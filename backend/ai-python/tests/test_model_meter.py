import asyncio
import base64
import hashlib
import hmac
import json
import time

import httpx
import pytest
from conftest import SECRET, encode, settings, signed
from fastapi.testclient import TestClient
from test_stream_text import choice, event

from app.clients.model_meter import checked_grant, meter_call, meter_context
from app.core.errors import ServiceError
from app.llm.openai_compatible import OpenAICompatibleProvider
from app.main import create_app


def grant(path="/internal/coach-explain", **overrides):
    data = {
        "user": "12",
        "sid": "session",
        "path": path,
        "expires": int(time.time()) + 120,
        "trace": "meter-test",
        **overrides,
    }
    encoded = base64.urlsafe_b64encode(json.dumps(data).encode()).decode().rstrip("=")
    signature = hmac.new(
        SECRET.encode(), ("meter\n" + encoded).encode(), hashlib.sha256
    ).hexdigest()
    return encoded + "." + signature


def test_grant_cannot_be_forged_expired_or_used_on_another_path():
    value = grant()
    assert checked_grant(value, "/internal/coach-explain", settings()) == value
    for bad, path in [
        (value + "x", "/internal/coach-explain"),
        (grant(expires=1), "/internal/coach-explain"),
        (value, "/internal/material-generate"),
    ]:
        with pytest.raises(ServiceError):
            checked_grant(bad, path, settings())


def test_enabled_route_refuses_missing_grant_before_model():
    body = encode({"scope": "student", "message": "解释概念"})
    with TestClient(create_app(settings(ai_metering_enabled=True))) as client:
        response = client.post(
            "/internal/coach-route",
            content=body,
            headers=signed(body, path="/internal/coach-route"),
        )
    assert response.status_code == 403
    assert response.json()["code"] == 40340


def test_exact_usage_and_missing_usage_have_separate_settlements(monkeypatch):
    calls = []
    real_client = httpx.AsyncClient

    def callback(request):
        assert request.headers.get("Content-Type") == "application/json"
        raw = request.content
        body = json.loads(raw)
        canonical = "\n".join(
            (
                request.headers["X-Timestamp"],
                request.headers["X-Nonce"],
                "POST",
                request.url.path,
                hashlib.sha256(raw).hexdigest(),
            )
        )
        expected = hmac.new(SECRET.encode(), canonical.encode(), hashlib.sha256).hexdigest()
        assert hmac.compare_digest(request.headers["X-Signature"], expected)
        calls.append((request.url.path, body))
        return httpx.Response(200, json={"code": 0, "data": {}})

    monkeypatch.setattr(
        "app.clients.model_meter.httpx.AsyncClient",
        lambda **kw: real_client(**kw, transport=httpx.MockTransport(callback)),
    )

    async def run():
        context = meter_context.set((grant(), "coach-explain"))
        try:
            config = settings(ai_metering_enabled=True)
            async with meter_call(config, {"max_tokens": 100}, "model") as call:
                call.sent = True
                call.usage({"usage": {"prompt_tokens": 17, "completion_tokens": 9}})
            async with meter_call(config, {"max_tokens": 100}, "model") as call:
                call.sent = True
        finally:
            meter_context.reset(context)

    asyncio.run(run())
    assert len(calls) == 4
    assert calls[0][1]["reservation"] > 100
    assert calls[1][1]["inputTokens"] == 17
    assert calls[1][1]["outputTokens"] == 9
    assert calls[3][1]["inputTokens"] is None
    assert calls[0][1]["id"] != calls[2][1]["id"]


def test_quota_denial_prevents_supplier_call(monkeypatch):
    called = []
    real_client = httpx.AsyncClient
    config = settings(
        ai_metering_enabled=True,
        llm_base_url="https://llm.example/v1",
        llm_api_key="fake-key",
        llm_model="test-model",
    )
    provider = OpenAICompatibleProvider(
        config,
        transport=httpx.MockTransport(
            lambda request: called.append(request) or httpx.Response(200)
        ),
    )
    monkeypatch.setattr(
        "app.clients.model_meter.httpx.AsyncClient",
        lambda **kw: real_client(
            **kw,
            transport=httpx.MockTransport(
                lambda request: httpx.Response(402, json={"code": 40240})
            ),
        ),
    )

    async def run():
        context = meter_context.set((grant(), "coach-explain"))
        try:
            with pytest.raises(ServiceError) as failure:
                await provider.generate_json([{"role": "user", "content": "问题"}])
            assert failure.value.code == 40240
            assert failure.value.message == "Token 余额不足，请联系管理员充值后重试。"
        finally:
            meter_context.reset(context)
            await provider.aclose()

    asyncio.run(run())
    assert not called


