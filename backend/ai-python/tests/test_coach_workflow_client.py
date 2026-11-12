import asyncio
import hashlib
import hmac
import json

import httpx
import pytest
from conftest import SECRET, TOKEN, settings
from pydantic import ValidationError

from app.clients.coach_workflow import PATH, CoachWorkflowClient
from app.core.errors import ServiceError


def test_business_callback_signs_exact_body_and_fixed_route():
    requests = []

    def handler(request):
        requests.append(request)
        body = request.content
        canonical = "\n".join(
            (
                request.headers["X-Timestamp"],
                request.headers["X-Nonce"],
                "POST",
                PATH,
                hashlib.sha256(body).hexdigest(),
            )
        )
        expected = hmac.new(SECRET.encode(), canonical.encode(), hashlib.sha256).hexdigest()
        assert request.headers["X-Service-Token"] == TOKEN
        assert hmac.compare_digest(expected, request.headers["X-Signature"])
        assert str(request.url) == "http://127.0.0.1:8080" + PATH
        assert json.loads(body) == {"grant": "bound-grant", "step": "submit_answer"}
        return httpx.Response(200, json={"code": 0, "data": {"context": {"scope": "student"}}})

    async def run():
        client = CoachWorkflowClient(settings(), transport=httpx.MockTransport(handler))
        try:
            assert await client.step("bound-grant", "submit_answer") == {"scope": "student"}
        finally:
            await client.aclose()

    asyncio.run(run())
    assert len(requests) == 1


@pytest.mark.parametrize(
    "failure",
    ["redirect", "network", "oversize", "bad_json", "bad_envelope", "array", "null", "non_utf8"],
)
def test_callback_failure_does_not_follow_redirect_or_retry(failure):
    calls = []

    def handler(request):
        calls.append(request)
        if failure == "redirect":
            return httpx.Response(307, headers={"Location": "https://attacker.example/steal"})
        if failure == "network":
            raise httpx.ConnectError("private failure", request=request)
        if failure == "oversize":
            return httpx.Response(200, content=b"x" * (512 * 1024 + 1))
        if failure == "array":
            return httpx.Response(200, json=[])
        if failure == "null":
            return httpx.Response(200, content=b"null")
        if failure == "non_utf8":
            return httpx.Response(200, content=b"\xff")
        if failure == "bad_json":
            return httpx.Response(200, content=b"not-json")
        return httpx.Response(200, json={"code": 1, "data": {}})

    async def run():
        client = CoachWorkflowClient(settings(), transport=httpx.MockTransport(handler))
        try:
            with pytest.raises(ServiceError) as exc:
                await client.step("grant", "context")
            assert str(exc.value) == (
                "业务工具响应过长" if failure == "oversize" else "业务工具调用失败"
            )
        finally:
            await client.aclose()

    asyncio.run(run())
    assert len(calls) == 1


@pytest.mark.parametrize(
    "url",
    [
        "http://user:pass@localhost",
        "file:///tmp/a",
        "http://localhost:8080?redirect=x",
        "http://localhost/#x",
    ],
)
def test_callback_configuration_rejects_credentials_and_non_http_addresses(url):
    with pytest.raises(ValidationError):
        settings(coach_api_url=url)


def test_callback_logs_material_gate_without_private_response(caplog):
    def handler(request):
        return httpx.Response(
            503, json={"code": 50341, "message": "private-response", "data": None}
        )

    async def run():
        client = CoachWorkflowClient(settings(), transport=httpx.MockTransport(handler))
        try:
            with pytest.raises(ServiceError):
                await client.step("private-grant", "create_material")
        finally:
            await client.aclose()

    asyncio.run(run())
    assert "step=create_material status=503 code=50341" in caplog.text
    assert "private-response" not in caplog.text
    assert "private-grant" not in caplog.text
