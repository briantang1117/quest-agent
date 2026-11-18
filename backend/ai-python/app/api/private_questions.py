import asyncio
from typing import Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field

from app.core.errors import ServiceError, envelope
from app.private_questions.pipeline import Candidate, check_candidate, extract_source

router = APIRouter()


class SourceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    scope: Literal["student"]
    kind: Literal["text", "image", "pdf"]
    content: str = Field(min_length=1, max_length=12_000_000)


class CheckRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    scope: Literal["student"]
    candidate: Candidate
    allow_search: bool = True


@router.post("/internal/private-source")
async def source(payload: SourceRequest, request: Request):
    try:
        async with asyncio.timeout(300):
            result = await extract_source(
                request.app.state.provider,
                request.app.state.ocr_provider,
                kind=payload.kind,
                content=payload.content,
            )
    except TimeoutError as exc:
        raise ServiceError(504, 50460, "识别超时，请拆分附件；未自动重试") from exc
    return envelope(request.state.trace_id, data=result)


@router.post("/internal/private-check")
async def check(payload: CheckRequest, request: Request):
    try:
        async with asyncio.timeout(300):
            result = await check_candidate(
                request.app.state.provider,
                request.app.state.search_provider if payload.allow_search else None,
                payload.candidate.model_dump(),
            )
    except TimeoutError as exc:
        raise ServiceError(504, 50460, "校对超时，未自动重试") from exc
    return envelope(request.state.trace_id, data=result)
