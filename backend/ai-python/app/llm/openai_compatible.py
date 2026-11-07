import httpx

from app.core.config import Settings
from app.core.errors import ServiceError


class OpenAICompatibleProvider:
    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None):
        if not settings.llm_configured:
            raise ValueError("OpenAI-compatible provider requires complete LLM configuration")
        assert settings.llm_api_key is not None
        self._settings = settings
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.llm_timeout_seconds),
            transport=transport,
            follow_redirects=False,
            trust_env=False,
            headers={"Authorization": f"Bearer {settings.llm_api_key.get_secret_value()}"},
        )

    async def generate_json(self, messages: list[dict[str, str]]) -> str:
        try:
            response = await self._client.post(
                f"{self._settings.llm_base_url}/chat/completions",
                json={
                    "model": self._settings.llm_model,
                    "messages": messages,
                    "response_format": {"type": "json_object"},
                    "temperature": self._settings.llm_temperature,
                    "max_tokens": self._settings.llm_max_tokens,
                },
            )
            response.raise_for_status()
        except httpx.TimeoutException as exc:
            raise ServiceError(504, 50401, "LLM upstream timed out") from exc
        except httpx.HTTPError as exc:
            raise ServiceError(502, 50201, "LLM upstream request failed") from exc
        try:
            choice = response.json()["choices"][0]
            content = choice["message"]["content"]
            if choice.get("finish_reason") != "stop" or not isinstance(content, str) or not content:
                raise ValueError("incomplete completion")
            return content
        except (ValueError, KeyError, IndexError, TypeError) as exc:
            raise ServiceError(502, 50201, "LLM upstream returned an invalid completion") from exc

    async def aclose(self) -> None:
        await self._client.aclose()