def test_streaming_http_keeps_meter_context_and_settles_supplier_usage(monkeypatch):
    callbacks = []
    config = settings(
        ai_metering_enabled=True,
        coach_tools_enabled=False,
        llm_base_url="https://llm.example/v1",
        llm_api_key="fake-key",
        llm_model="test-model",
    )

    def supplier(request):
        assert json.loads(request.content)["stream_options"] == {"include_usage": True}
        body = event(choice('{"reply":"答复","suggestions":[]}'))
        body += event(choice(finish="stop"))
        body += event({"choices": [], "usage": {"prompt_tokens": 19, "completion_tokens": 11}})
        return httpx.Response(200, content=(body + "data: [DONE]\n\n").encode())

    provider = OpenAICompatibleProvider(config, transport=httpx.MockTransport(supplier))
    real_client = httpx.AsyncClient

    def callback(request):
        callbacks.append((request.url.path, json.loads(request.content)))
        return httpx.Response(200, json={"code": 0, "data": {}})

    def factory(**kwargs):
        kwargs.pop("transport", None)
        return real_client(**kwargs, transport=httpx.MockTransport(callback))

    monkeypatch.setattr("app.clients.model_meter.httpx.AsyncClient", factory)
    path = "/internal/coach-explain"
    body = encode({"scope": "student", "message": "解释概念"})
    headers = {**signed(body, path=path), "X-Usage-Grant": grant(path)}
    with TestClient(create_app(config, provider=provider)) as client:
        response = client.post(path, content=body, headers=headers)
    assert response.status_code == 200
    assert "event: done" in response.text
    assert len(callbacks) == 2
    assert callbacks[1][1]["inputTokens"] == 19
    assert callbacks[1][1]["outputTokens"] == 11


def test_ocr_reservation_uses_image_budget_instead_of_base64_text_and_preserves_body():
    from app.clients.model_meter import estimate_reservation

    body = {
        "messages": [
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "识别题目"},
                    {
                        "type": "image_url",
                        "image_url": {"url": "data:image/png;base64," + "A" * 800000},
                    },
                ],
            }
        ],
        "max_tokens": 4096,
    }
    before = json.dumps(body)
    config = settings(ocr_image_reservation_tokens=32768)
    reservation = estimate_reservation(config, body, "vision-model", "OCR")
    assert 32768 + 4096 < reservation < 40000
    assert json.dumps(body) == before
    assert estimate_reservation(config, body, "vision-model", "text") > 1600000
    assert estimate_reservation(config, body, None, "OCR") == 0
    larger = settings(ocr_image_reservation_tokens=65536)
    assert estimate_reservation(larger, body, "vision-model", "OCR") - reservation == 32768


def test_ocr_budget_still_settles_actual_supplier_usage(monkeypatch):
    calls = []
    real_client = httpx.AsyncClient

    def callback(request):
        calls.append((request.url.path, json.loads(request.content)))
        return httpx.Response(200, json={"code": 0, "data": {}})

    monkeypatch.setattr(
        "app.clients.model_meter.httpx.AsyncClient",
        lambda **kw: real_client(**kw, transport=httpx.MockTransport(callback)),
    )

    async def run():
        context = meter_context.set((grant(), "private-source"))
        try:
            async with meter_call(
                settings(ai_metering_enabled=True),
                {
                    "max_tokens": 4096,
                    "messages": [
                        {
                            "content": [
                                {
                                    "type": "image_url",
                                    "image_url": {"url": "data:image/png;base64," + "A" * 800000},
                                }
                            ]
                        }
                    ],
                },
                "vision-model",
                "OCR",
            ) as call:
                call.sent = True
                call.usage({"usage": {"prompt_tokens": 2360, "completion_tokens": 880}})
        finally:
            meter_context.reset(context)

    asyncio.run(run())
    assert calls[0][1]["reservation"] < 40000
    assert calls[1][1]["inputTokens"] == 2360
    assert calls[1][1]["outputTokens"] == 880


@pytest.mark.parametrize("step", ["reserve", "settle"])
def test_meter_failure_logs_only_status_and_numeric_code(step, caplog):
    from app.clients.model_meter import MeterCall

    async def run():
        call = MeterCall(settings(), "PRIVATE_GRANT", "private-model", "OCR", 1)
        async with httpx.AsyncClient(
            base_url="http://localhost:8080",
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    500, json={"code": 50000, "message": "PRIVATE_RESPONSE"}
                )
            ),
        ) as client:
            call.client = client
            with pytest.raises(ServiceError) as failure:
                await call.callback(step, {"model": "PRIVATE_MODEL"})
            assert failure.value.code == 50345

    asyncio.run(run())
    assert f"step={step} status=500 code=50000" in caplog.text
    assert "PRIVATE_" not in caplog.text


@pytest.mark.parametrize("payload", [[], None, "PRIVATE_RESPONSE"])
def test_meter_invalid_envelope_is_controlled_and_does_not_leak_content(payload, caplog):
    from app.clients.model_meter import MeterCall

    async def run():
        call = MeterCall(settings(), "PRIVATE_GRANT", "model", "OCR", 1)
        async with httpx.AsyncClient(
            base_url="http://localhost:8080",
            transport=httpx.MockTransport(lambda request: httpx.Response(200, json=payload)),
        ) as client:
            call.client = client
            with pytest.raises(ServiceError) as failure:
                await call.callback("reserve", {})
            assert failure.value.code == 50345

    asyncio.run(run())
    assert "reason=invalid_response" in caplog.text
    assert "PRIVATE_" not in caplog.text
