import asyncio
import json

import httpx
import pytest
from conftest import settings

from app.core.errors import ServiceError
from app.llm.openai_compatible import OpenAICompatibleProvider


def event(payload):
    return f"data: {json.dumps(payload, ensure_ascii=False)}\n\n"


def choice(content=None, finish=None):
    delta = {} if content is None else {"content": content}
    return {"choices": [{"index": 0, "delta": delta, "finish_reason": finish}]}


def sse_body(chunks):
    return (
        "".join(event(choice(text)) for text in chunks)
        + event(choice(finish="stop"))
        + "data: [DONE]\n\n"
    ).encode()


def provider_for(handler):
    config = settings(
        llm_base_url="https://llm.example/v1",
        llm_api_key="secret-provider-key",
        llm_model="test-model",
    )
    return OpenAICompatibleProvider(config, transport=httpx.MockTransport(handler))


def collect(provider, *, json_mode=False):
    async def run():
        try:
            messages = [{"role": "user", "content": "讲"}]
            stream = (
                provider.stream_text(messages, json_mode=True)
                if json_mode
                else provider.stream_text(messages)
            )
            return [chunk async for chunk in stream]
        finally:
            await provider.aclose()

    return asyncio.run(run())


def test_stream_text_yields_incremental_chunks_and_requests_json_mode():
    def handler(request):
        body = json.loads(request.content)
        assert body["response_format"] == {"type": "json_object"}
        assert body["stream"] is True
        return httpx.Response(200, content=sse_body(["宏观", "经济", "政策"]))

    assert collect(provider_for(handler), json_mode=True) == ["宏观", "经济", "政策"]


def test_plain_text_mode_omits_response_format_and_accepts_keepalive_role_and_usage():
    def handler(request):
        assert "response_format" not in json.loads(request.content)
        body = (
            ": keepalive\n\n"
            + event({"choices": [{"index": 0, "delta": {"role": "assistant"}}]})
            + event(choice("有效"))
            + event(choice(finish="stop"))
            + event({"choices": [], "usage": {"total_tokens": 7}})
            + "data: [DONE]\n\n"
        )
        return httpx.Response(200, content=body.encode())

    assert collect(provider_for(handler)) == ["有效"]


@pytest.mark.parametrize(
    "body",
    [
        event(choice("半截")) + "data: [DONE]\n\n",
        event(choice("半截")) + event(choice(finish="stop")),
        event(choice("半截")),
        event(choice(finish="stop")) + "data: [DONE]\n\n",
        event(choice("  \n")) + event(choice(finish="stop")) + "data: [DONE]\n\n",
        event(choice("半截", "length")) + "data: [DONE]\n\n",
        event(choice("半截", "content_filter")) + "data: [DONE]\n\n",
        event(choice("半截", "tool_calls")) + "data: [DONE]\n\n",
        event({"error": {"message": "SECRET_UPSTREAM"}}),
        "data: not-json\n\n",
        event({"choices": [{}]}),
        event({"choices": [{"index": 0, "delta": {"content": 123}}]}),
        event({"choices": [{"index": 0, "delta": {"tool_calls": []}}]}),
        event({"choices": []}),
        event({"choices": [choice("x")["choices"][0], choice("y")["choices"][0]]}),
        event(choice("x", "stop")) + event(choice("y")) + "data: [DONE]\n\n",
        event({"choices": [{"index": 1, "delta": {"content": "x"}}]}),
        "data: [DONE]\n\n",
        'data: {"choices":\n\n' + event(choice("x", "stop")) + "data: [DONE]\n\n",
        "data: " + "x" * (64 * 1024 + 1) + "\n\n",
    ],
)
def test_invalid_or_incomplete_stream_is_never_success(body):
    calls = []

    def handler(request):
        calls.append(1)
        return httpx.Response(200, content=body.encode())

    with pytest.raises(ServiceError) as error:
        collect(provider_for(handler))
    assert error.value.code == 50201
    assert "SECRET_UPSTREAM" not in str(error.value)
    assert len(calls) == 1


