import base64
from io import BytesIO
from pathlib import Path

import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient
from pypdf import PdfReader

from app.core.errors import ServiceError
from app.main import create_app
from app.rendering.material_pdf import render_material_pdf

PATH = "/internal/material-pdf"
FONTS = [
    "/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
    "C:/Windows/Fonts/msyh.ttc",
]


@pytest.fixture
def font():
    value = next((p for p in FONTS if Path(p).is_file()), None)
    if value is None:
        pytest.skip("需要已安装的中文字体以验证嵌入与提取")
    return value


def test_chinese_a4_multipage_tables_code_and_embedded_font(font):
    markdown = "# 学习资料\n\n**核心概念** 与 `代码`\n\n"
    markdown += "| 概念 | 含义 |\n| --- | --- |\n"
    markdown += "| 考纲范围 | " + "这是较长的说明。" * 100 + " |\n"
    markdown += "\n1. 第一项\n   - 嵌套说明\n\n```python\nprint('中文')\n```\n\n"
    markdown += "\n\n".join("## 知识点\n\n" + "概念与适用条件。" * 100 for _ in range(8))
    reader = PdfReader(BytesIO(render_material_pdf("学习资料", markdown, font)))
    assert len(reader.pages) > 2
    text = "\n".join(page.extract_text() for page in reader.pages)
    for expected in ("核心概念", "考纲范围", "嵌套说明", "print", "概念与适用条件"):
        assert expected in text
    for page in reader.pages:
        assert float(page.mediabox.width) == pytest.approx(595.28, abs=0.1)
        assert float(page.mediabox.height) == pytest.approx(841.89, abs=0.1)
        assert "知序" in page.extract_text()
    fonts = reader.pages[0]["/Resources"]["/Font"].get_object().values()
    assert any("/FontFile2" in f.get_object().get("/FontDescriptor", {}) for f in fonts)


def test_export_treats_markup_and_remote_images_as_text(font, monkeypatch):
    import urllib.request

    monkeypatch.setattr(urllib.request, "urlopen", lambda *a, **kw: pytest.fail("禁止读取外部资源"))
    raw = render_material_pdf(
        "<b>安全标题</b>",
        "<script>alert(1)</script>\n\n![远程图片](https://example.com/a.png)\n\n"
        '[外部链接](https://example.com)\n\n<font color="red">原始HTML</font>',
        font,
    )
    reader = PdfReader(BytesIO(raw))
    text = reader.pages[0].extract_text()
    assert "安全标题" in text
    assert "远程图片" in text
    assert "原始HTML" in text
    assert not reader.pages[0].get("/Annots")


def test_missing_font_has_actionable_failure():
    with pytest.raises(ServiceError) as exc:
        render_material_pdf("标题", "正文", "/nonexistent/font.ttf")
    assert exc.value.code == 50342


def test_missing_bold_font_is_reported_without_breaking_regular_export(font):
    with pytest.raises(ServiceError) as exc:
        render_material_pdf("标题", "正文", font, "/nonexistent/bold.ttf")
    assert exc.value.code == 50342
    assert render_material_pdf("标题", "正文", font).startswith(b"%PDF-")


def test_pdf_route_requires_signature_and_validates_input(font):
    body = encode({"title": "考纲笔记", "markdown": "# 考纲笔记\n\n学习范围与要求。"})
    with TestClient(
        create_app(
            settings(
                material_pdf_font_path=font,
                material_pdf_bold_font_path=font,
            )
        )
    ) as client:
        assert client.post(PATH, content=body).status_code == 401
        response = client.post(PATH, content=body, headers=signed(body, path=PATH))
        assert response.status_code == 200
        pdf = base64.b64decode(response.json()["data"]["pdf_base64"])
        assert "学习范围" in PdfReader(BytesIO(pdf)).pages[0].extract_text()
        empty = encode({"title": "标题", "markdown": " "})
        assert client.post(PATH, content=empty, headers=signed(empty, path=PATH)).status_code == 422
        large = encode({"title": "标题", "markdown": "字" * 100001})
        assert client.post(PATH, content=large, headers=signed(large, path=PATH)).status_code == 422
