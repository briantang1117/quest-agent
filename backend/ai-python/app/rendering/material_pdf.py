"""本地 Markdown → A4 PDF；只排版文本，不执行 HTML、不读取远程资源。"""

import hashlib
from io import BytesIO
from pathlib import Path
from threading import BoundedSemaphore, Lock
from xml.sax.saxutils import escape

from markdown_it import MarkdownIt
from reportlab.lib import colors
from reportlab.lib.enums import TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFError, TTFont
from reportlab.platypus import (
    HRFlowable,
    LongTable,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    TableStyle,
)

from app.core.errors import ServiceError

_slots = BoundedSemaphore(2)
_font_lock = Lock()


def _font(path: str, bold_path: str = "") -> tuple[str, str]:
    candidates = (
        [path]
        if path
        else [
            "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "C:/Windows/Fonts/msyh.ttc",
        ]
    )
    selected = next((p for p in candidates if Path(p).is_file()), None)
    if selected is None:
        raise ServiceError(503, 50342, "PDF中文字体未配置，请设置 MATERIAL_PDF_FONT_PATH")
    if bold_path and not Path(bold_path).is_file():
        raise ServiceError(503, 50342, "PDF粗体字体不存在，请检查 MATERIAL_PDF_BOLD_FONT_PATH")
    with _font_lock:
        name = (
            "MaterialCJK-" + hashlib.sha256((selected + "\n" + bold_path).encode()).hexdigest()[:12]
        )
        bold = name + "-Bold" if bold_path else name
        if any(value not in pdfmetrics.getRegisteredFontNames() for value in (name, bold)):
            try:
                pdfmetrics.registerFont(TTFont(name, selected))
                if bold_path:
                    pdfmetrics.registerFont(TTFont(bold, bold_path))
            except (OSError, TTFError) as exc:
                raise ServiceError(503, 50342, "PDF字体不可用，请配置含中文的TrueType字体") from exc
            pdfmetrics.registerFontFamily(
                name, normal=name, bold=bold, italic=name, boldItalic=bold
            )
    return name, bold


def _inline(tokens) -> str:
    parts = []
    for token in tokens or []:
        kind = token.type
        if kind in {"text", "code_inline", "html_inline"}:
            text = escape(token.content)
            parts.append(
                f'<font backColor="#edf1f5">{text}</font>' if kind == "code_inline" else text
            )
        elif kind == "strong_open":
            parts.append("<b>")
        elif kind == "strong_close":
            parts.append("</b>")
        elif kind == "em_open":
            parts.append("<i>")
        elif kind == "em_close":
            parts.append("</i>")
        elif kind in {"softbreak", "hardbreak"}:
            parts.append("<br/>")
        elif kind == "image":
            parts.append(escape(f"[图片：{token.content or '未嵌入'}]"))
        # 链接只保留可见文字；导出不请求URL、不附加文件或脚本。
    return "".join(parts)


def render_material_pdf(
    title: str, markdown: str, font_path: str = "", bold_font_path: str = ""
) -> bytes:
    if not _slots.acquire(blocking=False):
        raise ServiceError(503, 50343, "PDF导出繁忙，请稍后重试")
    try:
        return _render(title, markdown, font_path, bold_font_path)
    finally:
        _slots.release()


