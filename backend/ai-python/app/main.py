from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException

from app.agent.extraction_graph import build_extraction_graph
from app.api.routes import router
from app.core.config import Settings
from app.core.errors import ServiceError, error_response
from app.core.security import NonceStore, authenticate
from app.llm.base import LLMProvider
from app.llm.openai_compatible import OpenAICompatibleProvider


def create_app(settings: Settings | None = None, *, provider: LLMProvider | None = None) -> FastAPI:
    config = settings if settings is not None else Settings()

    @asynccontextmanager
    async def lifespan(application: FastAPI):
        application.state.provider = provider
        if provider is None and config.llm_configured:
            application.state.provider = OpenAICompatibleProvider(config)
        try:
            application.state.extraction_graph = (
                build_extraction_graph(application.state.provider)
                if application.state.provider is not None
                else None
            )
            yield
        finally:
            application.state.extraction_graph = None
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
    return application


app = create_app()
