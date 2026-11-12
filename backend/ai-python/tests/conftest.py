import hashlib
import hmac
import json
import time
import uuid

import pytest

TOKEN = "test-token-for-internal-service-123456"
SECRET = "test-hmac-secret-at-least-32-characters"


@pytest.fixture(autouse=True)
def isolated_environment(monkeypatch):
    monkeypatch.setenv("COACH_CHECKPOINT_PATH", ":memory:")
    for name in (
        "APP_ENV",
        "COACH_TOOLS_ENABLED",
        "COACH_MAX_TOOL_CALLS",
        "COACH_MAX_TOOL_ROUNDS",
        "INTERNAL_SERVICE_TOKEN",
        "INTERNAL_HMAC_SECRET",
        "LLM_BASE_URL",
        "LLM_API_KEY",
        "LLM_MODEL",
        "TEXT_LLM_BASE_URL",
        "TEXT_LLM_API_KEY",
        "TEXT_LLM_MODEL",
        "OCR_API_BASE_URL",
        "OCR_API_KEY",
        "OCR_MODEL",
        "EMBEDDING_BASE_URL",
        "EMBEDDING_API_KEY",
        "EMBEDDING_MODEL",
        "EMBEDDING_DIMENSIONS",
        "SEARCH_API_BASE_URL",
        "SEARCH_API_KEY",
        "FETCH_API_BASE_URL",
        "FETCH_API_KEY",
    ):
        monkeypatch.delenv(name, raising=False)


def settings(**overrides):
    from app.core.config import Settings

    return Settings(
        _env_file=None,
        internal_service_token=TOKEN,
        internal_hmac_secret=SECRET,
        **overrides,
    )


def encode(payload):
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()


def signed(body=b"", method="POST", path="/internal/extract", timestamp=None, nonce=None):
    stamp = str(int(time.time()) if timestamp is None else timestamp)
    nonce = nonce or uuid.uuid4().hex
    message = "\n".join((stamp, nonce, method.upper(), path, hashlib.sha256(body).hexdigest()))
    return {
        "X-Service-Token": TOKEN,
        "X-Timestamp": stamp,
        "X-Nonce": nonce,
        "X-Signature": hmac.new(SECRET.encode(), message.encode(), hashlib.sha256).hexdigest(),
        "Content-Type": "application/json",
    }


@pytest.fixture
def question():
    return {
        "schema_version": "1.0",
        "type": "single_choice",
        "stem_md": "以下哪项正确？",
        "options": [{"key": "A", "text_md": "甲"}, {"key": "B", "text_md": "乙"}],
        "answer": {"keys": ["A"], "confidence": 0.9},
        "explanation_md": "甲正确。",
        "knowledge_points": ["GK/FL/MF/0003"],
        "difficulty": 3,
    }
