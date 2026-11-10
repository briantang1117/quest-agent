from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException

from app.agent.candidate_graph import build_candidate_graph
from app.agent.coach_graph import build_coach_graph
from app.agent.extraction_graph import build_extraction_graph
from app.api.coach import router as coach_router
from app.api.pdf_import import router as pdf_import_router
from app.api.public_import import router as public_import_router
from app.api.routes import router
from app.api.syllabus import router as syllabus_router
from app.core.config import Settings
from app.core.errors import ServiceError, error_response
from app.core.security import NonceStore, authenticate
from app.llm.base import LLMProvider
from app.llm.openai_compatible import OpenAICompatibleProvider
from app.providers.ark import ArkEmbeddingProvider
from app.providers.deepseek import DeepSeekSearchProvider


def create_app(
    settings: Settings | None = None,
    *,
    provider: LLMProvider | None = None,
    embedding_provider: ArkEmbeddingProvider | None = None,
    search: DeepSeekSearchProvider | None = None,
) -> FastAPI:
    config = settings if settings is not None else Settings()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        application.state.provider = provider
        if provider is None and config.llm_configured:
            application.state.provider = OpenAICompatibleProvider(config)
        application.state.search_provider = search
        application.state.embedding_provider = embedding_provider
        try:
            if embedding_provider is None and config.embedding_configured:
                application.state.embedding_provider = ArkEmbeddingProvider(config)
            if config.search_configured:
                application.state.search_provider = DeepSeekSearchProvider(config)
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
            application.state.extraction_graph = None
            application.state.candidate_graph = None
            application.state.coach_graph = None
            if application.state.embedding_provider is not None:
                await application.state.embedding_provider.aclose()
            if application.state.search_provider is not None:
                await application.state.search_provider.aclose()
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
            response = await call_next(request)
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
    application.include_router(coach_router)
    application.include_router(public_import_router)
    application.include_router(pdf_import_router)
    application.include_router(syllabus_router)
    return application


app = create_app()
