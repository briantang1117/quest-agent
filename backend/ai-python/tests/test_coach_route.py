import json

import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient

from app.core.errors import ServiceError
from app.main import create_app

PATH = "/internal/coach-route"


class RouteProvider:
    def __init__(self, result=None, failure=None):
        self.result = result or {
            "action": "chat",
            "keys": [],
            "reply": "我们聊聊。",
            "suggestions": [],
        }
        self.failure = failure
        self.messages = []

    async def generate_json(self, messages, *, background=False):
        self.messages.append(messages)
        if self.failure:
            raise self.failure
        return self.result if isinstance(self.result, str) else json.dumps(self.result)

    async def aclose(self):
        pass


def send(client, **extra):
    body = encode({"scope": "student", "message": "你好", **extra})
    return client.post(PATH, content=body, headers=signed(body, path=PATH))


@pytest.mark.parametrize(
    "action,keys", [("next", []), ("answer", ["B"]), ("chat", []), ("clarify", [])]
)
def test_route_returns_validated_envelope_with_one_short_intent_call(action, keys):
    provider = RouteProvider({"action": action, "keys": keys, "reply": "收到。"})
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client)
    assert response.status_code == 200
    assert response.json()["data"] == {
        "action": action,
        "keys": keys,
        "reply": "收到。",
        "suggestions": [],
    }
    assert response.json()["traceId"]
    assert len(provider.messages) == 1
    prompt = provider.messages[0][0]["content"]
    assert "不生成完整讲解" in prompt
    assert "不能透露或推断" in prompt
    assert "不可信" in prompt
    assert "Java" in prompt


@pytest.mark.parametrize(
    "result",
    [
        "bad SECRET_OUTPUT",
        {"action": "publish", "keys": [], "reply": "SECRET_OUTPUT"},
        {"action": "answer", "keys": [], "reply": "收到"},
        {"action": "chat", "keys": ["B"], "reply": "收到"},
        {"action": "chat", "keys": [], "reply": "长" * 201},
        {"action": "chat", "keys": [], "reply": "  "},
    ],
)
def test_invalid_route_output_is_safe_and_never_retried(result):
    provider = RouteProvider(result)
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client)
    assert response.status_code == 502
    assert "SECRET_OUTPUT" not in response.text
    assert len(provider.messages) == 1


def test_route_validation_logs_only_safe_error_location(caplog):
    provider = RouteProvider({"action": "chat", "keys": [], "reply": "SECRET_OUTPUT" * 30})
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client)
    assert response.status_code == 502
    assert "stage=coach_route" in caplog.text
    assert "reply:string_too_long" in caplog.text
    assert "SECRET_OUTPUT" not in caplog.text


def test_route_timeout_is_not_retried():
    provider = RouteProvider(failure=ServiceError(504, 50401, "超时"))
    with TestClient(create_app(settings(), provider=provider)) as client:
        assert send(client).status_code == 504
    assert len(provider.messages) == 1


def test_route_auth_scope_and_missing_provider():
    provider = RouteProvider()
    with TestClient(create_app(settings(), provider=provider)) as client:
        assert client.post(PATH, json={}).status_code == 401
        assert send(client, scope="admin").status_code == 403
    assert not provider.messages
    with TestClient(create_app(settings())) as client:
        assert send(client).status_code == 503
