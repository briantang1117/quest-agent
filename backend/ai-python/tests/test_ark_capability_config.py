import pytest
from pydantic import ValidationError

from app.core.config import Settings


def test_ark_capabilities_use_exact_model_aliases_and_separate_keys(monkeypatch):
    for key, value in {
        "OCR_API_BASE_URL": "https://ark.cn-beijing.volces.com/api/plan/v3",
        "OCR_API_KEY": "ocr-test-key",
        "OCR_MODEL": "doubao-seed-2.1-pro",
        "EMBEDDING_BASE_URL": "https://ark.cn-beijing.volces.com/api/plan/v3",
        "EMBEDDING_API_KEY": "embedding-test-key",
        "EMBEDDING_MODEL": "doubao-embedding-vision",
    }.items():
        monkeypatch.setenv(key, value)
    config = Settings()
    assert config.ocr_configured
    assert config.embedding_configured
    assert config.ocr_model == "doubao-seed-2.1-pro"
    assert config.embedding_model == "doubao-embedding-vision"
    assert config.ocr_api_key.get_secret_value() == "ocr-test-key"
    assert config.embedding_api_key.get_secret_value() == "embedding-test-key"
    assert not config.llm_configured
    assert config.embedding_dimensions is None
    assert "ocr-test-key" not in repr(config)
    assert "embedding-test-key" not in repr(config)


def test_missing_capability_key_never_borrows_text_or_other_key():
    config = Settings(
        llm_base_url="https://text.example/v1",
        llm_api_key="text-test-key",
        llm_model="text-model",
        ocr_api_base_url="https://ark.cn-beijing.volces.com/api/plan/v3",
        ocr_model="doubao-seed-2.1-pro",
        embedding_api_key="embedding-test-key",
    )
    assert config.llm_configured
    assert not config.ocr_configured
    assert not config.embedding_configured
    assert config.ocr_api_key is None


def test_empty_keys_allow_boot_without_claiming_capabilities_configured():
    config = Settings(ocr_api_key="", embedding_api_key=" ")
    assert config.ocr_api_key is None
    assert config.embedding_api_key is None
    assert not config.ocr_configured
    assert not config.embedding_configured


@pytest.mark.parametrize("field", ["ocr_api_base_url", "embedding_base_url"])
def test_capability_urls_reject_embedded_credentials(field):
    with pytest.raises(ValidationError):
        Settings(**{field: "https://username:password@example.com/v3"})


def test_embedding_dimensions_reads_decimal_environment_without_accepting_bool(monkeypatch):
    monkeypatch.setenv("EMBEDDING_DIMENSIONS", "2048")
    assert Settings().embedding_dimensions == 2048
    for value in (True, "true", "2.5", "", "-1"):
        with pytest.raises(ValidationError):
            Settings(embedding_dimensions=value)


def test_embedding_dimensions_must_be_positive_if_explicit():
    with pytest.raises(ValidationError):
        Settings(embedding_dimensions=0)
