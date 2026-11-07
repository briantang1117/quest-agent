import copy

import pytest
from pydantic import ValidationError


def validate(payload):
    from app.api.schemas import QuestionDraft

    return QuestionDraft.model_validate(payload)


def test_valid_question_defaults_to_unreviewed_ai_draft(question):
    result = validate(question)
    assert result.status == "draft"
    assert result.answer.source == "ai_generated"
    assert result.quality.review_status == "pending"


@pytest.mark.parametrize(
    "field,value",
    [
        ("stem_md", ""),
        ("stem_md", "  "),
        ("stem_md", "x" * 5001),
        ("type", "essay"),
        ("difficulty", 0),
        ("difficulty", 6),
        ("difficulty", 2.5),
        ("difficulty", True),
        ("difficulty", "3"),
        ("knowledge_points", []),
        ("knowledge_points", [""]),
        ("knowledge_points", ["   "]),
        ("knowledge_points", ["不是编码"]),
        ("knowledge_points", ["GK//MF"]),
        ("knowledge_points", ["GK/FL", "GK/FL"]),
        ("options", []),
        ("status", "published"),
    ],
)
def test_question_rejects_invalid_fields(question, field, value):
    question[field] = value
    with pytest.raises(ValidationError):
        validate(question)


@pytest.mark.parametrize("keys", [["A"], ["A", "A"], ["A", "C"], ["B", "A"], list("ABCDEFGHI")])
def test_options_must_be_two_to_eight_unique_contiguous_a_to_h(question, keys):
    question["options"] = [{"key": key, "text_md": key} for key in keys]
    with pytest.raises(ValidationError):
        validate(question)


@pytest.mark.parametrize(
    "kind,keys",
    [
        ("single_choice", []),
        ("single_choice", ["A", "B"]),
        ("single_choice", ["C"]),
        ("multiple_choice", ["A"]),
        ("multiple_choice", ["A", "C"]),
        ("multiple_choice", ["A", "A"]),
    ],
)
def test_answer_cardinality_uniqueness_and_option_subset(question, kind, keys):
    question["type"] = kind
    question["answer"]["keys"] = keys
    with pytest.raises(ValidationError):
        validate(question)


def test_multiple_choice_and_boundary_values_are_valid(question):
    question.update(type="multiple_choice", difficulty=5, stem_md="x" * 5000)
    question["options"] = [{"key": key, "text_md": key} for key in "ABCDEFGH"]
    question["answer"]["keys"] = ["A", "H"]
    assert validate(question).answer.keys == ["A", "H"]
    question["difficulty"] = 1
    assert validate(question).difficulty == 1


def test_review_cannot_be_preapproved(question):
    question["quality"] = {"review_status": "approved"}
    with pytest.raises(ValidationError):
        validate(copy.deepcopy(question))
