"""本地PDF结构预检，不执行脚本、渲染、OCR或网络调用。"""

import argparse
import hashlib
import io
import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path

from pypdf import PdfReader

from app.parser._diagnostics import parser_diagnostics

MAX_PDF_BYTES = 20 * 1024 * 1024
MAX_PAGES = 200
MAX_TEXT_CHARS = 2_000_000
MAX_CONTENT_BYTES = 8 * 1024 * 1024


class PdfInspectionError(ValueError):
    pass


@dataclass(frozen=True)
class PageInspection:
    page_number: int
    width: float
    height: float
    text: str
    layout_text: str
    has_answer_annotations: bool
    text_length: int
    image_count: int
    needs_vision: bool
    warnings: tuple[str, ...]


@dataclass(frozen=True)
class PdfInspection:
    sha256: str
    size_bytes: int
    page_count: int
    has_active_content: bool
    pages: tuple[PageInspection, ...]
    warnings: tuple[str, ...]
    cloud_calls: int = 0


MAX_TOTAL_CONTENT_BYTES = 32 * 1024 * 1024
MAX_RESOURCES = 2000
MAX_RESOURCE_DEPTH = 8


class _ContentBudget:
    def __init__(self):
        self.total = 0
        self.seen: set[int] = set()

    def check(self, stream):
        identity = id(stream)
        if identity in self.seen:
            return
        self.seen.add(identity)
        # 解码后才可量长，仍需服务化时增加独立进程的内存/时间隔离。
        size = len(stream.get_data())
        self.total += size
        if size > MAX_CONTENT_BYTES or self.total > MAX_TOTAL_CONTENT_BYTES:
            raise PdfInspectionError("PDF内容流超过单流或累计上限，需拆分处理")


def _resources(resources, budget: _ContentBudget) -> int:
    heights: dict[int, int] = {}
    visiting: set[int] = set()
    images: set[int] = set()

    def visit(resources, depth=0) -> int:
        if depth > MAX_RESOURCE_DEPTH:
            raise PdfInspectionError("PDF资源嵌套过深，需拆分处理")
        resources = resources.get_object() if hasattr(resources, "get_object") else resources
        objects = resources.get("/XObject", {}) if resources else {}
        objects = objects.get_object() if hasattr(objects, "get_object") else objects
        height = 0
        for reference in objects.values():
            obj = reference.get_object()
            identity = id(obj)
            if identity in visiting:
                raise PdfInspectionError("PDF资源存在循环引用，需人工检查")
            if identity not in heights:
                if len(heights) + len(visiting) >= MAX_RESOURCES:
                    raise PdfInspectionError("PDF资源过多，需拆分处理")
                subtype = obj.get("/Subtype")
                if subtype == "/Image":
                    images.add(identity)
                    heights[identity] = 0
                elif subtype == "/Form":
                    budget.check(obj)
                    visiting.add(identity)
                    heights[identity] = 1 + visit(obj.get("/Resources", {}), depth + 1)
                    visiting.remove(identity)
                else:
                    raise PdfInspectionError("PDF含不支持的XObject资源，需人工检查")
            # 去重解码不等于去重路径；共享DAG的最长调用链也必须受限。
            if depth + heights[identity] > MAX_RESOURCE_DEPTH:
                raise PdfInspectionError("PDF资源嵌套过深，需拆分处理")
            height = max(height, heights[identity])
        return height

    visit(resources)
    return len(images)


def _page_contents(page, budget: _ContentBudget):
    # 不先get_contents()合并数组，否则预算检查前就可能分配大块内存。
    contents = page.get("/Contents")
    if contents is None:
        return
    contents = contents.get_object()
    for reference in contents if isinstance(contents, list) else [contents]:
        budget.check(reference.get_object())


def inspect_pdf(
    data: bytes,
    *,
    max_bytes: int = MAX_PDF_BYTES,
    max_pages: int = MAX_PAGES,
    max_text_chars: int = MAX_TEXT_CHARS,
) -> PdfInspection:
    if min(max_bytes, max_pages, max_text_chars) <= 0:
        raise PdfInspectionError("预检限制必须为正数")
    if not isinstance(data, bytes) or not data or len(data) > max_bytes:
        raise PdfInspectionError("PDF为空或超过体积上限")
    if not data.startswith(b"%PDF-"):
        raise PdfInspectionError("不是受支持的PDF文件")
    with parser_diagnostics() as diagnostics:
        return _inspect_pdf(data, max_pages, max_text_chars, diagnostics)


