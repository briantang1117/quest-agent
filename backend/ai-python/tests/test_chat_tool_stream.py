"""合成 SSE 验证工具参数分片，测试不调用真实模型。"""

import asyncio
import json

import httpx
import pytest
from conftest import settings

from app.core.errors import ServiceError
from app.llm.openai_compatible import OpenAICompatibleProvider


def run_stream(chunks, finish="tool_calls", done=True):
    requests = []

    def handler(request):
        requests.append(json.loads(request.content))
        events = [
            {"choices": [{"index": 0, "delta": delta, "finish_reason": None}]} for delta in chunks
        ] + [{"choices": [{"index": 0, "delta": {}, "finish_reason": finish}]}]
        wire = "".join(f"data: {json.dumps(e)}\n\n" for e in events)
        if done:
            wire += "data: [DONE]\n\n"
        return httpx.Response(200, content=wire, headers={"content-type": "text/event-stream"})

    async def collect():
        provider = OpenAICompatibleProvider(
            settings(llm_base_url="https://llm.example/v1", llm_api_key="synthetic", llm_model="m"),
            transport=httpx.MockTransport(handler),
        )
        try:
            return [
                event
                async for event in provider.stream_chat(
                    [{"role": "user", "content": "问题"}], tools=[{"type": "function"}]
                )
            ]
        finally:
            await provider.aclose()

    return asyncio.run(collect()), requests


def fragment(arguments, **extra):
    return {"tool_calls": [{"index": 0, "function": {"arguments": arguments}, **extra}]}


def test_assembles_arguments_without_exposing_fragments():
    events, requests = run_stream(
        [
            fragment(
                '{"query":',
                id="call_1",
                type="function",
                function={"name": "search_web", "arguments": '{"query":'},
            ),
            fragment('"上海政策"}'),
        ]
    )
    assert events == [
        {
            "type": "tool_calls",
            "calls": [
                {
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "search_web", "arguments": '{"query":"上海政策"}'},
                }
            ],
        }
    ]
    assert requests[0]["tool_choice"] == "auto"
    assert "response_format" not in requests[0]


@pytest.mark.parametrize(
    "chunks,finish,done",
    [
        (
            [
                fragment(
                    "{", id="c", type="function", function={"name": "search_web", "arguments": "{"}
                )
            ],
            "tool_calls",
            True,
        ),
        (
            [
                fragment(
                    "{}",
                    id="c",
                    type="function",
                    function={"name": "search_web", "arguments": "{}"},
                )
            ],
            "stop",
            True,
        ),
        ([{"content": "正文"}], "length", True),
        ([{"content": "正文"}], "stop", False),
        ([{"tool_calls": [{"index": 6}]}], "tool_calls", True),
    ],
)
def test_rejects_incomplete_or_invalid_tool_stream(chunks, finish, done):
    with pytest.raises(ServiceError):
        run_stream(chunks, finish, done)


def test_final_content_keeps_streaming_contract():
    events, _ = run_stream([{"content": '{"reply":'}, {"content": '"你好"}'}], "stop")
    assert events == [
        {"type": "content", "text": '{"reply":'},
        {"type": "content", "text": '"你好"}'},
    ]


@pytest.mark.parametrize(
    "code,expected", [("unsupported_parameter", 50205), ("invalid_api_key", 50201)]
)
def test_only_explicit_unsupported_tools_error_is_safe_to_fallback(code, expected):
    requests = []

    def handler(request):
        requests.append(request)
        return httpx.Response(400, json={"error": {"param": "tools", "code": code}})

    async def run():
        provider = OpenAICompatibleProvider(
            settings(llm_base_url="https://llm.example/v1", llm_api_key="synthetic", llm_model="m"),
            transport=httpx.MockTransport(handler),
        )
        try:
            with pytest.raises(ServiceError) as failure:
                _ = [event async for event in provider.stream_chat([], tools=[])]
            assert failure.value.code == expected
        finally:
            await provider.aclose()

    asyncio.run(run())
    assert len(requests) == 1
