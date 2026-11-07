from typing import Literal
from urllib.parse import urlsplit

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=None, extra="ignore")

    app_env: Literal["local", "production"] = "local"
    internal_service_token: SecretStr | None = None
    internal_hmac_secret: SecretStr | None = None
    llm_base_url: str | None = None
    llm_api_key: SecretStr | None = None
    llm_model: str | None = None
    llm_timeout_seconds: float = Field(default=30, gt=0, le=300)
    llm_temperature: float = Field(default=0, ge=0, le=2)
    llm_max_tokens: int = Field(default=4096, ge=1, le=32768)

    @field_validator("llm_base_url", "llm_api_key", "llm_model", mode="before")
    @classmethod
    def empty_optional_llm_is_unconfigured(cls, value):
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @field_validator("internal_service_token", "internal_hmac_secret", "llm_api_key")
    @classmethod
    def reject_blank_secrets(cls, value: SecretStr | None) -> SecretStr | None:
        if value is not None and not value.get_secret_value().strip():
            raise ValueError("configured secrets must not be blank")
        return value

    @field_validator("llm_base_url")
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
                "LLM_BASE_URL must be an HTTP(S) base URL without credentials or query"
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
    def llm_configured(self) -> bool:
        return bool(self.llm_base_url and self.llm_api_key and self.llm_model)