def _inspect_pdf(data: bytes, max_pages: int, max_text_chars: int, diagnostics) -> PdfInspection:
    try:
        reader = PdfReader(io.BytesIO(data), strict=True)
        if reader.is_encrypted:
            raise PdfInspectionError("加密PDF暂不支持，请提供无密码副本")
        if not 1 <= len(reader.pages) <= max_pages:
            raise PdfInspectionError("PDF页数为空或超过上限")
        root = reader.trailer["/Root"]
        names = root.get("/Names", {})
        names = names.get_object() if hasattr(names, "get_object") else names
        active = bool(root.get("/OpenAction") or root.get("/AA") or names.get("/JavaScript"))
        pages = []
        total_chars = 0
        budget = _ContentBudget()
        # 全文资源先预检，再进入任何页面文本提取；图片只数资源、不解码像素。
        image_counts = []
        for page in reader.pages:
            _page_contents(page, budget)
            image_counts.append(_resources(page.get("/Resources", {}), budget))
        for number, page in enumerate(reader.pages, 1):
            warnings = []
            active = active or bool(page.get("/AA"))
            text = page.extract_text() or ""
            # 两种提取结果都保留：普通模式可能先输出答案块，layout有助恢复阅读顺序。
            layout_text = page.extract_text(extraction_mode="layout") or ""
            total_chars += len(text) + len(layout_text)
            if total_chars > max_text_chars:
                raise PdfInspectionError("PDF文本超过提取上限")
            if text.strip() and not layout_text.strip():
                layout_text = text
                warnings.append("layout_text_unavailable")
            image_count = image_counts[number - 1]
            if not text.strip():
                warnings.append("no_text_layer")
            if image_count:
                warnings.append("contains_images")
            answer_annotations = "正确答案" in text and "你的答案" in text
            if answer_annotations:
                warnings.append("separate_reference_and_user_answers")
            pages.append(
                PageInspection(
                    page_number=number,
                    width=float(page.mediabox.width),
                    height=float(page.mediabox.height),
                    text=text,
                    layout_text=layout_text,
                    has_answer_annotations=answer_annotations,
                    text_length=len(text),
                    image_count=image_count,
                    needs_vision=bool(warnings),
                    warnings=tuple(warnings),
                )
            )
        return PdfInspection(
            sha256=hashlib.sha256(data).hexdigest(),
            size_bytes=len(data),
            page_count=len(pages),
            has_active_content=active,
            pages=tuple(pages),
            warnings=tuple(
                flag
                for flag, enabled in (
                    ("active_content", active),
                    ("parser_diagnostics", diagnostics[0]),
                )
                if enabled
            ),
        )
    except PdfInspectionError:
        raise
    except Exception:
        raise PdfInspectionError("PDF结构无效或文本提取失败，需人工检查原文件") from None


def inspect_file(source: Path, output: Path) -> PdfInspection:
    if source.resolve() == output.resolve() or output.exists():
        raise PdfInspectionError("输出文件已存在或与源文件相同，禁止覆盖")
    try:
        with source.open("rb") as handle:
            raw = handle.read(MAX_PDF_BYTES + 1)
        report = inspect_pdf(raw)
        # 独占写、仅本人可读；不创建任意父目录，不覆盖已有源文件/报告。
        with open(
            output, "x", encoding="utf-8", opener=lambda path, flags: os.open(path, flags, 0o600)
        ) as handle:
            json.dump(asdict(report), handle, ensure_ascii=False, indent=2)
        return report
    except FileExistsError:
        raise PdfInspectionError("输出文件已存在，禁止覆盖") from None


def main() -> None:
    parser = argparse.ArgumentParser(description="仅本地PDF预检，无OCR/模型调用")
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = inspect_file(args.source, args.output)
    except (PdfInspectionError, OSError) as exc:
        # 不输出PDF原文；文件系统错误只给类型，避免泄露任意路径或内容。
        parser.exit(1, (str(exc) if isinstance(exc, PdfInspectionError) else "文件读写失败") + "\n")
    print(
        json.dumps(
            {
                "page_count": report.page_count,
                "size_bytes": report.size_bytes,
                "text_pages": sum(bool(page.text.strip()) for page in report.pages),
                "pages_with_images": sum(page.image_count > 0 for page in report.pages),
                "needs_vision_pages": sum(page.needs_vision for page in report.pages),
                "has_active_content": report.has_active_content,
                "cloud_calls": 0,
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
