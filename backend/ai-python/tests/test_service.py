import time

import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient
from pydantic import ValidationError


def make_app(config=None):
    from app.main import create_app

    return create_app(config or settings())


def test_empty_optional_llm_env_starts_production_but_extract_is_unavailable(monkeypatch):
    from conftest import SECRET, TOKEN

    from app.main import create_app

    for name, value in {
        "APP_ENV": "production",
        "INTERNAL_SERVICE_TOKEN": TOKEN,
        "INTERNAL_HMAC_SECRET": SECRET,
        "LLM_BASE_URL": "",
        "LLM_API_KEY": "",
        "LLM_MODEL": "",
    }.items():
        monkeypatch.setenv(name, value)
    with TestClient(create_app()) as client:
        assert client.get("/health/live").status_code == 200
        body = encode({"scope": "admin", "text": "题目"})
        response = client.post("/internal/extract", content=body, headers=signed(body))
        assert response.status_code == 503
        assert response.json()["code"] == 50302


def test_liveness_is_public_without_secrets_or_model():
    from app.core.config import Settings

    with TestClient(make_app(Settings(_env_file=None))) as client:
        response = client.get("/health/live")
        assert response.status_code == 200
        assert response.json() == {"status": "alive"}


@pytest.mark.parametrize("missing", ["token", "hmac", "both"])
def test_production_requires_security_secrets(missing):
    kwargs = {"app_env": "production"}
    if missing == "token":
        kwargs["internal_hmac_secret"] = "x" * 32
    elif missing == "hmac":
        kwargs["internal_service_token"] = "x" * 32
    from app.core.config import Settings

    with pytest.raises(ValidationError):
        Settings(_env_file=None, **kwargs)


@pytest.mark.parametrize("environment", ["staging", "", "Production"])
def test_environment_is_explicit(environment):
    from app.core.config import Settings

    with pytest.raises(ValidationError):
        Settings(_env_file=None, app_env=environment)


def test_local_missing_secrets_fails_closed():
    from app.core.config import Settings

    with TestClient(make_app(Settings(_env_file=None))) as client:
        response = client.post("/internal/extract", json={"scope": "admin", "text": "test"})
        assert response.status_code == 503
        assert response.json()["code"] == 50301
        assert response.json()["traceId"]


@pytest.mark.parametrize("header", ["X-Service-Token", "X-Timestamp", "X-Nonce", "X-Signature"])
def test_missing_auth_header_is_rejected(header):
    body = encode({"scope": "admin", "text": "题目"})
    headers = signed(body)
    del headers[header]
    with TestClient(make_app()) as client:
        assert client.post("/internal/extract", content=body, headers=headers).status_code == 401


@pytest.mark.parametrize(
    "header,value",
    [
        ("X-Service-Token", "wrong"),
        ("X-Signature", "0" * 64),
        ("X-Signature", "FF" * 32),
        ("X-Timestamp", "not-time"),
        ("X-Nonce", "bad nonce"),
    ],
)
def test_invalid_auth_is_rejected(header, value):
    body = encode({"scope": "admin", "text": "题目"})
    headers = signed(body)
    headers[header] = value
    with TestClient(make_app()) as client:
        assert client.post("/internal/extract", content=body, headers=headers).status_code == 401


@pytest.mark.parametrize("offset", [-301, 301])
def test_timestamp_outside_five_minute_window(offset):
    body = encode({"scope": "admin", "text": "题目"})
    with TestClient(make_app()) as client:
        response = client.post(
            "/internal/extract",
            content=body,
            headers=signed(body, timestamp=int(time.time()) + offset),
        )
        assert response.status_code == 401


@pytest.mark.parametrize("component", ["body", "method", "path"])
def test_signature_covers_exact_body_method_and_path(component):
    body = encode({"scope": "admin", "text": "题目"})
    kwargs = {"method": "GET"} if component == "method" else {}
    if component == "path":
        kwargs["path"] = "/internal/other"
    headers = signed(body, **kwargs)
    if component == "body":
        body += b" "
    with TestClient(make_app()) as client:
        assert client.post("/internal/extract", content=body, headers=headers).status_code == 401


def test_nonce_cannot_be_replayed_even_with_different_body():
    body = encode({"scope": "admin", "text": "题目"})
    with TestClient(make_app()) as client:
        headers = signed(body, nonce="one-use-only")
        assert client.post("/internal/extract", content=body, headers=headers).status_code == 503
        assert client.post("/internal/extract", content=body, headers=headers).status_code == 401
        other = encode({"scope": "admin", "text": "另一个题目"})
        assert (
            client.post(
                "/internal/extract",
                content=other,
                headers=signed(other, nonce="one-use-only"),
            ).status_code
            == 401
        )


def test_all_internal_paths_require_auth():
    with TestClient(make_app()) as client:
        assert client.get("/internal/not-implemented").status_code == 401
        assert client.post("/internal/extract?scope=admin").status_code == 401


def test_unsigned_query_parameters_are_rejected_after_auth():
    body = encode({"scope": "admin", "text": "题目"})
    with TestClient(make_app()) as client:
        response = client.post(
            "/internal/extract?scope=student",
            content=body,
            headers=signed(body),
        )
        assert response.status_code == 400


def test_bad_signature_does_not_reserve_nonce():
    body = encode({"scope": "admin", "text": "题目"})
    headers = signed(body, nonce="not-reserved-until-authenticated")
    bad = {**headers, "X-Signature": "0" * 64}
    with TestClient(make_app()) as client:
        assert client.post("/internal/extract", content=body, headers=bad).status_code == 401
        assert client.post("/internal/extract", content=body, headers=headers).status_code == 503


def test_future_timestamp_nonce_cannot_expire_while_request_is_still_valid(monkeypatch):
    import app.core.security as security

    now = 1_800_000_000
    monkeypatch.setattr(security.time, "time", lambda: now)
    body = encode({"scope": "admin", "text": "题目"})
    headers = signed(body, timestamp=now + 299)
    with TestClient(make_app()) as client:
        assert client.post("/internal/extract", content=body, headers=headers).status_code == 503
        now += 400
        assert client.post("/internal/extract", content=body, headers=headers).status_code == 401


@pytest.mark.parametrize("scope,status", [("root", 422), ("ADMIN", 422), ("student", 403)])
def test_scope_cannot_grant_admin_extraction(scope, status):
    body = encode({"scope": scope, "text": "题目"})
    with TestClient(make_app()) as client:
        assert (
            client.post("/internal/extract", content=body, headers=signed(body)).status_code
            == status
        )


@pytest.mark.parametrize(
    "payload",
    [
        {"text": "题目"},
        {"scope": "admin", "text": " "},
        {"scope": "admin", "text": "x" * 50001},
        {"scope": "admin", "text": "题目", "status": "published"},
    ],
)
def test_extract_request_validation(payload):
    body = encode(payload)
    with TestClient(make_app()) as client:
        response = client.post("/internal/extract", content=body, headers=signed(body))
        assert response.status_code == 422
        assert response.json()["data"] is None
        assert response.json()["traceId"]


def test_unconfigured_model_returns_explicit_unavailable():
    body = encode({"scope": "admin", "text": "题目"})
    with TestClient(make_app()) as client:
        response = client.post("/internal/extract", content=body, headers=signed(body))
        assert response.status_code == 503
        result = response.json()
        assert result["code"] == 50302
        assert "LLM" in result["message"]
        assert result["data"] is None
        assert result["traceId"] == response.headers["X-Trace-Id"]
