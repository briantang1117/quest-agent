"""基于版面文本的保守候选切题；不替代结构化校验，不生成或发布答案。"""

import re
from collections import Counter
from dataclasses import dataclass, replace
from typing import TYPE_CHECKING, Literal, Protocol

if TYPE_CHECKING:
    from app.parser.source_evidence import EvidenceSegment


class SourcePage(Protocol):
    page_number: int
    layout_text: str
    image_count: int


@dataclass(frozen=True)
class QuestionCandidate:
    question_no: int | None
    source_text: str
    page_numbers: tuple[int, ...]
    reference_keys: tuple[str, ...]
    warnings: tuple[str, ...]
    status: Literal["pending_review"] = "pending_review"
    evidence_refs: tuple["EvidenceSegment", ...] = ()
    reference_explanation: str = ""


QUESTION_START = re.compile(r"^\s*(\d{1,4})\s*[.．、]\s*(\S.*)$")
REFERENCE = re.compile(r"(?:正确答案|参考答案)\s*[：:]\s*([A-H](?:[\s、,，/]*[A-H])*)\s*")
REFERENCE_LABEL = re.compile(r"(?:正确答案|参考答案)\s*[：:]")
PERSONAL = re.compile(r"(?:你的答案|您的答案|我的答案)\s*[：:]")
EXPLANATION = re.compile(r"^\s*(?:原文解析|参考解析|答案解析|解析)\s*[：:]")
ANSWER_TABLE = re.compile(r"^\s*(?:参考答案|答案汇总|答案表)\s*$")


def split_candidates(
    pages: list[SourcePage] | tuple[SourcePage, ...],
) -> tuple[QuestionCandidate, ...]:
    # 只剔除跨多页完全相同的首个非空页眉，不擅自删除正文中的重复材料。
    headers = [
        next((line.strip() for line in page.layout_text.splitlines() if line.strip()), "")
        for page in pages
    ]
    repeated_headers = {
        header for header, count in Counter(headers).items() if header and count > 1
    }
    candidates = []
    current_no = None
    lines: list[str] = []
    source_pages: list[int] = []
    warnings: set[str] = set()
    in_answer_table = False

    def finish():
        if current_no is None:
            return
        source = "\n".join(lines).strip()
        references = set()
        explanation_start = next(
            (i for i, line in enumerate(lines) if EXPLANATION.match(line)), None
        )
        body = lines if explanation_start is None else lines[:explanation_start]
        for line in body:
            label = REFERENCE_LABEL.search(line)
            if label:
                match = REFERENCE.fullmatch(line[label.start() :])
                if match:
                    references.add(tuple(sorted(set(re.findall("[A-H]", match.group(1))))))
                else:
                    warnings.add("unrecognized_reference_answer")
        keys = next(iter(references)) if len(references) == 1 else ()
        if "unrecognized_reference_answer" in warnings:
            keys = ()
        if len(references) > 1:
            warnings.add("conflicting_reference_answers")
        elif not references and "unrecognized_reference_answer" not in warnings:
            warnings.add("missing_reference_answer")
        candidates.append(
            QuestionCandidate(
                current_no,
                source,
                tuple(source_pages),
                keys,
                tuple(sorted(warnings)),
                reference_explanation=(
                    "\n".join(lines[explanation_start:]).strip()
                    if explanation_start is not None
                    else ""
                ),
            )
        )

    for page in pages:
        first_nonempty = True
        for line in page.layout_text.splitlines():
            stripped = line.strip()
            if not stripped:
                continue
            if first_nonempty:
                first_nonempty = False
                if stripped in repeated_headers:
                    continue
            if ANSWER_TABLE.fullmatch(line):
                in_answer_table = True
                if current_no is not None:
                    warnings.add("standalone_answer_table_requires_matching")
                continue
            if in_answer_table:
                continue
            personal = PERSONAL.search(line)
            if personal:
                # 行内个人答案之后的范围不确定，保守截去整段后缀；原始证据仍保留。
                line = line[: personal.start()].rstrip()
                if not line:
                    continue
            start = QUESTION_START.match(line)
            # “1. A”一类答案表条目不能误当新题干。
            if start and not re.fullmatch(r"[A-H\s、,，/]+", start.group(2)):
                finish()
                current_no = int(start.group(1))
                lines = [line]
                source_pages = [page.page_number]
                warnings = set()
            elif current_no is not None:
                lines.append(line)
                if page.page_number not in source_pages:
                    source_pages.append(page.page_number)
            if current_no is not None and page.image_count:
                warnings.add("page_contains_images_unassigned")
    finish()
    counts = Counter(item.question_no for item in candidates)
    result = []
    for item in candidates:
        flags = set(item.warnings)
        if counts[item.question_no] > 1:
            flags.add("duplicate_question_number")
        result.append(replace(item, warnings=tuple(sorted(flags))))
    return tuple(result)
