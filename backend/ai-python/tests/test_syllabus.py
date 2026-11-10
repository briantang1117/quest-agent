import base64
import hashlib

import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient
from syllabus_fixtures import CAREER_PAGES, SHANGHAI_PAGES, syllabus_pdf

from app.main import create_app

PATH = "/internal/syllabus-inspect"


def send(client, raw, scope="admin"):
    body = encode({"scope": scope, "pdf_base64": base64.b64encode(raw).decode()})
    return client.post(PATH, content=body, headers=signed(body, path=PATH))


def test_hmac_admin_only_and_exact_camel_case_contract():
    raw = syllabus_pdf(*CAREER_PAGES)
    with TestClient(create_app(settings())) as client:
        assert client.post(PATH, json={}).status_code == 401
        assert send(client, raw, "student").status_code == 403
        body = encode({"scope": "admin", "pdf_base64": base64.b64encode(raw).decode()})
        assert (
            client.post(PATH, content=body + b" ", headers=signed(body, path=PATH)).status_code
            == 401
        )
        response = send(client, raw)
    assert response.status_code == 200
    result = response.json()
    assert set(result) == {"code", "message", "data", "traceId"}
    assert result["traceId"] == response.headers["X-Trace-Id"]
    data = result["data"]
    assert set(data) == {"sha256", "pageCount", "pages", "sections", "warnings"}
    assert data["sha256"] == hashlib.sha256(raw).hexdigest()
    assert data["pageCount"] == 3
    assert [p["page"] for p in data["pages"]] == [1, 2, 3]
    assert [p["printedPage"] for p in data["pages"]] == ["I", "II", "1"]
    assert "— I —" in data["pages"][0]["text"]
    assert all(set(p) == {"page", "printedPage", "text"} for p in data["pages"])
    assert all(
        set(s)
        == {
            "ordinal",
            "title",
            "path",
            "kind",
            "scope",
            "enabled",
            "pageStart",
            "pageEnd",
            "printedPages",
            "text",
        }
        and s["enabled"] is False
        for s in data["sections"]
    )
    assert [s["ordinal"] for s in data["sections"]] == list(range(1, len(data["sections"]) + 1))
    requirements = [s for s in data["sections"] if s["kind"] == "requirement"]
    cross = next(s for s in requirements if "跨页" in s["text"])
    assert cross["pageStart"] == 1 and cross["pageEnd"] == 2
    assert cross["printedPages"] == "I,II"
    assert cross["scope"] == "vocational_aptitude"
    assert "继续说明" in cross["text"]
    example = next(s for s in data["sections"] if s["kind"] == "example")
    assert "参考答案：A" in example["text"] and "解析：" in example["text"]
    assert any(s["kind"] == "overview" for s in data["sections"])
    assert any(s["kind"] == "other" for s in data["sections"])
    assert all(
        s["scope"] == "excluded"
        for s in data["sections"]
        if "社会科学材料" in s["text"] or "撰写分析材料" in s["text"]
    )
    assert "examYear" not in data


@pytest.mark.parametrize(
    ("texts", "expected"),
    [
        (SHANGHAI_PAGES, "administrative_aptitude"),
        (("职业能力倾向测验\n要求理解材料。",), "excluded"),
    ],
)
def test_scope_is_content_based_conservative_and_other_subjects_are_retained(texts, expected):
    with TestClient(create_app(settings())) as client:
        response = send(client, syllabus_pdf(*texts))
    assert response.status_code == 200
    sections = response.json()["data"]["sections"]
    requirement = next(s for s in sections if "理解" in s["text"])
    assert requirement["scope"] == expected
    if expected == "administrative_aptitude":
        assert next(s for s in sections if "文章写作" in s["text"])["scope"] == "excluded"


def test_pdf_soft_line_wraps_are_joined_without_losing_characters():
    """PDF每行约22字折行，软换行不应作为正文分段保留。"""
    from app.parser.syllabus_extraction import join_soft_wraps

    # 中文行尾接中文行首 -> 合并；句末标点后仍是下一行 -> 保留换行。
    assert join_soft_wraps("言语理解与\n表达") == "言语理解与表达"
    assert join_soft_wraps("等部分。\n（一）政治理论") == "等部分。\n（一）政治理论"
    # 英文单词断行要补空格，不能粘成一个词。
    assert join_soft_wraps("admini\nstration") == "admini stration"
    # 段落空行必须保留。
    assert join_soft_wraps("第一段\n\n第二段") == "第一段\n\n第二段"
    # 项目符号/编号行各自成行。
    assert join_soft_wraps("要求如下：\n1、第一条") == "要求如下：\n1、第一条"
    # 标题行后必须断开，否则标题会粘到正文里。
    assert (
        join_soft_wraps("一、行政职业能力测验\n《行政职业能力测验》试题分为政治理论")
        == "一、行政职业能力测验\n《行政职业能力测验》试题分为政治理论"
    )
    title = "上海市2027 年度考试录用公务员公共科目考试大纲"
    body = "上海市2027年度考试录用公务员公共科目笔试包括"
    assert join_soft_wraps(f"{title}\n{body}") == f"{title}\n{body}"


