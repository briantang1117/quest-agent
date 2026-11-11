import json

import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient

from app.core.errors import ServiceError
from app.main import create_app

PATH = "/internal/coach-explain"
QUESTION = {
    "stem_md": "合成题",
    "options": [{"key": "A", "text_md": "甲"}, {"key": "B", "text_md": "乙"}],
}


class StreamProvider:
    def __init__(self, chunks=None, failure=None):
        self.chunks = (
            chunks if chunks is not None else ('{"reply":"宏观', "经济", '政策","suggestions":[]}')
        )
        self.failure = failure
        self.messages = []

    async def stream_text(self, messages, *, json_mode=False, long_form=False):
        assert json_mode is True
        self.messages.append(messages)
        for chunk in self.chunks:
            yield chunk
        if self.failure is not None:
            raise self.failure

    async def generate_json(self, messages, *, background=False):
        raise AssertionError("正文流不应额外调用 JSON 生成")

    async def aclose(self):
        pass


def send(client, payload):
    body = encode(payload)
    return client.post(PATH, content=body, headers=signed(body, path=PATH))


def events(response):
    out = []
    for block in response.text.split("\n\n"):
        if not block.strip():
            continue
        name, data = "message", ""
        for line in block.splitlines():
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                data = line[5:].strip()
        out.append((name, json.loads(data)))
    return out


def run_stream(provider, **extra):
    with TestClient(create_app(settings(), provider=provider)) as client:
        return send(client, {"scope": "student", "message": "讲解", **extra})


def text_of(response):
    return "".join(data["text"] for name, data in events(response) if name == "delta")


def test_explain_streams_only_reply_then_suggestions_and_done():
    provider = StreamProvider()
    response = run_stream(provider)
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    got = events(response)
    assert [name for name, _ in got] == ["delta", "delta", "delta", "suggestions", "done"]
    assert text_of(response) == "宏观经济政策"
    assert got[-2:] == [("suggestions", {"items": []}), ("done", {})]
    assert len(provider.messages) == 1


@pytest.mark.parametrize("chunk_size", [1, 2, 3, 7, 1000])
def test_field_order_nested_reply_escapes_and_unicode_across_chunks(chunk_size):
    reply = '中文"引号"\\路径\n换行\t制表😀尾'
    raw = json.dumps(
        {
            "metadata": {"reply": "SECRET_NESTED", "items": [{"reply": "PRIVATE"}]},
            "suggestions": [
                {"action": "chat", "label": "再讲讲", "text": "讲讲概念"},
                {"action": "publish", "label": "禁止"},
                1,
                None,
            ],
            "reply": reply,
        },
        ensure_ascii=True,
    )
    provider = StreamProvider(
        chunks=[raw[i : i + chunk_size] for i in range(0, len(raw), chunk_size)]
    )
    response = run_stream(provider)
    assert text_of(response) == reply
    assert "SECRET_NESTED" not in response.text
    assert "PRIVATE" not in response.text
    assert events(response)[-2] == (
        "suggestions",
        {"items": [{"action": "chat", "label": "再讲讲", "text": "讲讲概念"}]},
    )
    assert events(response)[-1] == ("done", {})


def test_deltas_are_generated_before_model_finishes():
    import asyncio
    from types import SimpleNamespace

    from app.api.coach import CoachRequest, coach_explain

    async def run():
        provider = StreamProvider(chunks=('{"reply":"先到', '后到","suggestions":[]}'))
        request = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(provider=provider)))
        response = await coach_explain(CoachRequest(scope="student", message="你好"), request)
        iterator = response.body_iterator
        first = await anext(iterator)
        assert '"text": "先到"' in first
        assert '"reply"' not in first
        await iterator.aclose()

    asyncio.run(run())


