from fastapi import APIRouter, Request

from app.api.schemas import ApiResponse, ExtractRequest
from app.core.errors import ServiceError, envelope

router = APIRouter(prefix="/internal")


@router.post("/extract", response_model=ApiResponse)
async def extract(payload: ExtractRequest, request: Request) -> dict:
    if payload.scope != "admin":
        raise ServiceError(403, 40301, "Extraction requires admin scope")
    graph = request.app.state.extraction_graph
    if graph is None:
        raise ServiceError(503, 50302, "LLM provider is not configured")
    state = await graph.ainvoke({"text": payload.text}, config={"recursion_limit": 12})
    result = state["result"]
    return envelope(request.state.trace_id, data=result.model_dump(mode="json"))
