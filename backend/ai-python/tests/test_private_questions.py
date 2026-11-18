import base64
import io
import json
from types import SimpleNamespace

import pytest
from reportlab.pdfgen.canvas import Canvas

from app.agent.coach_capabilities import requests_private_import
from app.core.errors import ServiceError
from app.parser.pdf_runner import PdfRunnerError, run_pdf_worker
from app.private_questions.pipeline import PDF_WORKER, Candidate, check_candidate, extract_source


@pytest.fixture
def anyio_backend():
    return "asyncio"


def candidate(**changes):
    return Candidate.model_validate(
        {
            "type": "single_choice",
            "stem_md": "1+1=?",
            "options": [{"key": "A", "text_md": "2"}, {"key": "B", "text_md": "3"}],
            **changes,
        }
    ).model_dump()


def solution(**changes):
    return {
        "answer_keys": ["A"],
        "explanation_md": "两个一合计二",
        "memory_tip": "1+1=2",
        "uncertain": False,
        "needs_search": False,
        "search_query": "",
        "issues": [],
        **changes,
    }


class Provider:
    def __init__(self, *values):
        self.values = list(values)
        self.messages = []

    async def generate_json(self, messages):
        self.messages.append(messages)
        value = self.values.pop(0)
        if isinstance(value, Exception):
            raise value
        return json.dumps(value, ensure_ascii=False) if isinstance(value, dict) else value


@pytest.mark.anyio
async def test_missing_answer_is_solved_independently_without_claiming_verified():
    provider = Provider(solution(), solution())
    result = await check_candidate(provider, None, candidate())
    assert result["status"] == "ready"
    assert result["answer_source"] == "ai_suggested"
    assert result["web_verified"] is False
    assert "original_keys" not in json.loads(provider.messages[0][1]["content"])
    assert len(provider.messages) == 2


@pytest.mark.anyio
async def test_wrong_original_is_preserved_and_never_auto_adopted():
    provider = Provider(solution(), solution())
    result = await check_candidate(
        provider, None, candidate(original_keys=["B"], original_answer_quote="答案B")
    )
    assert result["candidate"]["original_keys"] == ["B"]
    assert result["answer_keys"] == ["A"]
    assert result["status"] == "needs_attention"
    assert any("不自动覆盖" in issue for issue in result["issues"])


@pytest.mark.anyio
@pytest.mark.parametrize("changes", [{"requires_image": True}, {"issues": ["缺少条件"]}])
async def test_incomplete_or_image_dependent_question_never_becomes_ready(changes):
    result = await check_candidate(Provider(solution(), solution()), None, candidate(**changes))
    assert result["status"] == "needs_attention"


@pytest.mark.anyio
async def test_no_search_does_not_fabricate_evidence():
    result = await check_candidate(
        Provider(solution(needs_search=True, search_query="公开知识点"), solution()),
        None,
        candidate(),
    )
    assert result["web_search_status"] == "unavailable"
    assert result["web_sources"] == []
    assert result["status"] == "needs_attention"


@pytest.mark.anyio
async def test_private_identity_query_is_not_sent_to_search():
    class Search:
        async def search(self, *args, **kwargs):
            pytest.fail("禁止发送身份信息查询")

    result = await check_candidate(
        Provider(solution(needs_search=True, search_query="用户123456789的信息"), solution()),
        Search(),
        candidate(),
    )
    assert result["web_sources"] == []


@pytest.mark.anyio
async def test_search_sources_are_recorded_without_claiming_official_verification():
    from datetime import UTC, datetime

    class Search:
        async def search(self, query, **options):
            assert query == "加法定义"
            return SimpleNamespace(
                results=[
                    SimpleNamespace(
                        title="数学", url="https://example.org/math", content="加法", snippet=""
                    )
                ],
                retrieved_at=datetime.now(UTC),
            )

    result = await check_candidate(
        Provider(solution(needs_search=True, search_query="加法定义"), solution()),
        Search(),
        candidate(),
    )
    assert result["web_search_status"] == "found"
    assert result["web_sources"][0]["url"] == "https://example.org/math"
    assert not result["web_verified"]


