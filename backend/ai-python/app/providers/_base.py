from types import TracebackType
from urllib.parse import urlsplit

import httpx

from app.core.config import Settings
from app.core.errors import ServiceError


class ToolProvider:
    """单能力、单密钥的异步客户端；不重试，不信任环境代理。"""

    def __init__(
        self,
        settings: Settings,
        *,
        capability: str,
        base_field: str,
        key_field: str,
        model_field: str | None = None,
        transport: httpx.AsyncBaseTransport | None = None,
    ):
        base_url = getattr(settings, base_field, None)
        secret = getattr(settings, key_field, None)
        model = getattr(settings, model_field, None) if model_field else None
        if (
            not isinstance(base_url, str)
            or not base_url.strip()
            or secret is None
            or not secret.get_secret_value().strip()
            or (model_field and (not isinstance(model, str) or not model.strip()))
        ):
            raise ServiceError(503, 50301, f"{capability}工具未完整配置")
        try:
            parts = urlsplit(base_url)
            valid_url = (
                parts.scheme in {"http", "https"}
                and parts.hostname
                and parts.port != 0
                and not parts.username
                and not parts.password
                and not parts.query
                and not parts.fragment
                and not any(char.isspace() or ord(char) < 32 for char in base_url)
            )
        except ValueError:
            valid_url = False
        if not valid_url:
            raise ServiceError(503, 50301, f"{capability}工具地址配置无效")
        self._base_url = base_url.rstrip("/")
        self._model = model
        self._capability = capability
        self._settings = settings
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(settings.llm_timeout_seconds),
            transport=transport,
            follow_redirects=False,
            trust_env=False,
            headers={"Authorization": f"Bearer {secret.get_secret_value()}"},
        )

    def _invalid_output(self) -> ServiceError:
        return ServiceError(502, 50202, f"{self._capability}上游返回无效或不完整数据")

    async def _post(self, path: str, body: dict, *, max_response_bytes: int | None = None) -> dict:
        try:
            if max_response_bytes is None:
                response = await self._client.post(f"{self._base_url}{path}", json=body)
            else:
                async with self._client.stream(
                    "POST", f"{self._base_url}{path}", json=body
                ) as upstream:
                    raw = bytearray()
                    async for block in upstream.aiter_bytes(chunk_size=16384):
                        if len(raw) + len(block) > max_response_bytes:
                            raise self._invalid_output()
                        raw.extend(block)
                    content_type = upstream.headers.get("Content-Type", "").split(";", 1)[0].lower()
                    if (
                        200 <= upstream.status_code < 300
                        and content_type
                        and not (
                            content_type == "application/json" or content_type.endswith("+json")
                        )
                    ):
                        raise self._invalid_output()
                    response = httpx.Response(upstream.status_code, content=bytes(raw))
        except httpx.TimeoutException:
            raise ServiceError(504, 50401, f"{self._capability}上游请求超时") from None
        except httpx.HTTPError:
            raise ServiceError(502, 50201, f"{self._capability}上游网络请求失败") from None
        status = response.status_code
        # 不使用响应正文、URL或底层异常构造错误，避免泄漏密钥与源资料。
        if status == 401:
            raise ServiceError(503, 50302, f"{self._capability}上游认证失败（401）")
        if status == 403:
            raise ServiceError(503, 50303, f"{self._capability}上游权限不足（403）")
        if status == 402:
            raise ServiceError(503, 50304, f"{self._capability}上游配额或余额不足（402）")
        if status == 429:
            raise ServiceError(503, 50305, f"{self._capability}上游限流（429），未自动重试")
        if status >= 500:
            raise ServiceError(502, 50203, f"{self._capability}上游服务异常（5xx）")
        if 300 <= status < 400:
            raise ServiceError(502, 50204, f"{self._capability}上游重定向已拒绝")
        if not 200 <= status < 300:
            raise ServiceError(502, 50201, f"{self._capability}上游拒绝请求")
        try:
            payload = response.json()
        except (ValueError, UnicodeError):
            raise self._invalid_output() from None
        if not isinstance(payload, dict):
            raise self._invalid_output()
        return payload

    async def aclose(self) -> None:
        await self._client.aclose()

    async def __aenter__(self):
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        await self.aclose()
