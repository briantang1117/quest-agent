import pytest
from pydantic import ValidationError

from app.parser.source_evidence import (
    EvidenceSegment,
    SourceEvidence,
    assemble_candidate,
    pdf_evidence,
)

# 合成作答结果文本；不将真实截图题目/个人作答复制到仓库。
TEXT = (
    "练习 8/15\n哪项是偶数？\nA.3\nB.5\nC.8\nD.9\n"
    "正确答案：C\n你的答案：D\n易错项：A\n解析：8能被2整除。"
)


def evidence(text=TEXT, **changes):
    fields = dict(
        evidence_id="image-1",
        source_id="source-1",
        sha256="a" * 64,
        kind="image",
        ordinal=1,
        text=text,
        image_ref="asset-1",
    )
    return SourceEvidence(**(fields | changes))


def segment(source, role, text):
    start = source.text.index(text)
    return EvidenceSegment(
        evidence_id=source.evidence_id, role=role, start=start, end=start + len(text)
    )


def test_unnumbered_screenshot_separates_reference_personal_and_ui():
    source = evidence()
    parts = [
        segment(source, "ui", "练习 8/15"),
        segment(source, "question", "哪项是偶数？\nA.3\nB.5\nC.8\nD.9"),
        segment(source, "reference_answer", "正确答案：C"),
        segment(source, "personal_answer", "你的答案：D"),
        segment(source, "ui", "易错项：A"),
        segment(source, "reference_explanation", "解析：8能被2整除。"),
    ]
    result = assemble_candidate([source], parts)
    assert result.question_no is None
    assert result.reference_keys == ("C",)
    assert result.reference_explanation == "解析：8能被2整除。"
    assert result.page_numbers == ()
    assert "你的答案" not in result.source_text
    assert "8/15" not in result.source_text
    assert "易错项" not in result.source_text
    assert "正确答案：C" in result.source_text
    assert result.status == "pending_review"
    assert result.evidence_refs == tuple(parts[1:3] + [parts[5]])


def test_explicit_candidate_can_cross_multiple_images_and_pdf_pages():
    first = evidence("材料与题干", evidence_id="pdf-1", kind="pdf_page", page_number=2)
    second = evidence("A.甲 B.乙\n参考答案：B", evidence_id="image-2", ordinal=2)
    result = assemble_candidate(
        [first, second],
        [
            segment(first, "question", "材料与题干"),
            segment(second, "question", "A.甲 B.乙"),
            segment(second, "reference_answer", "参考答案：B"),
        ],
    )
    assert result.page_numbers == (2,)
    assert result.reference_keys == ("B",)
    assert [ref.evidence_id for ref in result.evidence_refs] == ["pdf-1", "image-2", "image-2"]
    assert result.source_text == "材料与题干\nA.甲 B.乙\n参考答案：B"


@pytest.mark.parametrize(
    "texts, expected",
    [
        (["正确答案：A", "参考答案：B"], "conflicting_reference_answers"),
        (["正确答案：待定"], "unrecognized_reference_answer"),
        (["你的答案：D"], "unrecognized_reference_answer"),
    ],
)
def test_unreliable_reference_is_not_silently_treated_as_missing(texts, expected):
    source = evidence("题干\n" + "\n".join(texts))
    result = assemble_candidate(
        [source],
        [
            segment(source, "question", "题干"),
            *[segment(source, "reference_answer", text) for text in texts],
        ],
    )
    assert result.reference_keys == ()
    assert expected in result.warnings


def test_multiselect_reference_is_normalized_without_treating_order_as_conflict():
    source = evidence("题干\n正确答案：C、A\n参考答案：AC")
    result = assemble_candidate(
        [source],
        [
            segment(source, "question", "题干"),
            segment(source, "reference_answer", "正确答案：C、A"),
            segment(source, "reference_answer", "参考答案：AC"),
        ],
    )
    assert result.reference_keys == ("A", "C")
    assert "conflicting_reference_answers" not in result.warnings


def test_unknown_evidence_out_of_bounds_and_overlapping_segments_are_rejected():
    source = evidence()
    good = segment(source, "question", "哪项是偶数？")
    for bad in [
        good.model_copy(update={"evidence_id": "unknown"}),
        good.model_copy(update={"end": len(TEXT) + 1}),
    ]:
        with pytest.raises(ValueError):
            assemble_candidate([source], [bad])
    with pytest.raises(ValueError):
        assemble_candidate([source], [good, good.model_copy(update={"role": "personal_answer"})])
    with pytest.raises(ValueError):
        assemble_candidate([source, source], [good])


def test_no_question_boundary_means_no_guessed_candidate():
    source = evidence()
    with pytest.raises(ValueError):
        assemble_candidate([source], [])
    with pytest.raises(ValueError):
        assemble_candidate([source], [segment(source, "ui", "练习 8/15")])


@pytest.mark.parametrize(
    "changes",
    [
        {"sha256": "invalid"},
        {"kind": "unknown"},
        {"ordinal": 0},
        {"ordinal": True},
        {"kind": "pdf_page"},
        {"kind": "image", "page_number": 1},
    ],
)
def test_invalid_source_identity_is_rejected(changes):
    with pytest.raises(ValidationError):
        evidence(**changes)


def test_pdf_adapter_retains_original_text_layout_and_warnings():
    from test_pdf_inspection import pdf_bytes

    from app.parser.pdf_inspection import inspect_pdf

    report = inspect_pdf(pdf_bytes(text=True))
    units = pdf_evidence(report, source_id="pdf-source")
    assert len(units) == 1
    assert units[0].sha256 == report.sha256
    assert units[0].kind == "pdf_page"
    assert units[0].page_number == 1
    assert units[0].text == report.pages[0].layout_text
    assert units[0].plain_text == report.pages[0].text
    assert units[0].image_ref is None  # 未渲染，不能伪造原图引用
