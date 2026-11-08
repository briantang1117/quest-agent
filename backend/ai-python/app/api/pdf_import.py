"""受内部HMAC保护的管理员本地PDF批量预检，不调用OCR、模型或检索。"""

import base64
import binascii
from typing import Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field

from app.core.errors import ServiceError, envelope
from app.parser.pdf_runner import MAX_PDF_BYTES, PdfRunnerError, run_pdf_inspection

router = APIRouter()
MAX_BASE64_CHARS = 4 * ((MAX_PDF_BYTES + 2) // 3)


class PdfInspectRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    scope: Literal["admin", "student"]
    pdf_base64: str = Field(min_length=1, strict=True)


@router.post("/internal/pdf-inspect")
def pdf_inspect(payload: PdfInspectRequest, request: Request):
    # 身份由已验签的Java调用方确定，不能作为浏览器自报Admin的公开入口。
    if payload.scope != "admin":
        raise ServiceError(403, 40301, "公共PDF预检仅允许管理员")
    if len(payload.pdf_base64) > MAX_BASE64_CHARS:
        raise ServiceError(413, 41301, "PDF超过20MiB上限")
    try:
        raw = base64.b64decode(payload.pdf_base64, validate=True)
    except (binascii.Error, ValueError):
        raise ServiceError(422, 42201, "PDF编码无效") from None
    if len(raw) > MAX_PDF_BYTES:
        raise ServiceError(413, 41301, "PDF超过20MiB上限")
    try:
        data = run_pdf_inspection(raw)
    except PdfRunnerError as exc:
        if exc.reason == "timeout":
            raise ServiceError(504, 50401, "PDF预检超时，请拆分文件后重试") from None
        if exc.reason == "worker_failed":
            raise ServiceError(503, 50303, "PDF预检进程不可用") from None
        raise ServiceError(422, 42201, "PDF无效或超过预检限制，请检查并拆分文件") from None
    return envelope(request.state.trace_id, data=data)
