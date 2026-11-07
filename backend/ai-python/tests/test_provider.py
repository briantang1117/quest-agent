import json

import httpx
import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient


def upstream(content):
    return httpx.Response(
        200,
        json={
            "id": "test-completion",
            "object": "chat.completion",
            "model": "test-model",
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": "stop",
                }
            ],
            "usage": {"prompt_tokens": 15, "completion_tokens": 30, "total_tokens": 45},
        },
    )


def client_for(handler):
    from app.llm.openai_compatible import OpenAICompatibleProvider
    from app.main import create_app

    config = settings(
        llm_base_url="https://llm.example/v1",
        llm_api_key="secret-provider-key",
        llm_model="test-model",
    )
    provider = OpenAICompatibleProvider(config, transport=httpx.MockTransport(handler))
    return TestClient(create_app(config, provider=provider))


def extract(client):
    body = encode({"scope": "admin", "text": "整理以下题目：甲还是乙？"})
    return client.post("/internal/extract", content=body, headers=signed(body))


def test_real_adapter_sends_compatible_request_and_forces_drafts(question):
    question["status"] = "published"
    question["answer"]["source"] = "manual"
    question["quality"] = {"review_status": "approved", "ai_confidence": 0.99}

    def handler(request):
        assert request.url == "https://llm.example/v1/chat/completions"
        assert request.headers["authorization"] == "Bearer secret-provider-key"
        data = json.loads(request.content)
        assert data["model"] == "test-model"
        assert data["messages"][0]["role"] == "system"
        assert data["messages"][1]["role"] == "user"
        assert "甲还是乙" in data["messages"][1]["content"]
        assert data["response_format"] == {"type": "json_object"}
        return upstream(json.dumps({"questions": [question]}))

    with client_for(handler) as client:
        response = extract(client)
    assert response.status_code == 200
    payload = response.json()
    assert payload["code"] == 0
    assert payload["traceId"]
    result = payload["data"]["questions"][0]
    assert result["status"] == "draft"
    assert result["answer"]["source"] == "ai_generated"
    assert result["quality"]["review_status"] == "pending"
    assert result["stem_md"] == question["stem_md"]


@pytest.mark.parametrize("status", [400, 401, 429, 500, 503])
def test_upstream_http_errors_are_sanitized(status):
    with client_for(
        lambda _: httpx.Response(status, text="secret-provider-key private text")
    ) as client:
        response = extract(client)
    assert response.status_code == 502
    assert response.json()["code"] == 50201
    assert response.json()["data"] is None
    assert "secret-provider-key" not in response.text
    assert "private text" not in response.text


@pytest.mark.parametrize(
    "error,status,code",
    [
        (httpx.ReadTimeout, 504, 50401),
        (httpx.ConnectError, 502, 50201),
    ],
)
def test_upstream_transport_errors(error, status, code):
    def handler(request):
        raise error("private connection details", request=request)

    with client_for(handler) as client:
        response = extract(client)
    assert response.status_code == status
    assert response.json()["code"] == code
    assert "private connection" not in response.text


@pytest.mark.parametrize(
    "content",
    [
        "not json",
        '{"questions":[]}',
        '{"questions":[{}]}',
        '{"questions":null}',
        "[]",
    ],
)
def test_invalid_ai_output_retries_once_then_returns_no_fake_results(content):
    calls = []

    def handler(request):
        calls.append(json.loads(request.content))
        return upstream(content)

    with client_for(handler) as client:
        response = extract(client)
    assert response.status_code == 502
    assert response.json()["code"] == 50202
    assert response.json()["data"] is None
    assert len(calls) == 2
    assert calls[1]["messages"][-1]["role"] == "user"
    assert "validation" in calls[1]["messages"][-1]["content"].lower()


def test_schema_correction_can_succeed(question):
    calls = 0

    def handler(_):
        nonlocal calls
        calls += 1
        return upstream("{}" if calls == 1 else json.dumps({"questions": [question]}))

    with client_for(handler) as client:
        response = extract(client)
    assert response.status_code == 200
    assert len(response.json()["data"]["questions"]) == 1
    assert calls == 2


@pytest.mark.parametrize(
    "response",
    [
        httpx.Response(200, text="not-json"),
        httpx.Response(200, json={"choices": []}),
        httpx.Response(200, json={"choices": [{"message": {"content": None}}]}),
    ],
)
def test_malformed_upstream_envelope_is_bad_gateway(response):
    with client_for(lambda _: response) as client:
        result = extract(client)
    assert result.status_code == 502
    assert result.json()["data"] is None
