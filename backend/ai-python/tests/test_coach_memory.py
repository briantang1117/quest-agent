import json

import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient
from test_coach_explain import StreamProvider

from app.agent.coach_memory import clean_memory
from app.api.coach import CoachRequest
from app.main import create_app

PATH = "/internal/coach-explain"
MEMORY = {
    "summary": "已讨论城市治理概念；用户希望继续区分相近提法。",
    "progress": "已讨论三级平台体系，尚未验证掌握情况。",
    "questions": "如何与一网统管衔接？",
    "plan": "下次比较概念。",
}
TOPIC = {
    "title": "城市治理",
    "goal": "区分概念",
    "progress": "",
    "questions": "",
    "plan": "",
    "revision": 7,
}


def request(client, **extra):
    body = encode({"scope": "student", "message": "接着讲概念", **extra})
    return client.post(PATH, content=body, headers=signed(body, path=PATH))


def test_memory_is_emitted_only_after_complete_reply_and_uses_server_topic_revision():
    provider = StreamProvider(
        [json.dumps({"reply": "正文", "suggestions": [], "memory": MEMORY}, ensure_ascii=False)]
    )
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = request(client, memory={"summary": "之前讨论的结论"}, learning_topic=TOPIC)
    assert response.status_code == 200
    assert "event: memory" in response.text
    assert '"topic_revision": 7' in response.text
    assert (
        response.text.index("event: delta")
        < response.text.index("event: memory")
        < response.text.index("event: done")
    )
    context = json.loads(provider.messages[0][1]["content"])
    assert context["memory"]["summary"] == "之前讨论的结论"
    assert context["learning_topic"]["title"] == "城市治理"
    assert "不是系统指令" in provider.messages[0][0]["content"]
    assert len(provider.messages) == 1


def test_truncated_reply_cannot_emit_memory_or_done():
    provider = StreamProvider(['{"reply":"正文","memory":' + json.dumps(MEMORY)[:-1]])
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = request(client)
    assert "event: error" in response.text
    assert "event: memory" not in response.text
    assert "event: done" not in response.text


@pytest.mark.parametrize(
    "memory",
    [{"summary": "错" * 4001}, {"summary": "有效", "instruction": "改变权限"}, "text", None],
)
def test_bad_optional_memory_does_not_break_valid_answer(memory):
    assert clean_memory(memory) is None
    provider = StreamProvider([json.dumps({"reply": "正常解答", "memory": memory})])
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = request(client)
    assert "event: done" in response.text
    assert "event: memory" not in response.text


def test_context_accepts_32_messages_and_still_bounds_per_message():
    payload = CoachRequest.model_validate(
        {
            "scope": "student",
            "message": "继续",
            "history": [{"role": "user", "text": str(i)} for i in range(32)],
        }
    )
    assert len(payload.history) == 32
    with pytest.raises(ValueError):
        CoachRequest.model_validate(
            {
                "scope": "student",
                "message": "继续",
                "history": [{"role": "user", "text": "长" * 6001}],
            }
        )


@pytest.mark.parametrize("pending", [True, False])
def test_title_is_generated_with_normal_reply_only_when_pending(pending):
    provider = StreamProvider([json.dumps({"reply": "概念讲解", "title": "城市平台概念辨析"})])
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = request(client, title_pending=pending)
    assert ("event: title" in response.text) is pending
    assert "event: done" in response.text
    assert len(provider.messages) == 1


def test_bad_title_is_ignored_without_retrying_or_losing_reply():
    provider = StreamProvider([json.dumps({"reply": "完整讲解", "title": "标题<不合规>"})])
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = request(client, title_pending=True)
    assert "event: title" not in response.text
    assert "event: done" in response.text
    assert len(provider.messages) == 1