def _render(title, markdown, font_path, bold_font_path):
    font, bold_font = _font(font_path, bold_font_path)
    base = ParagraphStyle(
        "正文",
        fontName=font,
        fontSize=10.5,
        leading=17,
        spaceAfter=7,
        wordWrap="CJK",
        splitLongWords=True,
        alignment=TA_LEFT,
        textColor=colors.HexColor("#253246"),
    )
    tokens = MarkdownIt("commonmark", {"html": False}).enable("table").parse(markdown)
    story = []
    first_heading = (
        tokens[1].content if len(tokens) > 1 and tokens[0].type == "heading_open" else ""
    )
    if first_heading.strip() != title.strip():
        story.append(
            Paragraph(
                escape(title),
                ParagraphStyle(
                    "标题",
                    parent=base,
                    fontName=bold_font,
                    fontSize=20,
                    leading=29,
                    spaceAfter=18,
                    keepWithNext=True,
                ),
            )
        )
    lists = []
    quote_depth = 0
    index = 0
    while index < len(tokens):
        token = tokens[index]
        kind = token.type
        if kind in {"bullet_list_open", "ordered_list_open"}:
            lists.append(
                {
                    "ordered": kind == "ordered_list_open",
                    "number": int(token.attrGet("start") or 1),
                    "pending": False,
                }
            )
        elif kind in {"bullet_list_close", "ordered_list_close"}:
            lists.pop()
        elif kind == "list_item_open":
            lists[-1]["pending"] = True
        elif kind == "blockquote_open":
            quote_depth += 1
        elif kind == "blockquote_close":
            quote_depth -= 1
        elif kind in {"paragraph_open", "heading_open"}:
            inline = tokens[index + 1]
            text = _inline(inline.children)
            indent = 13 * len(lists) + 12 * quote_depth
            style = ParagraphStyle("段落", parent=base, leftIndent=indent)
            if kind == "heading_open":
                style.fontName = bold_font
                level = int(token.tag[1])
                style.fontSize = {1: 19, 2: 15, 3: 12.5}.get(level, 11)
                style.leading = style.fontSize * 1.5
                style.spaceBefore = 12
                style.spaceAfter = 9
                style.keepWithNext = True
            if lists and lists[-1]["pending"]:
                item = lists[-1]
                marker = f"{item['number']}." if item["ordered"] else "•"
                text = escape(marker) + " " + text
                item["number"] += 1
                item["pending"] = False
            story.append(Paragraph(text or " ", style))
            index += 2
        elif kind in {"fence", "code_block"}:
            style = ParagraphStyle(
                "代码",
                parent=base,
                fontSize=9,
                leading=14,
                backColor=colors.HexColor("#f2f5f8"),
                borderPadding=7,
            )
            for line in token.content.splitlines():
                story.append(Paragraph(escape(line).replace(" ", "&#160;") or " ", style))
            story.append(Spacer(1, 7))
        elif kind == "hr":
            story.append(HRFlowable(width="100%", color=colors.HexColor("#d6dce5"), spaceAfter=10))
        elif kind == "table_open":
            rows, row = [], []
            index += 1
            cell_style = ParagraphStyle("单元格", parent=base, fontSize=9, leading=14, spaceAfter=0)
            while tokens[index].type != "table_close":
                current = tokens[index]
                if current.type == "tr_open":
                    row = []
                elif current.type == "inline":
                    row.append(Paragraph(_inline(current.children) or " ", cell_style))
                elif current.type == "tr_close":
                    rows.append(row)
                index += 1
            columns = max(map(len, rows), default=0)
            if columns > 12:
                raise ServiceError(422, 42242, "表格列数过多，请改用Markdown下载")
            if columns:
                table = LongTable(
                    rows,
                    colWidths=[(A4[0] - 40 * mm) / columns] * columns,
                    repeatRows=1,
                    splitInRow=1,
                    hAlign="LEFT",
                )
                table.setStyle(
                    TableStyle(
                        [
                            ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#eaf0f7")),
                            ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#cbd5e1")),
                            ("VALIGN", (0, 0), (-1, -1), "TOP"),
                            ("LEFTPADDING", (0, 0), (-1, -1), 7),
                            ("RIGHTPADDING", (0, 0), (-1, -1), 7),
                            ("TOPPADDING", (0, 0), (-1, -1), 6),
                            ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
                        ]
                    )
                )
                story.extend([table, Spacer(1, 10)])
        index += 1

    def footer(canvas, doc):
        canvas.saveState()
        canvas.setFont(font, 8)
        canvas.setFillColor(colors.HexColor("#64748b"))
        canvas.drawString(20 * mm, 12 * mm, "知序 · 学习资料")
        canvas.drawRightString(A4[0] - 20 * mm, 12 * mm, str(doc.page))
        canvas.restoreState()

    output = BytesIO()
    doc = SimpleDocTemplate(
        output,
        pagesize=A4,
        rightMargin=20 * mm,
        leftMargin=20 * mm,
        topMargin=19 * mm,
        bottomMargin=21 * mm,
        title=title,
        author="知序",
    )
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    result = output.getvalue()
    if len(result) > 8 * 1024 * 1024:
        raise ServiceError(413, 41342, "PDF文件超过大小限制，请改用Markdown下载")
    return result
