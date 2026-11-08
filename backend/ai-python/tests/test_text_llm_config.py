from app.core.config import Settings


def test_text_model_environment_is_used_by_existing_provider(monkeypatch):
    monkeypatch.setenv("TEXT_LLM_BASE_URL", "https://apistore.thinklight.com.cn/v1")
    monkeypatch.setenv("TEXT_LLM_API_KEY", "text-only-test-key")
    monkeypatch.setenv("TEXT_LLM_MODEL", "deepseek-v4.1-flash")
    config = Settings()
    assert config.llm_configured
    assert config.llm_model == "deepseek-v4.1-flash"
    assert config.llm_api_key.get_secret_value() == "text-only-test-key"


def test_partial_text_group_never_borrows_legacy_key(monkeypatch):
    monkeypatch.setenv("TEXT_LLM_MODEL", "deepseek-v4.1-flash")
    config = Settings(llm_base_url="https://legacy.example/v1", llm_api_key="legacy-test-key")
    assert not config.llm_configured
    assert config.llm_api_key is None


def test_empty_text_key_disables_provider_even_with_complete_legacy_group(monkeypatch):
    monkeypatch.setenv("TEXT_LLM_API_KEY", "")
    config = Settings(
        llm_base_url="https://legacy.example/v1", llm_api_key="legacy-key", llm_model="legacy"
    )
    assert not config.llm_configured
