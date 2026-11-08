from datetime import UTC, datetime

import httpx

from app.core.config import Settings
from app.core.errors import ServiceError
from app.providers._base import ToolProvider
from app.providers._urls import (
    Resolver,
    public_url_host,
    resolve_public_addresses,
    validate_public_url,
)
from app.providers.models import FetchResult, SearchHit, SearchResult


class DeepSeekSearchProvider(ToolProvider):
    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None):
        super().__init__(
            settings,
            capability="Search",
            base_field="search_api_base_url",
            key_field="search_api_key",
            transport=transport,
        )

    async def search(
        self, query: str, *, count: int = 5, need_content: bool = False
    ) -> SearchResult:
        if (
            not isinstance(query, str)
            or not query.strip()
            or not 1 <= len(query) <= 128
            or type(count) is not int
            or not 1 <= count <= 50
            or type(need_content) is not bool
        ):
            raise ServiceError(
                400,
                40001,
                "Search要求query为1–128字符、count为1–50整数、need_content为布尔值",
            )
        payload = await self._post(
            "/tools/search", {"query": query, "count": count, "need_content": need_content}
        )
        items = payload.get("results")
        if not isinstance(items, list) or len(items) > count:
            raise self._invalid_output()
        hits = []
        for item in items:
            if (
                not isinstance(item, dict)
                or not isinstance(item.get("title"), str)
                or not isinstance(item.get("url"), str)
                or any(
                    key in item and item[key] is not None and not isinstance(item[key], str)
                    for key in ("content", "snippet")
                )
            ):
                raise self._invalid_output()
            try:
                # 这里只保留来源，不自动访问；真正fetch时仍须重新校验DNS。
                public_url_host(item["url"])
            except ServiceError:
                raise self._invalid_output() from None
            hits.append(
                SearchHit(
                    title=item["title"],
                    url=item["url"],
                    content=item.get("content"),
                    snippet=item.get("snippet"),
                )
            )
        return SearchResult(query=query, results=tuple(hits), retrieved_at=datetime.now(UTC))


class DeepSeekFetchProvider(ToolProvider):
    def __init__(
        self,
        settings: Settings,
        *,
        transport: httpx.AsyncBaseTransport | None = None,
        resolver: Resolver = resolve_public_addresses,
    ):
        super().__init__(
            settings,
            capability="Fetch",
            base_field="fetch_api_base_url",
            key_field="fetch_api_key",
            transport=transport,
        )
        self._resolver = resolver

    async def fetch(self, url: str) -> FetchResult:
        """抓取获授权的公开网页，不直接请求目标站点，也不自动跟随工具重定向。

        DNS校验不能约束远端工具二次解析及站内跳转；远端仍必须自行落实
        DNS绑定和每跳公网校验，不能将此客户端当作远端SSRF沙箱。
        """
        await validate_public_url(url, self._resolver, self._settings.llm_timeout_seconds)
        payload = await self._post("/tools/fetch", {"url": url})
        title, content = payload.get("title"), payload.get("content")
        if not isinstance(title, str) or not isinstance(content, str) or not content.strip():
            raise self._invalid_output()
        # 若工具显式报告最终URL，再次做公网/DNS校验；不可把它当作事前隔离保证。
        for key in ("url", "final_url"):
            if key in payload:
                try:
                    await validate_public_url(
                        payload[key], self._resolver, self._settings.llm_timeout_seconds
                    )
                except ServiceError:
                    raise self._invalid_output() from None
        return FetchResult(url=url, title=title, content=content, retrieved_at=datetime.now(UTC))
