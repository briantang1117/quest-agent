"""HMAC考纲离线预检、AI整理与嵌入；不写业务库，不启用或发布。"""

import base64
import binascii
import math
from typing import Annotated, Literal

from fastapi import APIRouter, Request
from pydantic import Field, StringConstraints, field_validator

from app.agent.syllabus_review import ReviewRequest, StrictContract, review_syllabus
from app.api.pdf_import import MAX_BASE64_CHARS, PdfInspectRequest
from app.core.errors import ServiceError, envelope
from app.parser.pdf_runner import MAX_PDF_BYTES, PdfRunnerError
from app.parser.syllabus_runner import run_syllabus_inspection
from app.providers.ark import MAX_VECTOR_DIMENSIONS
from app.providers.models import EmbeddingResult

router = APIRouter()


@router.post("/internal/syllabus-inspect")
def syllabus_inspect(payload: PdfInspectRequest, request: Request):
    if payload.scope != "admin":
        raise ServiceError(403, 40301, "考纲预检仅允许管理员")
    if len(payload.pdf_base64) > MAX_BASE64_CHARS:
        raise ServiceError(413, 41301, "PDF超过20MiB上限")
    try:
        raw = base64.b64decode(payload.pdf_base64, validate=True)
    except (binascii.Error, ValueError):
        raise ServiceError(422, 42201, "PDF编码无效") from None
    if len(raw) > MAX_PDF_BYTES:
        raise ServiceError(413, 41301, "PDF超过20MiB上限")
    try:
        data = run_syllabus_inspection(raw)
    except PdfRunnerError as exc:
        if exc.reason == "timeout":
            raise ServiceError(504, 50401, "考纲预检超时，请拆分文件后重试") from None
        if exc.reason == "worker_failed":
            raise ServiceError(503, 50303, "考纲预检进程不可用") from None
        raise ServiceError(422, 42201, "PDF无效或超过预检限制，请检查并拆分文件") from None
    return envelope(request.state.trace_id, data=data)


@router.post("/internal/syllabus-review")
async def syllabus_review(payload: ReviewRequest, request: Request):
    if payload.scope != "admin":
        raise ServiceError(403, 40301, "考纲整理仅允许管理员")
    provider = request.app.state.provider
    if provider is None:
        raise ServiceError(503, 50302, "文本模型未配置")
    return envelope(request.state.trace_id, data=await review_syllabus(payload, provider))


class EmbedRequest(StrictContract):
    scope: Literal["admin", "student"]
    texts: list[Annotated[str, StringConstraints(min_length=1, max_length=3000)]] = Field(
        min_length=1, max_length=8
    )

    @field_validator("texts")
    @classmethod
    def bounded_texts(cls, items):
        if any(not item.strip() for item in items) or sum(map(len, items)) > 12000:
            raise ValueError("嵌入文本为空或总量超过上限")
        return items


def _validate_embedding(result: EmbeddingResult):
    # 注入替身也必须满足同一安全边界；拒绝布尔、字符串、非有限值和零向量。
    try:
        if (
            not isinstance(result.model, str)
            or not result.model.strip()
            or type(result.dimensions) is not int
            or not 1 <= result.dimensions <= MAX_VECTOR_DIMENSIONS
            or not isinstance(result.vector, (tuple, list))
            or len(result.vector) != result.dimensions
            or any(
                type(value) not in {int, float} or not math.isfinite(value)
                for value in result.vector
            )
            or not any(value != 0 for value in result.vector)
        ):
            raise ValueError("嵌入结果无效")
    except (AttributeError, TypeError, ValueError, OverflowError):
        raise ServiceError(502, 50202, "Embedding上游返回无效或不完整数据") from None


@router.post("/internal/syllabus-embed")
async def syllabus_embed(payload: EmbedRequest, request: Request):
    provider = request.app.state.embedding_provider
    if provider is None:
        raise ServiceError(503, 50302, "Embedding工具未配置")
    model, dimensions, vectors = None, None, []
    for text in payload.texts:
        result = await provider.embed(text)
        _validate_embedding(result)
        if model is not None and (result.model != model or result.dimensions != dimensions):
            raise ServiceError(502, 50202, "Embedding上游模型或维度不一致")
        model, dimensions = result.model, result.dimensions
        vectors.append([float(value) for value in result.vector])
    return envelope(
        request.state.trace_id, data={"model": model, "dimensions": dimensions, "vectors": vectors}
    )