@pytest.mark.anyio
async def test_personal_selection_is_not_a_standard_answer_and_quote_must_exist():
    source = "我的答案B 1+1=? A.2 B.3"
    provider = Provider(
        {
            "questions": [candidate(original_keys=["B"], original_answer_quote="我的答案B")],
            "warnings": [],
        }
    )
    result = await extract_source(provider, None, kind="text", content=source)
    assert result["questions"][0]["original_keys"] == []
    assert result["questions"][0]["personal_keys"] == ["B"]
    result = await extract_source(
        Provider(
            {
                "questions": [
                    candidate(original_keys=["B"], original_answer_quote="不存在的答案B")
                ],
                "warnings": [],
            }
        ),
        None,
        kind="text",
        content=source,
    )
    assert not result["questions"][0]["original_keys"]
    assert result["questions"][0]["issues"]


@pytest.mark.anyio
async def test_long_text_is_chunked_and_overlap_deduplicated():
    value = {"questions": [candidate()], "warnings": []}
    provider = Provider(value, value, value)
    result = await extract_source(provider, None, kind="text", content="x" * 25000)
    assert len(provider.messages) == 3
    assert len(result["questions"]) == 1
    assert all(
        len(json.loads(messages[1]["content"])[0]["text"]) <= 14000
        for messages in provider.messages
    )


@pytest.mark.anyio
async def test_malformed_or_truncated_output_is_not_retried():
    provider = Provider('{"questions":[')
    with pytest.raises(ServiceError, match="未自动重试"):
        await extract_source(provider, None, kind="text", content="题目")
    assert len(provider.messages) == 1


@pytest.mark.anyio
async def test_image_requires_configured_ocr():
    with pytest.raises(ServiceError, match="图片识别服务未配置"):
        await extract_source(
            Provider(), None, kind="image", content=base64.b64encode(b"png").decode()
        )


@pytest.mark.anyio
async def test_image_ocr_then_question_extraction():
    class OCR:
        async def recognize(self, raw):
            assert raw == b"bytes"
            return SimpleNamespace(text="1+1=? A.2 B.3")

    result = await extract_source(
        Provider({"questions": [candidate()], "warnings": []}),
        OCR(),
        kind="image",
        content=base64.b64encode(b"bytes").decode(),
    )
    assert len(result["questions"]) == 1


def test_real_offline_pdf_worker_extracts_text_and_limits_pages():
    output = io.BytesIO()
    canvas = Canvas(output)
    canvas.drawString(50, 700, "1+1=? A.2 B.3")
    canvas.save()
    result = json.loads(run_pdf_worker(output.getvalue(), PDF_WORKER))
    assert "1+1" in result["pages"][0]["text"]
    output = io.BytesIO()
    canvas = Canvas(output)
    for _ in range(21):
        canvas.drawString(50, 700, "Question")
        canvas.showPage()
    canvas.save()
    with pytest.raises(PdfRunnerError):
        run_pdf_worker(output.getvalue(), PDF_WORKER)


def test_real_scanned_pdf_is_rendered_in_worker():
    from PIL import Image
    from reportlab.lib.utils import ImageReader

    output = io.BytesIO()
    canvas = Canvas(output)
    canvas.drawImage(ImageReader(Image.new("RGB", (100, 100), "white")), 50, 600)
    canvas.save()
    result = json.loads(run_pdf_worker(output.getvalue(), PDF_WORKER))
    assert base64.b64decode(result["pages"][0]["image"]).startswith(b"\x89PNG")


@pytest.mark.parametrize("text", ["帮我导入题目", "记下这道题", "保存这些错题", "帮我导入错题"])
def test_explicit_import(text):
    assert requests_private_import(text)


@pytest.mark.parametrize(
    "text", ["做个知识点对比表", "怎么导入题目", "不要导入题目", "请解释“记下这道题”是什么意思"]
)
def test_ordinary_chat_is_not_import(text):
    assert not requests_private_import(text)


