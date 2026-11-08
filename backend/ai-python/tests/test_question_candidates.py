from types import SimpleNamespace

import pytest


def page(number, text, images=0):
    return SimpleNamespace(page_number=number, layout_text=text, image_count=images)


def split(pages):
    from app.parser.question_candidates import split_candidates

    return split_candidates(pages)


def test_reference_answer_is_not_replaced_by_personal_response():
    candidates = split([page(1, "1.题干\nA.甲 B.乙 C.丙 D.丁\n正确答案： D\n你的答案： B")])
    assert len(candidates) == 1
    item = candidates[0]
    assert item.reference_keys == ("D",)
    assert "你的答案" not in item.source_text
    assert "正确答案" in item.source_text
    assert item.page_numbers == (1,)
    assert item.status == "pending_review"


def test_cross_page_question_keeps_source_pages_and_ignores_repeated_header():
    items = split(
        [
            page(1, "试卷标题\n1.题干跨页\nA.甲 B.乙"),
            page(
                2,
                "试卷标题\nC.丙 D.丁\n正确答案： C\n你的答案： A\n"
                "2.下一题\nA.甲 B.乙\n正确答案： B",
            ),
        ]
    )
    assert [item.question_no for item in items] == [1, 2]
    assert items[0].page_numbers == (1, 2)
    assert "试卷标题" not in items[0].source_text
    assert items[0].reference_keys == ("C",)


def test_number_reset_duplicate_or_missing_numbers_require_review():
    items = split([page(1, "1.题一\n正确答案： A\n1.题二\n正确答案： B\n3.题三")])
    assert "duplicate_question_number" in items[0].warnings
    assert "duplicate_question_number" in items[1].warnings
    assert "missing_reference_answer" in items[2].warnings
    assert all(item.status == "pending_review" for item in items)


def test_conflicting_reference_answers_are_not_silently_selected():
    item = split([page(1, "1.题干\n正确答案： A\n正确答案： B")])[0]
    assert item.reference_keys == ()
    assert "conflicting_reference_answers" in item.warnings


def test_images_are_not_claimed_to_be_associated_with_a_specific_question():
    item = split([page(1, "1.题干\n正确答案： A", images=1)])[0]
    assert "page_contains_images_unassigned" in item.warnings


def test_answer_table_and_personal_answers_never_become_new_questions():
    items = split([page(1, "1.题目\nA.甲 B.乙\n你的答案： B\n参考答案\n1. A\n2. B")])
    assert len(items) == 1
    assert items[0].reference_keys == ()
    assert "standalone_answer_table_requires_matching" in items[0].warnings


def test_multiple_choice_source_answers_are_preserved_in_order():
    item = split([page(1, "1、题干\n正确答案： A、C\n你的答案： D")])[0]
    assert item.reference_keys == ("A", "C")


def test_inline_personal_response_is_removed_without_losing_reference():
    item = split([page(1, "1.题干\nA.甲 B.乙\n正确答案：A 你的答案：B PRIVATE")])[0]
    assert "PRIVATE" not in item.source_text
    assert "你的答案" not in item.source_text
    assert item.reference_keys == ("A",)


@pytest.mark.parametrize("answer", ["A或B", "A（存疑）", "A至D"])
def test_ambiguous_answer_is_not_accepted_as_a_letter_prefix(answer):
    item = split([page(1, f"1.题干\n正确答案：{answer}")])[0]
    assert item.reference_keys == ()
    assert "unrecognized_reference_answer" in item.warnings


def test_original_explanation_survives_candidate_reconstruction():
    item = split([page(1, "1.题干\nA.甲 B.乙\n正确答案：A\n原文解析：必须保留。\n第二行解析。")])[0]
    assert item.reference_explanation == "原文解析：必须保留。\n第二行解析。"
