"""仅供Java鉴权后调用的个人Agent意图/讲解，不直接对浏览器公开。"""

from typing import Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field

from app.core.errors import ServiceError, envelope

router = APIRouter()


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Option(Contract):
    key: Literal["A", "B", "C", "D", "E", "F", "G", "H"]
    text_md: str = Field(min_length=1, max_length=5000)


class VisibleQuestion(Contract):
    stem_md: str = Field(min_length=1, max_length=5000)
    type: Literal["single_choice", "multiple_choice"] = "single_choice"
    options: list[Option] = Field(min_length=2, max_length=8)


class SubmittedResult(Contract):
    correct: bool
    selected_keys: list[str] = Field(max_length=8)
    answer_keys: list[str] = Field(min_length=1, max_length=8)
    explanation_md: str = Field(max_length=20000)


class HistoryMessage(Contract):
    role: Literal["user", "assistant"]
    text: str = Field(max_length=6000)


class CoachRequest(Contract):
    scope: Literal["admin", "student"]
    subject: Literal["vocational_aptitude", "administrative_aptitude"] = "vocational_aptitude"
    message: str = Field(min_length=1, max_length=2000)
    history: list[HistoryMessage] = Field(default_factory=list, max_length=12)
    question: VisibleQuestion | None = None
    result: SubmittedResult | None = None


@router.post("/internal/coach-turn")
async def coach_turn(payload: CoachRequest, request: Request):
    if payload.scope != "student":
        raise ServiceError(403, 40301, "陪练仅允许普通用户")
    graph = request.app.state.coach_graph
    if graph is None:
        raise ServiceError(503, 50302, "文本模型未配置")
    state = await graph.ainvoke(
        {"context": payload.model_dump(exclude={"scope"}, exclude_none=True)}
    )
    return envelope(request.state.trace_id, data=state["result"])