@pytest.mark.parametrize(
    "raw",
    [
        '{"reply":"半截',
        '{"reply":"正文","suggestions":[]} trailing',
        '{"reply":"正文","reply":"另一个","suggestions":[]}',
        '{"reply":"正文","suggestions":"bad"}',
        '{"reply":123,"suggestions":[]}',
        '{"nested":{"reply":"SECRET"},"suggestions":[]}',
        '{"reply":"","suggestions":[]}',
        '{"reply":"   ","suggestions":[]}',
        '{"reply":"\\ud83d","suggestions":[]}',
        '{"reply":"\\ude00","suggestions":[]}',
        '{"reply":"坏\\q","suggestions":[]}',
        json.dumps({"reply": "字" * 6001, "suggestions": []}),
        json.dumps({"ignored": "x" * 65536, "reply": "正文"}),
    ],
)
def test_invalid_json_body_ends_in_safe_error_without_success(raw):
    response = run_stream(StreamProvider(chunks=(raw,)))
    names = [name for name, _ in events(response)]
    assert names[-1] == "error"
    assert "done" not in names
    assert "suggestions" not in names
    assert "SECRET" not in response.text
    assert events(response)[-1][1] == {"code": 50203}


def test_explain_rejects_non_student_scope():
    provider = StreamProvider()
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client, {"scope": "admin", "message": "讲解"})
    assert response.status_code == 403
    assert not provider.messages


def test_explain_requires_internal_hmac():
    provider = StreamProvider()
    with TestClient(create_app(settings(), provider=provider)) as client:
        assert client.post(PATH, json={"scope": "student", "message": "讲解"}).status_code == 401
    assert not provider.messages


def test_stream_failure_becomes_error_event_and_stops():
    provider = StreamProvider(
        chunks=('{"reply":"半截',), failure=ServiceError(502, 50201, "SECRET_UPSTREAM")
    )
    response = run_stream(provider)
    assert [name for name, _ in events(response)] == ["delta", "error"]
    assert "SECRET_UPSTREAM" not in response.text
    assert len(provider.messages) == 1


def test_unanswered_prompt_forbids_solving_and_result_is_java_authority():
    provider = StreamProvider()
    run_stream(provider, question=QUESTION)
    prompt = provider.messages[0][0]["content"]
    assert "未作答" in prompt
    assert "不能透露或推断" in prompt
    assert "Java" in prompt and "权威" in prompt
    assert "不可信" in prompt
    assert "JSON" in prompt


def test_submitted_result_reaches_explanation_context_unchanged():
    result = {
        "correct": False,
        "selected_keys": ["A"],
        "answer_keys": ["B"],
        "explanation_md": "权威解析",
    }
    provider = StreamProvider()
    run_stream(provider, question=QUESTION, result=result)
    assert json.loads(provider.messages[0][1]["content"])["result"] == result


def test_closing_sse_body_closes_the_upstream_stream():
    import asyncio
    from types import SimpleNamespace

    from app.api.coach import CoachRequest, coach_explain

    async def run():
        closed = []

        class ClosingProvider(StreamProvider):
            async def stream_text(self, messages, *, json_mode=False, long_form=False):
                try:
                    yield '{"reply":"正文'
                    yield '后续","suggestions":[]}'
                finally:
                    closed.append(True)

        provider = ClosingProvider()
        request = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(provider=provider)))
        response = await coach_explain(CoachRequest(scope="student", message="你好"), request)
        await anext(response.body_iterator)
        await response.body_iterator.aclose()
        assert closed == [True]

    asyncio.run(run())


def test_missing_model_is_explicitly_unavailable():
    with TestClient(create_app(settings())) as client:
        response = send(client, {"scope": "student", "message": "你好"})
    assert response.status_code == 503


def test_output_failure_logs_safe_shape_and_reason_without_model_text(caplog):
    provider = StreamProvider(chunks=["PRIVATE_MODEL_TEXT should never appear in logs"])
    with caplog.at_level("WARNING"):
        response = run_stream(provider)
    assert events(response)[-1][0] == "error"
    assert "shape=text" in caplog.text
    assert "reason=json_structure_invalid" in caplog.text
    assert "PRIVATE_MODEL_TEXT" not in caplog.text
