"""Java 冻结公开消息后经 HMAC 调用的私人学习资料边界。"""

from typing import Literal

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from app.agent.materials import generate_material
from app.core.errors import ServiceError, envelope

router = APIRouter()
ChoiceKey = Literal["A", "B", "C", "D", "E", "F", "G", "H"]


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class MaterialOption(Contract):
    key: ChoiceKey
    textMd: str = Field(min_length=1, max_length=5000)


class MaterialQuestion(Contract):
    id: str = Field(min_length=1, max_length=200)
    type: Literal["single_choice", "multiple_choice"]
    stemMd: str = Field(min_length=1, max_length=5000)
    options: list[MaterialOption] = Field(min_length=2, max_length=8)

    @model_validator(mode="after")
    def unique_options(self):
        if len({item.key for item in self.options}) != len(self.options):
            raise ValueError("题目选项重复")
        return self


class MaterialResult(Contract):
    correct: bool
    selectedKeys: list[ChoiceKey] = Field(min_length=1, max_length=8)
    answerKeys: list[ChoiceKey] = Field(min_length=1, max_length=8)
    explanationMd: str = Field(max_length=20000)

    @model_validator(mode="after")
    def consistent_result(self):
        for keys in (self.selectedKeys, self.answerKeys):
            if len(set(keys)) != len(keys):
                raise ValueError("判分选项重复")
        if self.correct != (set(self.selectedKeys) == set(self.answerKeys)):
            raise ValueError("判分结果与选项不一致")
        return self


class MaterialMessage(Contract):
    id: str = Field(min_length=1, max_length=200)
    role: Literal["user", "assistant"]
    text: str
    question: MaterialQuestion | None = None
    result: MaterialResult | None = None

    @model_validator(mode="after")
    def public_question_result(self):
        if self.role != "assistant" and (self.question is not None or self.result is not None):
            raise ValueError("题卡和判分只能来自公开助手消息")
        if self.result is not None:
            if self.question is None:
                raise ValueError("判分缺少关联公开题目")
            available = {item.key for item in self.question.options}
            if not available.issuperset(self.result.selectedKeys + self.result.answerKeys):
                raise ValueError("判分包含题目外选项")
            if self.question.type == "single_choice":
                if len(self.result.selectedKeys) != 1 or len(self.result.answerKeys) != 1:
                    raise ValueError("单选题判分数量不匹配")
            elif len(self.result.answerKeys) < 2:
                raise ValueError("多选题答案数量不匹配")
        return self


class MaterialRequest(Contract):
    scope: Literal["student", "admin"]
    subject: Literal["vocational_aptitude", "administrative_aptitude"]
    source_title: str = Field(min_length=1, max_length=300)
    instructions: str | None = Field(default=None, max_length=10000)
    messages: list[MaterialMessage] = Field(min_length=1)


class MaterialReply(Contract):
    title: str = Field(min_length=1, max_length=300)
    markdown: str = Field(min_length=1)

    @field_validator("title", "markdown")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("资料标题或正文为空")
        return value


def _content_chars(value) -> int:
    if isinstance(value, str):
        return len(value)
    if isinstance(value, list):
        return sum(_content_chars(item) for item in value)
    if isinstance(value, dict):
        return sum(_content_chars(item) for item in value.values())
    return 0


@router.post("/internal/material-generate")
async def material_generate(payload: MaterialRequest, request: Request):
    if payload.scope != "student":
        raise ServiceError(403, 40301, "学习资料仅允许普通用户")
    config = request.app.state.settings
    context = payload.model_dump(exclude={"scope"}, exclude_none=True)
    if _content_chars(context) > config.material_max_input_chars:
        raise ServiceError(422, 42201, "资料内容超过字符上限，请缩小范围")
    provider = request.app.state.provider
    if provider is None or not callable(getattr(provider, "generate_material", None)):
        raise ServiceError(503, 50302, "资料文本模型未配置")
    content = await generate_material(provider, context)
    try:
        if not isinstance(content, str) or len(content.encode("utf-8")) > 1024 * 1024:
            raise ValueError("资料模型输出超过大小上限")
        result = MaterialReply.model_validate_json(content)
        if len(result.markdown) > config.material_max_output_chars:
            raise ValueError("资料正文超过字符上限")
    except (ValueError, TypeError, RecursionError) as exc:
        raise ServiceError(502, 50203, "资料模型返回无效或不完整内容") from exc
    return envelope(request.state.trace_id, data=result.model_dump())
