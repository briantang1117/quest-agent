import asyncio
import json

import httpx
import pytest
from conftest import settings
from test_stream_text import choice, event, sse_body

from app.core.errors import ServiceError
from app.llm.openai_compatible import OpenAICompatibleProvider


def provider_for(handler, **overrides):
    return OpenAICompatibleProvider(
        settings(
            llm_base_url="https://llm.example/v1",
            llm_api_key="fake-key",
            llm_model="fake-model",
            **overrides,
        ),
        transport=httpx.MockTransport(handler),
    )


def collect(provider, messages=None):
    async def run():
        try:
            return await provider.generate_material(
                messages or [{"role": "user", "content": "整理"}]
            )
        finally:
            await provider.aclose()

    return asyncio.run(run())


def test_material_stream_has_independent_token_timeout_and_large_output_budget():
    content = json.dumps({"title": "复习", "markdown": "文" * 100000}, ensure_ascii=False)
    calls = []

    def handler(request):
        calls.append(request)
        body = json.loads(request.content)
        assert body["stream"] is True
        assert body["max_tokens"] == 32768
        assert body["response_format"] == {"type": "json_object"}
        assert request.extensions["timeout"] == dict(connect=600, read=600, write=600, pool=600)
        return httpx.Response(
            200, content=sse_body([content[i : i + 1000] for i in range(0, len(content), 1000)])
        )

    assert collect(provider_for(handler)) == content
    assert len(calls) == 1


@pytest.mark.parametrize(
    "body",
    [
        event(choice("partial")),
        event(choice("partial", "stop")),
        event(choice("partial")) + "data: [DONE]\n\n",
        event(choice("partial", "length")) + "data: [DONE]\n\n",
        event(choice("partial", "content_filter")) + "data: [DONE]\n\n",
        event(choice("partial", "tool_calls")) + "data: [DONE]\n\n",
        event(choice("partial", "stop")) + event(choice("more")) + "data: [DONE]\n\n",
        event(choice(" ", "stop")) + "data: [DONE]\n\n",
        event({"choices": [{"delta": {"tool_calls": []}}]}) + "data: [DONE]\n\n",
        "data: not-json SECRET\n\n",
        "data: [DONE]\n\n",
        "data: " + "x" * (64 * 1024 + 1) + "\n\n",
    ],
)
def test_material_rejects_invalid_stream_and_never_retries(body, caplog):
    calls = []

    def handler(request):
        calls.append(1)
        return httpx.Response(200, content=body.encode())

    with pytest.raises(ServiceError) as error:
        collect(provider_for(handler))
    assert error.value.code == 50201
    assert "SECRET" not in str(error.value) + caplog.text
    assert len(calls) == 1


def test_material_content_and_wire_output_are_bounded():
    class ExcessiveWire(httpx.AsyncByteStream):
        async def __aiter__(self):
            for _ in range(257):
                yield b":" + b"x" * 4093 + b"\n\n"

    with pytest.raises(ServiceError):
        collect(provider_for(lambda request: httpx.Response(200, stream=ExcessiveWire())))
    with pytest.raises(ServiceError):
        collect(
            provider_for(lambda request: httpx.Response(200, content=sse_body(["x" * 1000] * 1049)))
        )


def test_material_request_input_is_bounded_before_network_call():
    calls = []

    def handler(request):
        calls.append(1)
        return httpx.Response(200, content=sse_body(["{} "]))

    with pytest.raises(ServiceError) as error:
        collect(
            provider_for(handler, material_max_input_bytes=1024),
            [{"role": "user", "content": "x" * 2000}],
        )
    assert error.value.status == 413
    assert not calls


@pytest.mark.parametrize(
    "failure,code", [(httpx.ReadTimeout("SECRET"), 50401), (httpx.ConnectError("SECRET"), 50201)]
)
def test_material_transport_errors_do_not_retry(failure, code):
    calls = []

    def handler(request):
        calls.append(1)
        raise failure

    with pytest.raises(ServiceError) as error:
        collect(provider_for(handler))
    assert error.value.code == code
    assert len(calls) == 1


def test_material_total_timeout_covers_entire_stream_not_only_individual_reads():
    class SlowStream(httpx.AsyncByteStream):
        closed = False

        async def __aiter__(self):
            yield event(choice("partial")).encode()
            await asyncio.Event().wait()

        async def aclose(self):
            self.closed = True

    stream = SlowStream()
    with pytest.raises(ServiceError) as error:
        collect(
            provider_for(
                lambda request: httpx.Response(200, stream=stream),
                material_llm_timeout_seconds=0.02,
            )
        )
    assert error.value.code == 50401
    assert stream.closed


def test_material_split_utf8_crlf_role_reasoning_and_usage_are_supported():
    class FragmentedStream(httpx.AsyncByteStream):
        async def __aiter__(self):
            body = event(
                {"choices": [{"delta": {"role": "assistant", "reasoning_content": "不转发"}}]}
            )
            body += event(choice("中文😀", "stop"))
            body += event({"choices": [], "usage": {"total_tokens": 8}}) + "data: [DONE]\n\n"
            for byte in body.replace("\n", "\r\n").encode():
                yield bytes([byte])

    assert (
        collect(provider_for(lambda request: httpx.Response(200, stream=FragmentedStream())))
        == "中文😀"
    )


def test_material_config_defaults_do_not_expand_regular_chat_limits():
    config = settings()
    assert config.material_max_input_chars == 1000000
    assert config.material_max_input_bytes == 8 * 1024 * 1024
    assert config.material_max_output_chars == 100000
    assert config.material_max_output_tokens == 32768
    assert config.material_llm_timeout_seconds == 600
    assert config.llm_timeout_seconds == 30
    assert config.llm_max_tokens == 4096
    assert config.explanation_max_tokens == 8192
