"""网页工具与预算的离线测试，使用合成文本和凭据。"""

import asyncio
import json
from datetime import UTC, datetime
from types import SimpleNamespace

import pytest
from conftest import settings
from fastapi.testclient import TestClient

from app.agent.coach_tools import explain_with_tools
from app.agent.tools import ToolExecutor
from app.main import create_app
from tests.test_coach_explain import QUESTION, events, send, text_of
from tests.test_coach_tool_explain import CloseableSearch, ToolProvider
from tests.test_coach_tools import FINAL, Search, call


def fetch_call(url="https://example.com/reference", id="f"):
    return {
        "id": id,
        "type": "function",
        "function": {"name": "fetch_page", "arguments": json.dumps({"url": url})},
    }


class Fetch:
    def __init__(self, content="页面正文", fail=False):
        self.urls = []
        self.content = content
        self.fail = fail
        self.closed = False

    async def fetch(self, url):
        self.urls.append(url)
        if self.fail:
            raise TimeoutError("secret")
        return SimpleNamespace(
            title="公开网页", url=url, content=self.content, retrieved_at=datetime.now(UTC)
        )

    async def aclose(self):
        self.closed = True


def test_fetch_deduplicates_normalized_url_and_truncates_in_combined_byte_budget():
    async def run():
        fetch = Fetch(content="中" * 30000)
        executor = ToolExecutor(Search(snippet="文" * 15000), fetch=fetch)
        observations = [await executor.execute(call())]
        observations.append(await executor.execute(fetch_call("https://EXAMPLE.com:443/reference")))
        observations.append(await executor.execute(fetch_call()))
        observations.extend([await executor.execute(fetch_call(id=str(i))) for i in range(3)])
        assert len(fetch.urls) == 1
        assert len(observations[1].get("text", "")) <= 20000
        assert observations[1]["truncated"]
        assert (
            sum(
                len(json.dumps(o, ensure_ascii=False, separators=(",", ":")).encode())
                for o in observations
            )
            <= 24 * 1024
        )
        assert len(executor.audits) == 2
        assert executor.audits[-1]["tool"] == "fetch_page"
        assert "text" not in executor.sources[-1]

    asyncio.run(run())


@pytest.mark.parametrize(
    "url",
    [
        "file:///etc/passwd",
        "http://127.0.0.1",
        "http://169.254.169.254/",
        "http://[::1]/",
        "https://user:pass@example.com",
        "https://example.com/%0a",
    ],
)
def test_executor_rejects_unsafe_url_before_fetch(url):
    async def run():
        fetch = Fetch()
        result = await ToolExecutor(None, fetch=fetch).execute(fetch_call(url))
        assert result["error"] == "invalid_arguments"
        assert fetch.urls == []

    asyncio.run(run())


def test_search_then_fetch_observation_reaches_model_and_sources_keep_tools():
    async def run():
        provider = ToolProvider(
            [
                [{"type": "tool_calls", "calls": [call()]}],
                [{"type": "tool_calls", "calls": [fetch_call()]}],
                FINAL,
            ]
        )
        got = [e async for e in explain_with_tools(provider, Search(), [], fetch=Fetch())]
        assert [tool["function"]["name"] for tool in provider.requests[0][1]["tools"]] == [
            "get_current_time",
            "search_web",
            "fetch_page",
        ]
        assert json.loads(provider.requests[-1][0][-1]["content"])["text"] == "页面正文"
        assert {s["tool"] for s in got[-1]["items"]} == {"search_web", "fetch_page"}
        assert [e for e in got if e["type"] == "content"] == FINAL

    asyncio.run(run())


def test_fetch_failure_is_cached_and_not_retried():
    async def run():
        fetch = Fetch(fail=True)
        executor = ToolExecutor(None, fetch=fetch)
        results = [await executor.execute(fetch_call()), await executor.execute(fetch_call())]
        assert len(fetch.urls) == 1
        assert results[0]["error"] == results[1]["error"] == "timeout"
        assert "secret" not in str(results)
        assert executor.sources == []

    asyncio.run(run())


def test_default_on_fetch_only_endpoint_and_lifecycle():
    assert settings().coach_tools_enabled is True
    provider = ToolProvider([[{"type": "tool_calls", "calls": [fetch_call()]}], FINAL])
    fetch = Fetch()
    with TestClient(create_app(settings(), provider=provider, fetch=fetch)) as client:
        response = send(client, {"scope": "student", "message": "读这篇公开文章"})
    assert text_of(response) == "解释"
    assert fetch.closed
    assert fetch.urls == ["https://example.com/reference"]
    assert [tool["function"]["name"] for tool in provider.requests[0][1]["tools"]] == [
        "get_current_time",
        "fetch_page",
    ]
    assert dict(events(response))["sources"]["items"][0]["tool"] == "fetch_page"


def test_default_on_unanswered_question_never_fetches():
    fetch = Fetch()
    with TestClient(
        create_app(settings(), provider=ToolProvider([]), search=CloseableSearch(), fetch=fetch)
    ) as client:
        response = send(client, {"scope": "student", "message": "查答案", "question": QUESTION})
    assert text_of(response) == "直接讲解"
    assert fetch.urls == []


def test_fetch_total_deadline_cancels_inflight_request():
    class SlowFetch(Fetch):
        async def fetch(self, url):
            try:
                await asyncio.sleep(10)
            finally:
                self.cancelled = True

    async def run():
        fetch = SlowFetch()
        provider = ToolProvider([[{"type": "tool_calls", "calls": [fetch_call()]}]])
        from app.core.errors import ServiceError

        with pytest.raises(ServiceError) as failure:
            _ = [
                event
                async for event in explain_with_tools(
                    provider, None, [], fetch=fetch, budget_seconds=0.02
                )
            ]
        assert failure.value.status == 504
        assert fetch.cancelled

    asyncio.run(run())


def test_configured_fetch_provider_is_initialized_and_closed(monkeypatch):
    fetch = Fetch()
    monkeypatch.setattr("app.main.DeepSeekFetchProvider", lambda config: fetch)
    with TestClient(
        create_app(
            settings(fetch_api_base_url="https://fetch.example/v1", fetch_api_key="synthetic")
        )
    ) as client:
        assert client.app.state.fetch_provider is fetch
    assert fetch.closed


@pytest.mark.parametrize("length,truncated", [(20000, False), (20001, True)])
def test_fetch_text_character_boundary(length, truncated):
    async def run():
        result = await ToolExecutor(None, fetch=Fetch(content="x" * length)).execute(fetch_call())
        assert len(result["text"]) == 20000
        assert result["truncated"] is truncated

    asyncio.run(run())


def test_provider_timeout_keeps_timeout_audit_category():
    from app.core.errors import ServiceError

    class TimedOutFetch(Fetch):
        async def fetch(self, url):
            self.urls.append(url)
            raise ServiceError(504, 50401, "timeout")

    async def run():
        fetch = TimedOutFetch()
        executor = ToolExecutor(None, fetch=fetch)
        first = await executor.execute(fetch_call())
        await executor.execute(fetch_call())
        assert first["error"] == "timeout"
        assert executor.audits[0]["failure"] == "timeout"
        assert len(fetch.urls) == 1

    asyncio.run(run())