@pytest.mark.parametrize("reasoning", [None, "PRIVATE_REASONING"])
def test_compatible_provider_reasoning_metadata_does_not_break_visible_content(reasoning):
    def handler(request):
        # 部分 OpenAI-compatible 服务在每个 delta 中附带 reasoning_content，
        # 包括正文分片上的 null；它不是工具调用，也不是应展示的正文。
        body = event(
            {
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "role": "assistant",
                            "content": "",
                            "reasoning_content": reasoning,
                        },
                        "finish_reason": None,
                    }
                ]
            }
        )
        body += event(
            {
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "content": "有效正文",
                            "reasoning_content": None,
                        },
                        "finish_reason": None,
                    }
                ]
            }
        )
        body += event(choice(finish="stop")) + "data: [DONE]\n\n"
        return httpx.Response(200, content=body.encode())

    assert collect(provider_for(handler)) == ["有效正文"]


def test_stream_content_has_bounded_size():
    def handler(request):
        return httpx.Response(200, content=sse_body(["字" * 22000]))

    with pytest.raises(ServiceError):
        collect(provider_for(handler))


def test_stream_text_surfaces_timeout_without_retry():
    calls = []

    def handler(request):
        calls.append(1)
        raise httpx.ReadTimeout("timed out", request=request)

    with pytest.raises(ServiceError) as error:
        collect(provider_for(handler))
    assert error.value.code == 50401
    assert len(calls) == 1


def test_stream_text_surfaces_http_error():
    def handler(request):
        return httpx.Response(500)

    with pytest.raises(ServiceError) as error:
        collect(provider_for(handler))
    assert error.value.code == 50201


@pytest.mark.parametrize("invalid", [b"\xff", b"\xe4\xb8"])
def test_invalid_or_truncated_utf8_is_safe(invalid):
    provider = provider_for(lambda request: httpx.Response(200, content=invalid))
    with pytest.raises(ServiceError) as error:
        collect(provider)
    assert error.value.code == 50201


def test_crlf_and_utf8_network_splits_are_supported():
    class FragmentedStream(httpx.AsyncByteStream):
        async def __aiter__(self):
            raw = sse_body(["中文😀"]).replace(b"\n", b"\r\n")
            for byte in raw:
                yield bytes([byte])

    provider = provider_for(lambda request: httpx.Response(200, stream=FragmentedStream()))
    assert collect(provider) == ["中文😀"]


def test_stream_total_wire_bytes_are_bounded_even_for_keepalives():
    class ExcessiveStream(httpx.AsyncByteStream):
        async def __aiter__(self):
            for _ in range(257):
                yield b":" + b"x" * 4093 + b"\n\n"

    provider = provider_for(lambda request: httpx.Response(200, stream=ExcessiveStream()))
    with pytest.raises(ServiceError):
        collect(provider)


def test_stream_yields_before_waiting_for_the_next_network_chunk():
    async def run():
        waiting = asyncio.Event()

        class LiveStream(httpx.AsyncByteStream):
            async def __aiter__(self):
                yield event(choice("先到")).encode()
                await waiting.wait()
                yield (event(choice("后到", "stop")) + "data: [DONE]\n\n").encode()

        provider = provider_for(lambda request: httpx.Response(200, stream=LiveStream()))
        stream = provider.stream_text([{"role": "user", "content": "讲"}])
        try:
            assert await asyncio.wait_for(anext(stream), timeout=0.1) == "先到"
            waiting.set()
            assert [chunk async for chunk in stream] == ["后到"]
        finally:
            await stream.aclose()
            await provider.aclose()

    asyncio.run(run())


class BrokenStream(httpx.AsyncByteStream):
    async def __aiter__(self):
        yield event(choice("半截")).encode()
        raise httpx.ReadError("SECRET_UPSTREAM")


def test_transport_interruption_after_content_is_safe_and_not_retried():
    calls = []

    def handler(request):
        calls.append(1)
        return httpx.Response(200, stream=BrokenStream())

    with pytest.raises(ServiceError) as error:
        collect(provider_for(handler))
    assert error.value.code == 50201
    assert "SECRET_UPSTREAM" not in str(error.value)
    assert len(calls) == 1
