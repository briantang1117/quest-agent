"""仅在隔离进程调用：按通用标题/段落整理考纲草稿，不判断考试年度。"""

import re

from app.parser.pdf_inspection import inspect_pdf

MAX_SECTION_CHARS = 12000
MAX_SECTIONS = 1000
_PAGE_NUMBER = re.compile(r"^[\s—–－\-·]*(\d{1,4}|[ivxlcdmIVXLCDM]{1,12})[\s—–－\-·]*$")
_HEADING = re.compile(
    r"^(?:第[一二三四五六七八九十百\d]+[部分章节篇]|"
    r"[一二三四五六七八九十百]+[、．.]|[（(][一二三四五六七八九十百\d]+[）)]|"
    r"\d+(?:\.\d+)*[、．.]?\s+)"
)
_CATEGORY = re.compile(r"[（(]?([A-EＡ-Ｅ])\s*类[）)]?", re.I)


_SENTENCE_END = tuple("。；！？：")
_LIST_START = re.compile(
    r"^(?:[（(]?[一二三四五六七八九十百]+[）)、．.]|[0-9]{1,3}[、．)）]|[•·●○▪-])"
)


def join_soft_wraps(text: str) -> str:
    """合并PDF物理折行，保留真正段落。

    只有"上一行未以句末标点结束且下一行不是新条目"才视为软换行。
    该规则确定、可复现，不改写任何字符，只删除排版产生的换行。
    """
    lines = text.split("\n")
    out: list[str] = []
    for line in lines:
        if not out:
            out.append(line)
            continue
        previous = out[-1]
        if not previous.strip() or not line.strip():
            out.append(line)
            continue
        # 句末标点、新条目、以及"上一行本身就是标题"都要断开。
        if (
            previous.rstrip().endswith(_SENTENCE_END)
            or _LIST_START.match(line.strip())
            or _heading(previous.strip())
            or _heading(line.strip())
        ):
            out.append(line)
            continue
        # 英文/数字断行需要补一个空格，中文直接相接。
        if previous[-1].isascii() and previous[-1].isalnum():
            out[-1] = previous.rstrip() + " " + line.lstrip()
        else:
            out[-1] = previous.rstrip() + line.lstrip()
    return "\n".join(out)


def _printed_page(text: str) -> str | None:
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    # 页脚优先；只识别独立短行，不把正文数字推断成页码。
    for line in [*lines[-2:][::-1], *lines[:1]]:
        match = _PAGE_NUMBER.fullmatch(line)
        if match:
            return match[1]
    return None


def _heading(line: str) -> bool:
    if len(line) > 140:
        return False
    compact = re.sub(r"\s+", "", line)
    if _HEADING.match(line) or re.match(r"^[【\[]?(?:例题|示例|样题)", compact):
        return True
    return bool(
        re.fullmatch(
            r"(?:职业能力倾向测验|行政职业能力测验|综合应用能力|申论)(?:[（(].*[）)])?", compact
        )
        or _CATEGORY.search(compact)
        and not re.search(r"[。；：]", compact)
        or compact.endswith(("考试大纲", "考试内容", "考试要求", "考试性质和目标"))
    )


def _level(line: str) -> int:
    if re.match(r"^第.+[部分篇]", line):
        return 0
    if re.match(r"^[一二三四五六七八九十百]+[、．.]", line):
        return 1
    if re.match(r"^[（(]", line):
        return 2
    return 3


def _title(line: str) -> str:
    cleaned = _HEADING.sub("", line, count=1).strip()
    cleaned = re.split(r"[。；]", cleaned, maxsplit=1)[0].strip()
    return cleaned[:300] or line[:300]


def _kind(title: str) -> str:
    if any(word in title for word in ("例题", "示例", "样题")):
        return "example"
    if any(
        word in title for word in ("性质", "目标", "概述", "说明", "介绍", "时间", "题型", "结构")
    ):
        return "overview"
    if any(word in title for word in ("要求", "内容", "范围", "能力", "测验", "测查")):
        return "requirement"
    return "other"


