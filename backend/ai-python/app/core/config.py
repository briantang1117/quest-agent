from typing import Literal
from urllib.parse import urlsplit

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    app_env: Literal["local", "production"] = "local"
    internal_service_token: SecretStr | None = None
    internal_hmac_secret: SecretStr | None = None
    text_llm_base_url: str | None = Field(default=None, exclude=True, repr=False)
    text_llm_api_key: SecretStr | None = Field(default=None, exclude=True, repr=False)
    text_llm_model: str | None = Field(default=None, exclude=True, repr=False)
    llm_base_url: str | None = None
    llm_api_key: SecretStr | None = None
    llm_model: str | None = None
    # 方舟调试能力各自配置；地址和模型别名原样使用，不借用文本模型密钥。
    ocr_api_base_url: str | None = None
    ocr_api_key: SecretStr | None = None
    ocr_model: str | None = None
    embedding_base_url: str | None = None
    embedding_api_key: SecretStr | None = None
    embedding_model: str | None = None
    search_api_base_url: str | None = None
    search_api_key: SecretStr | None = None
    fetch_api_base_url: str | None = None
    fetch_api_key: SecretStr | None = None
    # 必须根据接口实际输出核实，不能从模型名猜测或默认沿用数据库1024维。
    embedding_dimensions: int | None = Field(default=None, gt=0, strict=True)
    # 对话单轮用较短超时；后台批处理（考纲整份整理/向量化）另用长超时，不强行套用对话预算。
    llm_timeout_seconds: float = Field(default=30, gt=0, le=300)
    background_llm_timeout_seconds: float = Field(default=180, gt=0, le=600)
    llm_temperature: float = Field(default=0, ge=0, le=2)
    llm_max_tokens: int = Field(default=4096, ge=1, le=32768)
    # 讲解类回答（多主体对比、长解析）容易撞4096上限被截断；单独给更高预算。
    explanation_max_tokens: int = Field(default=8192, ge=1, le=32768)

    @model_validator(mode="before")
    @classmethod
    def select_text_provider_group(cls, values):
        # 任一TEXT配置出现即整组选用，绝不从旧组或其他能力借用密钥。
        if isinstance(values, dict) and any(
            key in values for key in ("text_llm_base_url", "text_llm_api_key", "text_llm_model")
        ):
            values = dict(values)
            for suffix in ("base_url", "api_key", "model"):
                values[f"llm_{suffix}"] = values.get(f"text_llm_{suffix}")
        return values

    @field_validator(
        "llm_base_url",
        "llm_api_key",
        "llm_model",
        "ocr_api_base_url",
        "ocr_api_key",
        "ocr_model",
        "embedding_base_url",
        "embedding_api_key",
        "embedding_model",
        "search_api_base_url",
        "search_api_key",
        "fetch_api_base_url",
        "fetch_api_key",
        mode="before",
    )
    @classmethod
    def empty_optional_llm_is_unconfigured(cls, value):
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @field_validator("embedding_dimensions", mode="before")
    @classmethod
    def decimal_dimensions(cls, value):
        if isinstance(value, str) and value.isascii() and value.isdecimal():
            return int(value)
        return value

    @property
    def background_timeout(self) -> float:
        return max(self.llm_timeout_seconds, self.background_llm_timeout_seconds)

    @field_validator("internal_service_token", "internal_hmac_secret", "llm_api_key")
    @classmethod
    def reject_blank_secrets(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None and not value.get_secret_value().strip():
            raise ValueError("configured secrets must not be blank")
        return value

    @field_validator(
        "llm_base_url",
        "ocr_api_base_url",
        "embedding_base_url",
        "search_api_base_url",
        "fetch_api_base_url",
    )
    @classmethod
    def validate_base_url(cls, value: str | None) -> str | None:
        if value is None:
            return None
        parts = urlsplit(value)
        if (
            parts.scheme not in {"http", "https"}
            or not parts.hostname
            or parts.username
            or parts.password
            or parts.query
            or parts.fragment
        ):
            raise ValueError(
                "Provider base URL must be HTTP(S) without credentials, query or fragment"
            )
        return value.rstrip("/")

    @field_validator("llm_model")
    @classmethod
    def reject_blank_model(cls, value: str | None) -> str | None:
        if value is not None and not value.strip():
            raise ValueError("LLM_MODEL must not be blank")
        return value

    @model_validator(mode="after")
    def secure_production(self) -> "Settings":
        if self.app_env == "production":
            for secret in (self.internal_service_token, self.internal_hmac_secret):
                if secret is None or len(secret.get_secret_value()) < 32:
                    raise ValueError(
                        "production requires both internal secrets, at least 32 chars each"
                    )
        return self

    @property
    def internal_security_configured(self) -> bool:
        return self.internal_service_token is not None and self.internal_hmac_secret is not None

    @property
    def ocr_configured(self) -> bool:
        return bool(self.ocr_api_base_url and self.ocr_api_key and self.ocr_model)

    @property
    def embedding_configured(self) -> bool:
        return bool(self.embedding_base_url and self.embedding_api_key and self.embedding_model)

    @property
    def search_configured(self) -> bool:
        return bool(self.search_api_base_url and self.search_api_key)

    @property
    def fetch_configured(self) -> bool:
        return bool(self.fetch_api_base_url and self.fetch_api_key)

    @property
    def llm_configured(self) -> bool:
        return bool(self.llm_base_url and self.llm_api_key and self.llm_model)
