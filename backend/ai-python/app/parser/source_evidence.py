"""多来源文本证据与显式语义分段；不猜题目边界，不提供伪造图像坐标。"""

import re
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

from app.parser.pdf_inspection import PdfInspection
from app.parser.question_candidates import QuestionCandidate

Identifier = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]


class SourceEvidence(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    evidence_id: Identifier
    source_id: Identifier
    sha256: Annotated[str, StringConstraints(pattern=r"^[a-f0-9]{64}$")]
    kind: Literal["pdf_page", "image"]
    ordinal: int = Field(gt=0, strict=True)
    page_number: int | None = Field(default=None, gt=0, strict=True)
    text: str = Field(max_length=2_000_000)
    plain_text: str | None = Field(default=None, max_length=2_000_000)
    image_ref: Identifier | None = None
    warnings: tuple[str, ...] = ()

    @model_validator(mode="after")
    def page_identity(self):
        if (self.kind == "pdf_page") != (self.page_number is not None):
            raise ValueError("仅PDF页必须携带页码，图片使用序号")
        return self


class EvidenceSegment(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    evidence_id: Identifier
    role: Literal["question", "reference_answer", "reference_explanation", "personal_answer", "ui"]
    start: int = Field(ge=0, strict=True)
    end: int = Field(gt=0, strict=True)

    @model_validator(mode="after")
    def valid_range(self):
        if self.end <= self.start:
            raise ValueError("证据文本区间必须非空")
        return self


_REFERENCE = re.compile(r"\s*(?:正确答案|参考答案)\s*[：:]\s*([A-H](?:[\s、,，/]*[A-H])*)\s*")


def assemble_candidate(
    evidence: list[SourceEvidence] | tuple[SourceEvidence, ...],
    segments: list[EvidenceSegment] | tuple[EvidenceSegment, ...],
    *,
    question_no: int | None = None,
) -> QuestionCandidate:
    if question_no is not None and (type(question_no) is not int or question_no <= 0):
        raise ValueError("题号必须为空或正整数")
    sources = {item.evidence_id: item for item in evidence}
    if len(sources) != len(evidence):
        raise ValueError("证据ID重复")
    ranges: dict[str, list[tuple[int, int]]] = {}
    refs = []
    lines = []
    explanations = []
    answers: set[tuple[str, ...]] = set()
    pages = []
    warnings = set()
    has_question = False
    for part in segments:
        source = sources.get(part.evidence_id)
        if source is None or not 0 <= part.start < part.end <= len(source.text):
            raise ValueError("证据引用不存在或文本区间越界")
        used = ranges.setdefault(part.evidence_id, [])
        if any(part.start < end and part.end > start for start, end in used):
            raise ValueError("语义片段重叠，需人工确认边界")
        used.append((part.start, part.end))
        if part.role in {"personal_answer", "ui"}:
            continue
        text = source.text[part.start : part.end].strip()
        if not text:
            raise ValueError("有效题目片段不能为空白")
        refs.append(part)
        lines.append(text)
        warnings.update(source.warnings)
        if source.page_number is not None and source.page_number not in pages:
            pages.append(source.page_number)
        if part.role == "question":
            has_question = True
        elif part.role == "reference_explanation":
            explanations.append(text)
        elif part.role == "reference_answer":
            match = _REFERENCE.fullmatch(text)
            if match:
                answers.add(tuple(sorted(set(re.findall("[A-H]", match.group(1))))))
            else:
                warnings.add("unrecognized_reference_answer")
    if not has_question:
        raise ValueError("尚未确定题目正文，不能猜测生成候选题")
    if len(answers) > 1:
        warnings.add("conflicting_reference_answers")
    elif not answers and "unrecognized_reference_answer" not in warnings:
        warnings.add("missing_reference_answer")
    return QuestionCandidate(
        question_no=question_no,
        source_text="\n".join(lines),
        page_numbers=tuple(pages),
        reference_keys=(
            next(iter(answers))
            if len(answers) == 1 and "unrecognized_reference_answer" not in warnings
            else ()
        ),
        warnings=tuple(sorted(warnings)),
        evidence_refs=tuple(refs),
        reference_explanation="\n".join(explanations),
    )


def pdf_evidence(report: PdfInspection, *, source_id: str) -> tuple[SourceEvidence, ...]:
    return tuple(
        SourceEvidence(
            evidence_id=f"{source_id}:page:{page.page_number}",
            source_id=source_id,
            sha256=report.sha256,
            kind="pdf_page",
            ordinal=page.page_number,
            page_number=page.page_number,
            text=page.layout_text,
            plain_text=page.text,
            warnings=tuple(dict.fromkeys((*report.warnings, *page.warnings))),
        )
        for page in report.pages
    )
