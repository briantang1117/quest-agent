"""父进程只做有界传输和严格结果校验，不导入PDF解析器。"""

import hashlib
import json
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.parser.pdf_runner import PdfRunnerError, run_pdf_worker

WORKER_SCRIPT = Path(__file__).resolve().with_name("syllabus_worker.py")


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class Page(Contract):
    page: int = Field(ge=1, le=200)
    printedPage: str | None = Field(max_length=32)
    text: str = Field(max_length=1_000_000)


class Section(Contract):
    ordinal: int = Field(ge=1, le=1000)
    title: str = Field(min_length=1, max_length=300)
    path: str = Field(min_length=1, max_length=1000)
    kind: Literal["overview", "requirement", "example", "other"]
    scope: Literal["vocational_aptitude", "administrative_aptitude", "excluded"]
    enabled: bool
    pageStart: int = Field(ge=1, le=200)
    pageEnd: int = Field(ge=1, le=200)
    printedPages: str = Field(max_length=6600)
    text: str = Field(min_length=1, max_length=12000)


class Result(Contract):
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    pageCount: int = Field(ge=1, le=200)
    pages: list[Page] = Field(min_length=1, max_length=200)
    sections: list[Section] = Field(max_length=1000)
    warnings: list[str] = Field(max_length=5000)

    @model_validator(mode="after")
    def check_evidence(self):
        if [p.page for p in self.pages] != list(range(1, self.pageCount + 1)):
            raise ValueError("考纲物理页不连续")
        if [s.ordinal for s in self.sections] != list(range(1, len(self.sections) + 1)):
            raise ValueError("章节顺序无效")
        if sum(len(p.text) for p in self.pages) > 1_000_000:
            raise ValueError("考纲正文总量超限")
        for section in self.sections:
            if section.enabled or not section.pageStart <= section.pageEnd <= self.pageCount:
                raise ValueError("章节页码无效")
            labels = list(
                dict.fromkeys(
                    p.printedPage
                    for p in self.pages[section.pageStart - 1 : section.pageEnd]
                    if p.printedPage is not None
                )
            )
            if section.printedPages != ",".join(labels):
                raise ValueError("章节印刷页码无效")
        if "pending_review" not in self.warnings:
            raise ValueError("缺少草稿标记")
        return self


def run_syllabus_inspection(raw: bytes) -> dict:
    try:
        result = Result.model_validate(json.loads(run_pdf_worker(raw, WORKER_SCRIPT)))
        if result.sha256 != hashlib.sha256(raw).hexdigest():
            raise ValueError("原件摘要不匹配")
        return result.model_dump()
    except PdfRunnerError:
        raise
    except (ValueError, TypeError, RecursionError):
        raise PdfRunnerError("worker_failed") from None