def extract_syllabus(raw: bytes) -> dict:
    report = inspect_pdf(raw)
    pages = [
        {"page": page.page_number, "printedPage": _printed_page(page.text), "text": page.text}
        for page in report.pages
    ]
    sections: list[dict] = []
    warnings = {"pending_review", *report.warnings}
    # 仅文档标题/机构行提供来源线索；例题地名、文件名和年份都不是依据。
    shanghai = any(
        re.search(r"上海(?:市)?[^。；：]{0,100}公务员[^。；：]{0,100}(?:考纲|大纲)", compact)
        or re.fullmatch(r"上海(?:市)?(?:公务员局|人力资源和社会保障局|人事考试中心)", compact)
        for p in pages
        for line in p["text"].splitlines()
        if len(compact := re.sub(r"\s+", "", line)) <= 200
    )
    category = None
    subject = "excluded"
    titles: list[tuple[int, str]] = []
    current = None

    def flush():
        nonlocal current
        if current is None:
            return
        current["ordinal"] = len(sections) + 1
        current["printedPages"] = ",".join(
            dict.fromkeys(
                page["printedPage"]
                for page in pages[current["pageStart"] - 1 : current["pageEnd"]]
                if page["printedPage"] is not None
            )
        )
        sections.append(current)
        if len(sections) > MAX_SECTIONS:
            raise ValueError("考纲章节数量超过上限")
        current = None

    def append(text, page, title, kind, scope):
        nonlocal current
        while text:
            if current is None:
                current = {
                    "title": title[:300],
                    "path": " / ".join(t for _, t in titles)[-1000:] or "正文",
                    "kind": kind,
                    "scope": scope,
                    "enabled": False,
                    "pageStart": page["page"],
                    "pageEnd": page["page"],
                    "text": "",
                }
            separator = "\n" if current["text"] else ""
            available = MAX_SECTION_CHARS - len(current["text"]) - len(separator)
            if available <= 0:
                flush()
                continue
            part, text = text[:available], text[available:]
            current["text"] += separator + part
            current["pageEnd"] = page["page"]
            if text:
                flush()

    title, kind, scope = "正文", "other", "excluded"
    for page in pages:
        for flag in report.pages[page["page"] - 1].warnings:
            warnings.add(f"page:{page['page']}:{flag}")
        if not page["text"].strip():
            warnings.add("requires_ocr")
        for raw_line in page["text"].splitlines():
            line = raw_line.strip()
            if not line:
                # 段落边界只切普通正文；例题/答案和连续的要求段保持证据关联。
                if kind == "other":
                    flush()
                continue
            if _PAGE_NUMBER.fullmatch(line):
                continue
            compact = re.sub(r"\s+", "", line)
            if _heading(line):
                flush()
                level = _level(line)
                titles = [(n, t) for n, t in titles if n < level]
                titles.append((level, _title(line)))
                category_match = _CATEGORY.search(compact)
                if category_match:
                    category = (
                        category_match[1].upper().translate(str.maketrans("ＡＢＣＤＥ", "ABCDE"))
                    )
                    subject = "excluded"
                elif re.match(r"^第.+[部分篇]", line):
                    category = None
                    subject = "excluded"
                if any(word in compact for word in ("综合应用能力", "申论", "专业科目")):
                    subject = "excluded"
                elif "职业能力倾向测验" in compact:
                    subject = "vocational_aptitude" if category == "A" else "excluded"
                elif "行政职业能力测验" in compact:
                    subject = "administrative_aptitude" if shanghai else "excluded"
                title, kind, scope = _title(line), _kind(compact), subject
            elif current is None and title == "正文" and line.startswith(("要求", "主要测查")):
                kind = "requirement"
            append(line, page, title, kind, scope)
    flush()
    # 章节正文与逐页原文都只做软换行合并；不增删任何字符，仅去掉PDF折行。
    # 逐页原文保持原始换行，供人工对照PDF；章节正文是整理后的可读文本。
    for section in sections:
        section["text"] = join_soft_wraps(section["text"])
    return {
        "sha256": report.sha256,
        "pageCount": report.page_count,
        "pages": pages,
        "sections": sections,
        "warnings": sorted(warnings),
    }
