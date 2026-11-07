from typing import Annotated, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

NonBlank = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1)]
OptionKey = Literal["A", "B", "C", "D", "E", "F", "G", "H"]
KnowledgeCode = Annotated[
    str,
    StringConstraints(pattern=r"^[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*$"),
]


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Option(Contract):
    key: OptionKey
    text_md: NonBlank
    image: str | None = None


class Answer(Contract):
    keys: list[OptionKey] = Field(min_length=1, max_length=8)
    source: Literal["ai_generated"] = "ai_generated"
    confidence: float | None = Field(default=None, ge=0, le=1)


class QuestionSource(Contract):
    exam_type: str | None = None
    exam_name: str | None = None
    year: int | None = None
    region: str | None = None
    paper_id: int | None = None
    question_no: int | None = None


class Quality(Contract):
    ai_confidence: float | None = Field(default=None, ge=0, le=1)
    review_status: Literal["pending"] = "pending"
    dup_group: str | None = None


class QuestionDraft(Contract):
    schema_version: Literal["1.0"] = "1.0"
    type: Literal["single_choice", "multiple_choice"]
    stem_md: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=5000)]
    stem_images: list[str] = Field(default_factory=list)
    options: list[Option] = Field(min_length=2, max_length=8)
    answer: Answer
    explanation_md: str = ""
    knowledge_points: list[KnowledgeCode] = Field(min_length=1)
    difficulty: int = Field(ge=1, le=5, strict=True)
    source: QuestionSource = Field(default_factory=QuestionSource)
    tags: list[str] = Field(default_factory=list)
    status: Literal["draft"] = "draft"
    quality: Quality = Field(default_factory=Quality)

    @model_validator(mode="after")
    def validate_options_and_answers(self) -> Self:
        keys = [option.key for option in self.options]
        if keys != list("ABCDEFGH"[: len(keys)]):
            raise ValueError("options must have unique contiguous A-H keys in order")
        answers = self.answer.keys
        if len(set(answers)) != len(answers) or not set(answers).issubset(keys):
            raise ValueError("answer keys must be a unique subset of option keys")
        if self.type == "single_choice" and len(answers) != 1:
            raise ValueError("single choice requires exactly one answer")
        if self.type == "multiple_choice" and len(answers) < 2:
            raise ValueError("multiple choice requires at least two answers")
        if len(set(self.knowledge_points)) != len(self.knowledge_points):
            raise ValueError("knowledge point codes must be unique")
        return self


class ExtractRequest(Contract):
    scope: Literal["admin", "student"]
    text: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=50000)]


class ExtractResult(Contract):
    questions: list[QuestionDraft] = Field(min_length=1, max_length=100)


class ApiResponse(Contract):
    code: int
    message: str
    data: ExtractResult | None
    traceId: str
