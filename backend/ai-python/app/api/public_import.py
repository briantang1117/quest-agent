"""首段单题文本导入；不接收文件、不信任终端自报身份。"""

from dataclasses import replace
from types import SimpleNamespace

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field

from app.api.schemas import ExtractRequest
from app.core.errors import ServiceError, envelope
from app.parser.question_candidates import QUESTION_START, split_candidates

router = APIRouter()


class CandidateInput(BaseModel):
    model_config = ConfigDict(extra="forbid")
    question_no: int | None = Field(default=None, ge=1, strict=True)
    source_text: str = Field(min_length=1, max_length=12000)
    page_numbers: list[int] = Field(max_length=200)
    reference_keys: list[str] = Field(max_length=8)
    reference_explanation: str = Field(default="", max_length=20000)
    warnings: list[str] = Field(max_length=100)


class PublicImportRequest(ExtractRequest):
    text: str = Field(min_length=1, max_length=12000)
    candidate: CandidateInput | None = None


class PublicImportResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: int
    message: str
    data: dict
    traceId: str


@router.post("/internal/public-import", response_model=PublicImportResponse)
async def public_import(payload: PublicImportRequest, request: Request):
    if payload.scope != "admin":
        raise ServiceError(403, 40301, "公共导入仅允许管理员")
    graph = request.app.state.candidate_graph
    if graph is None:
        raise ServiceError(503, 50302, "文本模型未配置")
    text = payload.text.strip()
    if not text:
        raise ServiceError(422, 42201, "题目正文不能为空")
    numbered = QUESTION_START.match(text.splitlines()[0]) is not None
    items = split_candidates(
        [
            SimpleNamespace(
                page_number=1, image_count=0, layout_text=text if numbered else "1." + text
            )
        ]
    )
    if len(items) != 1:
        raise ServiceError(422, 42201, "此入口仅接收一道题，请确认题目边界")
    item = replace(
        items[0], question_no=items[0].question_no if numbered else None, page_numbers=()
    )
    if payload.candidate is not None:
        original = payload.candidate
        if original.source_text.strip() != text or any(
            type(page) is not int or not 1 <= page <= 200 for page in original.page_numbers
        ):
            raise ServiceError(422, 42201, "候选来源不匹配")
        # 原答案重新从可见文本识别，不能仅信任请求中的答案键。
        if set(original.reference_keys) != set(item.reference_keys):
            raise ServiceError(422, 42201, "候选原答案与原文不匹配")
        item = replace(
            item,
            question_no=original.question_no,
            page_numbers=tuple(original.page_numbers),
            warnings=tuple(dict.fromkeys((*item.warnings, *original.warnings))),
            reference_explanation=item.reference_explanation,
        )
    state = await graph.ainvoke({"candidate": item}, config={"recursion_limit": 12})
    return envelope(
        request.state.trace_id,
        data={
            "draft": state["draft"],
            "warnings": state["warnings"],
            "provenance": state["provenance"],
        },
    )
