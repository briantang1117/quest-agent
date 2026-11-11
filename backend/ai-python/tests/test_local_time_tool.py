"""本地时间工具的固定时钟、时区及模型观察测试。"""

import asyncio
import json
from datetime import UTC, datetime

import pytest
from conftest import settings
from fastapi.testclient import TestClient

from app.agent.coach_tools import explain_with_tools
from app.agent.tools import ToolExecutor
from app.main import create_app
from tests.test_coach_explain import events, send, text_of
from tests.test_coach_tool_explain import ToolProvider
from tests.test_coach_tools import FINAL, Provider, Search, call


def time_call(timezone="Asia/Shanghai", id="clock"):
    return {
        "id": id,
        "type": "function",
        "function": {"name": "get_current_time", "arguments": json.dumps({"timezone": timezone})},
    }


@pytest.mark.parametrize(
    "timezone,date,year,offset",
    [
        ("Asia/Shanghai", "2027-01-01", 2027, "+08:00"),
        ("UTC", "2026-12-31", 2026, "+00:00"),
        ("America/New_York", "2026-12-31", 2026, "-05:00"),
    ],
)
def test_fixed_clock_crosses_year_in_requested_timezone(timezone, date, year, offset):
    async def run():
        executor = ToolExecutor(None, clock=lambda: datetime(2026, 12, 31, 18, tzinfo=UTC))
        result = await executor.execute(time_call(timezone))
        assert result["date"] == date
        assert result["year"] == year
        assert result["utc_offset"] == offset
        assert result["timezone"] == timezone
        assert executor.sources == []
        assert executor.audits[0]["tool"] == "get_current_time"

    asyncio.run(run())


@pytest.mark.parametrize(
    "arguments",
    [
        '{"timezone":"No/SuchZone"}',
        '{"timezone":"../../etc/passwd"}',
        '{"timezone":123}',
        '{"userId":"other"}',
    ],
)
def test_invalid_timezone_and_extra_arguments_are_rejected(arguments):
    async def run():
        item = time_call()
        item["function"]["arguments"] = arguments
        assert (await ToolExecutor(None).execute(item))["error"] == "invalid_arguments"

    asyncio.run(run())


def test_default_timezone_and_duplicate_clock_calls_share_snapshot():
    async def run():
        invocations = []

        def clock():
            invocations.append(True)
            return datetime(2026, 1, 1, tzinfo=UTC)

        executor = ToolExecutor(None, clock=clock)
        item = time_call()
        item["function"]["arguments"] = "{}"
        first = await executor.execute(item)
        second = await executor.execute(time_call())
        assert first == second
        assert first["timezone"] == "Asia/Shanghai"
        assert len(invocations) == len(executor.audits) == 1

    asyncio.run(run())


def test_model_observes_clock_before_it_builds_search_query():
    async def run():
        provider = Provider(
            [
                [{"type": "tool_calls", "calls": [time_call()]}],
                [{"type": "tool_calls", "calls": [call("2026 上海市考 日期")]}],
                FINAL,
            ]
        )
        got = [e async for e in explain_with_tools(provider, Search(), [])]
        observation = json.loads(provider.requests[1][0][-1]["content"])
        assert observation["timezone"] == "Asia/Shanghai"
        assert "year" in observation
        assert any(e["type"] == "tool_status" and e["tool"] == "get_current_time" for e in got)
        assert all(s["tool"] != "get_current_time" for s in got[-1]["items"])

    asyncio.run(run())


def test_clock_tool_is_available_without_web_credentials():
    provider = ToolProvider([[{"type": "tool_calls", "calls": [time_call()]}], FINAL])
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client, {"scope": "student", "message": "今天是哪天"})
    assert text_of(response) == "解释"
    assert [t["function"]["name"] for t in provider.requests[0][1]["tools"]] == ["get_current_time"]
    assert any(
        name == "tool_status" and data["tool"] == "get_current_time"
        for name, data in events(response)
    )
    assert all(name != "sources" for name, _ in events(response))