def test_signed_private_source_endpoint_and_student_scope():
    from conftest import encode, settings, signed
    from fastapi.testclient import TestClient

    from app.main import create_app

    class ApiProvider(Provider):
        async def aclose(self):
            pass

    provider = ApiProvider({"questions": [candidate()], "warnings": []})
    path = "/internal/private-source"
    with TestClient(create_app(settings(), provider=provider)) as client:
        raw = encode({"scope": "student", "kind": "text", "content": "1+1 A.2 B.3"})
        assert client.post(path, content=raw).status_code == 401
        response = client.post(path, content=raw, headers=signed(raw, path=path))
        assert response.status_code == 200
        assert response.json()["data"]["questions"][0]["stem_md"] == "1+1=?"
        raw = encode({"scope": "admin", "kind": "text", "content": "题目"})
        assert client.post(path, content=raw, headers=signed(raw, path=path)).status_code == 422
    assert len(provider.messages) == 1


def test_private_import_workflow_uses_fixed_business_callback_without_explaining():
    from conftest import encode, settings, signed
    from fastapi.testclient import TestClient
    from test_coach_workflow import Business, context
    from test_coach_workflow import Provider as WorkflowProvider

    from app.main import create_app

    class PrivateBusiness(Business):
        async def step(self, grant, step, **kwargs):
            self.steps.append((grant, step, kwargs))
            return {**context(), "message": "帮我导入题目"}

    provider, business = WorkflowProvider("private_import"), PrivateBusiness()
    path = "/internal/coach-workflow"
    raw = encode(
        {
            "scope": "student",
            "workflow_id": (
                "11111111-1111-1111-1111-111111111111:22222222-2222-2222-2222-222222222222"
            ),
            "action": "chat",
            "grant": "grant",
            "context": {**context(), "message": "帮我导入题目"},
        }
    )
    with TestClient(create_app(settings(), provider=provider)) as client:
        client.app.state.coach_workflow_client = business
        response = client.post(path, content=raw, headers=signed(raw, path=path))
    assert response.status_code == 200
    assert [step for _, step, _ in business.steps] == ["context", "create_private_import"]
    assert provider.explanations == 0


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("single", "single_choice"),
        ("multiple", "multiple_choice"),
        ("单选题", "single_choice"),
        ("多选", "multiple_choice"),
        (" SINGLE ", "single_choice"),
    ],
)
def test_unambiguous_question_type_names_are_canonicalized(raw, expected):
    assert candidate(type=raw)["type"] == expected


@pytest.mark.parametrize("raw", ["判断题", "unknown", "", None, 1])
def test_unknown_question_type_is_not_guessed(raw):
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        candidate(type=raw)


@pytest.mark.anyio
async def test_type_alias_does_not_bypass_option_or_answer_validation():
    value = candidate()
    value["type"] = "single"
    value["original_keys"] = ["Z"]
    provider = Provider({"questions": [value], "warnings": []})
    with pytest.raises(ServiceError):
        await extract_source(provider, None, kind="text", content="题目 A.甲 B.乙")
    assert len(provider.messages) == 1


@pytest.mark.anyio
async def test_extraction_prompt_states_enum_and_accepts_legacy_single_label():
    value = candidate()
    value["type"] = "single"
    provider = Provider({"questions": [value], "warnings": []})
    result = await extract_source(provider, None, kind="text", content="1+1=? A.2 B.3")
    assert result["questions"][0]["type"] == "single_choice"
    prompt = provider.messages[0][0]["content"]
    assert '"enum":["single_choice","multiple_choice"]' in prompt
    assert "JSON Schema" in prompt


def test_private_validation_log_never_contains_values_or_extra_field_text(caplog):
    from app.private_questions.pipeline import Candidates, _json

    value = candidate()
    value["type"] = "PRIVATE_VALUE"
    value["PRIVATE_EXTRA_FIELD"] = "PRIVATE_INPUT"
    with pytest.raises(ServiceError):
        _json(json.dumps({"questions": [value], "warnings": []}), Candidates)
    assert "questions.0.type" in caplog.text
    assert "literal_error" in caplog.text
    assert "unknown_field" in caplog.text
    assert "PRIVATE_" not in caplog.text