def test_long_paragraph_is_split_without_losing_text():
    text = "一、考试内容\n" + "长段落" * 5000
    with TestClient(create_app(settings())) as client:
        response = send(client, syllabus_pdf(text))
    assert response.status_code == 200
    sections = response.json()["data"]["sections"]
    assert all(0 < len(s["text"]) <= 12000 for s in sections)
    assert sum(s["text"].count("长") for s in sections) == 5000


@pytest.mark.parametrize("raw", [b"%PDF-1.7\nPRIVATE_SYLLABUS", syllabus_pdf(*([""] * 201))])
def test_bad_pdf_and_page_limit_are_redacted(raw, caplog):
    with TestClient(create_app(settings())) as client:
        response = send(client, raw)
    assert response.status_code == 422
    assert response.json()["data"] is None
    assert "PRIVATE_SYLLABUS" not in response.text + caplog.text


def test_byte_limit_and_invalid_base64():
    with TestClient(create_app(settings())) as client:
        assert send(client, b"x" * (20 * 1024 * 1024 + 1)).status_code == 413
        body = encode({"scope": "admin", "pdf_base64": "%%%PRIVATE%%%"})
        response = client.post(PATH, content=body, headers=signed(body, path=PATH))
    assert response.status_code == 422
    assert "PRIVATE" not in response.text


def test_fullwidth_page_numbers_and_wrapped_requirement_heading_are_preserved():
    raw = syllabus_pdf(
        "上海市公务员考试大纲\n一、行政职业能力测验\n"
        "（一）判断推理。主要测查报考人员对事物关系的\n－7－",
        "－8－\n\n理解、比较、组合和判断能力。\n例题1：合成题\n参考答案：A",
    )
    with TestClient(create_app(settings())) as client:
        response = send(client, raw)
    assert response.status_code == 200
    data = response.json()["data"]
    assert [p["printedPage"] for p in data["pages"]] == ["7", "8"]
    section = next(s for s in data["sections"] if "主要测查" in s["text"])
    assert section["kind"] == "requirement"
    assert section["title"] == "判断推理"
    assert "主要测查" in section["text"]
    assert section["pageStart"] == 1 and section["pageEnd"] == 2
    assert "理解、比较" in section["text"]


def test_section_spanning_page_number_only_page_keeps_full_citation_range():
    raw = syllabus_pdf("一、考试内容\n要求掌握材料。\n1", "2", "续接说明。\n3")
    with TestClient(create_app(settings())) as client:
        response = send(client, raw)
    assert response.status_code == 200
    section = response.json()["data"]["sections"][0]
    assert section["pageStart"] == 1 and section["pageEnd"] == 3
    assert section["printedPages"] == "1,2,3"


def test_scope_does_not_bleed_across_categories_or_professional_subjects():
    raw = syllabus_pdf(
        "上海公务员和事业单位考纲资料\n5.1 综合管理类（A类）\n"
        "5.1.1 《职业能力倾向测验（A类）》\n（一）考试内容\n要求掌握A类内容。\n"
        "例题1：题目\n答案：A\n（二）判断推理。主要测查推理能力\n"
        "5.2 社会科学专技类（B类）\n5.2.1 《职业能力倾向测验（B类）》\n"
        "（一）考试内容\n要求掌握B类内容。\n三、专业科目考试\n（一）法律\n要求掌握专业知识。"
    )
    with TestClient(create_app(settings())) as client:
        response = send(client, raw)
    assert response.status_code == 200
    sections = response.json()["data"]["sections"]
    assert (
        next(s for s in sections if "主要测查推理" in s["text"])["scope"] == "vocational_aptitude"
    )
    assert all(
        s["scope"] == "excluded"
        for s in sections
        if "B类内容" in s["text"] or "专业知识" in s["text"]
    )


def test_shanghai_mentioned_only_in_example_is_not_origin_evidence():
    raw = syllabus_pdf(
        "其他地区公务员考试大纲\n一、行政职业能力测验\n（一）考试内容\n要求分析材料。\n"
        "例题1：上海某公司招聘人员。\n答案：A"
    )
    with TestClient(create_app(settings())) as client:
        response = send(client, raw)
    assert response.status_code == 200
    assert all(s["scope"] == "excluded" for s in response.json()["data"]["sections"])


def test_scan_keeps_pages_but_does_not_invent_sections():
    with TestClient(create_app(settings())) as client:
        response = send(client, syllabus_pdf(""))
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["pages"] == [{"page": 1, "printedPage": None, "text": ""}]
    assert data["sections"] == []
    assert "page:1:no_text_layer" in data["warnings"]
