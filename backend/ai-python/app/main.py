import asyncio
import os
from contextlib import AsyncExitStack, asynccontextmanager
from pathlib import Path
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from starlette.exceptions import HTTPException

from app.agent.candidate_graph import build_candidate_graph
from app.agent.coach_graph import build_coach_graph
from app.agent.coach_workflow import build_coach_workflow
from app.agent.extraction_graph import build_extraction_graph
from app.api.coach import router as coach_router
from app.api.coach_workflow import router as coach_workflow_router
from app.api.materials import router as materials_router
from app.api.pdf_import import router as pdf_import_router
from app.api.private_questions import router as private_questions_router
from app.api.public_import import router as public_import_router
from app.api.routes import router
from app.api.syllabus import router as syllabus_router
from app.clients.coach_workflow import CoachWorkflowClient
from app.clients.model_meter import checked_grant, meter_context
from app.core.config import Settings
from app.core.errors import ServiceError, error_response
from app.core.security import NonceStore, authenticate
from app.llm.base import LLMProvider
from app.llm.openai_compatible import OpenAICompatibleProvider
from app.providers.ark import ArkEmbeddingProvider, ArkOCRProvider
from app.providers.deepseek import DeepSeekFetchProvider, DeepSeekSearchProvider


def _checkpoint_path(value: str) -> str:
    if value == ":memory:":
        return value
    path = Path(value).expanduser().resolve()
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    # WAL/SHM继承数据库权限；不修改已有用户目录的权限。
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR, 0o600)
    os.close(descriptor)
    path.chmod(0o600)
    return str(path)


def create_app(
    settings: Settings | None = None,
    *,
    provider: LLMProvider | None = None,
    embedding_provider: ArkEmbeddingProvider | None = None,
    search: DeepSeekSearchProvider | None = None,
    fetch: DeepSeekFetchProvider | None = None,
    ocr: ArkOCRProvider | None = None,
) -> FastAPI:
    config = settings if settings is not None else Settings()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        application.state.provider = provider
        if provider is None and config.llm_configured:
            application.state.provider = OpenAICompatibleProvider(config)
        application.state.search_provider = search
        application.state.fetch_provider = fetch
        application.state.ocr_provider = ocr
        application.state.embedding_provider = embedding_provider
        resources = AsyncExitStack()
        workflow_client = CoachWorkflowClient(config)
        application.state.coach_workflow_client = workflow_client
        application.state.coach_workflow_active = set()
        application.state.coach_workflow_claim_lock = asyncio.Lock()
        try:
            checkpoint_path = await asyncio.to_thread(
                _checkpoint_path, config.coach_checkpoint_path
            )
            saver = await resources.enter_async_context(
                AsyncSqliteSaver.from_conn_string(checkpoint_path)
            )
            await saver.setup()
            application.state.coach_workflow_graph = build_coach_workflow(saver)
            if embedding_provider is None and config.embedding_configured:
                application.state.embedding_provider = ArkEmbeddingProvider(config)
            if search is None and config.search_configured:
                application.state.search_provider = DeepSeekSearchProvider(config)
            if ocr is None and config.ocr_configured:
                application.state.ocr_provider = ArkOCRProvider(config)
            if fetch is None and config.fetch_configured:
                application.state.fetch_provider = DeepSeekFetchProvider(config)
            application.state.candidate_graph = (
                build_candidate_graph(
                    application.state.provider, search=application.state.search_provider
                )
                if application.state.provider is not None
                else None
            )
            application.state.extraction_graph = (
                build_extraction_graph(application.state.provider)
                if application.state.provider is not None
                else None
            )
            application.state.coach_graph = (
                build_coach_graph(application.state.provider)
                if application.state.provider is not None
                else None
            )
            yield
        finally:
            application.state.coach_workflow_graph = None
            await workflow_client.aclose()
            await resources.aclose()
            application.state.extraction_graph = None
            application.state.candidate_graph = None
            application.state.coach_graph = None
            if application.state.ocr_provider is not None:
                await application.state.ocr_provider.aclose()
            if application.state.embedding_provider is not None:
                await application.state.embedding_provider.aclose()
            if application.state.search_provider is not None:
                await application.state.search_provider.aclose()
            if application.state.fetch_provider is not None:
                await application.state.fetch_provider.aclose()
            if application.state.provider is not None:
                await application.state.provider.aclose()

    application = FastAPI(
        title="Quest Internal AI Service",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        redirect_slashes=False,
    )
    nonces = NonceStore()
    application.state.settings = config
    application.state.provider = None
    application.state.embedding_provider = None
    application.state.extraction_graph = None

    @application.middleware("http")
    async def security_and_trace(request: Request, call_next):
        request.state.trace_id = uuid4().hex
        try:
            if request.url.path == "/internal" or request.url.path.startswith("/internal/"):
                await authenticate(request, config, nonces)
                # Query parameters are not signed by the protocol; reject them.
                if request.url.query:
                    raise ServiceError(400, 40001, "Internal routes do not accept query parameters")
            context_token = None
            if config.ai_metering_enabled and request.url.path.startswith("/internal/"):
                exempt = {
                    "/internal/material-pdf",
                    "/internal/pdf-inspect",
                    "/internal/syllabus-inspect",
                }
                if request.url.path not in exempt:
                    grant = checked_grant(
                        request.headers.get("X-Usage-Grant", ""), request.url.path, config
                    )
                    context_token = meter_context.set(
                        (grant, request.url.path.removeprefix("/internal/"))
                    )
            try:
                response = await call_next(request)
            finally:
                if context_token is not None:
                    meter_context.reset(context_token)
        except ServiceError as exc:
            response = error_response(request, exc)
        response.headers["X-Trace-Id"] = request.state.trace_id
        return response

    @application.exception_handler(ServiceError)
    async def handle_service_error(request: Request, exc: ServiceError):
        return error_response(request, exc)

    @application.exception_handler(RequestValidationError)
    async def handle_validation_error(request: Request, exc: RequestValidationError):
        # Do not echo raw input, internal credentials or provider output.
        return error_response(request, ServiceError(422, 42201, "Request validation failed"))

    @application.exception_handler(HTTPException)
    async def handle_http_error(request: Request, exc: HTTPException):
        return error_response(
            request, ServiceError(exc.status_code, exc.status_code * 100, "HTTP error")
        )

    @application.get("/health/live")
    async def live():
        return {"status": "alive"}

    application.include_router(router)
    application.include_router(private_questions_router)
    application.include_router(coach_router)
    application.include_router(coach_workflow_router)
    application.include_router(materials_router)
    application.include_router(public_import_router)
    application.include_router(pdf_import_router)
    application.include_router(syllabus_router)
    return application


app = create_app()
