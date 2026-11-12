import asyncio
import codecs
import json
import logging
from contextlib import aclosing

import httpx

from app.core.config import Settings
from app.core.errors import ServiceError
from app.llm.tool_stream import ToolStream

logger = logging.getLogger(__name__)


async def _sse_payloads(response: httpx.Response, *, max_wire_bytes: int = 1024 * 1024):
    """按 SSE 事件读入，在线限制行、事件和总字节，避免 aiter_lines 无界积累。"""
    decoder = codecs.getincrementaldecoder("utf-8")("strict")
    pending, data, total = "", [], 0
    async for block in response.aiter_bytes():
        total += len(block)
        if total > max_wire_bytes:
            raise ValueError("stream too large")
        pending += decoder.decode(block)
        while "\n" in pending:
            line, pending = pending.split("\n", 1)
            line = line.removesuffix("\r")
            if len(line.encode("utf-8")) > 64 * 1024:
                raise ValueError("SSE line too large")
            if not line:
                if data:
                    yield "\n".join(data)
                    data = []
            elif line.startswith("data:"):
                data.append(line[5:].removeprefix(" "))
                if sum(len(item.encode("utf-8")) for item in data) > 64 * 1024:
                    raise ValueError("SSE event too large")
            elif not line.startswith(":"):
                raise ValueError("unexpected SSE field")
        if len(pending.encode("utf-8")) > 64 * 1024:
            raise ValueError("SSE line too large")
    decoder.decode(b"", final=True)
    if pending or data:
        raise ValueError("truncated SSE event")


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

    async def generate_json(
        self, messages: list[dict[str, str]], *, background: bool = False
    ) -> str:
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
                timeout=self._settings.background_timeout if background else None,
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

    async def stream_text(
        self, messages: list[dict[str, str]], *, json_mode: bool = False, long_form: bool = False
    ):
        """有界正文流；仅 stop、非空正文及 [DONE] 三者齐备才成功，不重试。"""
        max_tokens = (
            self._settings.explanation_max_tokens if long_form else self._settings.llm_max_tokens
        )
        async with aclosing(
            self._stream_completion(messages, json_mode=json_mode, max_tokens=max_tokens)
        ) as stream:
            async for chunk in stream:
                yield chunk

    async def generate_material(self, messages: list[dict[str, str]]) -> str:
        """资料专用长流聚合；整次调用超时，不重试、不返回半截正文。"""
        request_body = self._completion_body(
            messages, json_mode=True, max_tokens=self._settings.material_max_output_tokens
        )
        if len(json.dumps(request_body, ensure_ascii=False).encode("utf-8")) > (
            self._settings.material_max_input_bytes
        ):
            raise ServiceError(413, 41301, "资料模型请求超过大小上限，请缩小范围")
        try:
            async with asyncio.timeout(self._settings.material_llm_timeout_seconds):
                async with aclosing(
                    self._stream_completion(
                        messages,
                        json_mode=True,
                        max_tokens=self._settings.material_max_output_tokens,
                        content_limit=1024 * 1024,
                        max_wire_bytes=self._settings.material_max_stream_bytes,
                        request_timeout=self._settings.material_llm_timeout_seconds,
                    )
                ) as stream:
                    return "".join([chunk async for chunk in stream])
        except TimeoutError as exc:
            raise ServiceError(504, 50401, "LLM upstream timed out") from exc

    def _completion_body(self, messages, *, json_mode, max_tokens):
        body = {
            "model": self._settings.llm_model,
            "messages": messages,
            "temperature": self._settings.llm_temperature,
            "max_tokens": max_tokens,
            "stream": True,
        }
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        return body

    async def _stream_completion(
        self,
        messages,
        *,
        json_mode,
        max_tokens,
        content_limit=64 * 1024,
        request_timeout=None,
        max_wire_bytes=1024 * 1024,
    ):
        body = self._completion_body(messages, json_mode=json_mode, max_tokens=max_tokens)
        options = {} if request_timeout is None else {"timeout": request_timeout}
        stopped, nonempty, content_size = False, False, 0
        try:
            async with self._client.stream(
                "POST", f"{self._settings.llm_base_url}/chat/completions", json=body, **options
            ) as response:
                response.raise_for_status()
                async for payload in _sse_payloads(response, max_wire_bytes=max_wire_bytes):
                    if payload == "[DONE]":
                        if not stopped or not nonempty:
                            raise ValueError("incomplete completion")
                        return
                    chunk = json.loads(payload)
                    if not isinstance(chunk, dict) or "error" in chunk:
                        raise ValueError("invalid upstream event")
                    choices = chunk.get("choices")
                    # 部分供应商在 stop 后追加 usage，不应当成第二个正文片段。
                    if choices == [] and isinstance(chunk.get("usage"), dict):
                        continue
                    if not isinstance(choices, list) or len(choices) != 1 or stopped:
                        raise ValueError("invalid choices")
                    choice = choices[0]
                    if not isinstance(choice, dict) or choice.get("index", 0) != 0:
                        raise ValueError("invalid choice")
                    delta = choice.get("delta")
                    if not isinstance(delta, dict) or set(delta) - {
                        "role",
                        "content",
                        "reasoning_content",
                        "reasoning",
                    }:
                        raise ValueError("invalid delta")
                    # 供应商的推理元数据不属于正文，既不转发也不写入审计原文。
                    for key in ("reasoning_content", "reasoning"):
                        if delta.get(key) is not None and not isinstance(delta[key], str):
                            raise ValueError("invalid reasoning metadata")
                    if "role" in delta and delta["role"] != "assistant":
                        raise ValueError("invalid role")
                    text = delta.get("content")
                    if text is not None and not isinstance(text, str):
                        raise ValueError("invalid content")
                    finish = choice.get("finish_reason")
                    if finish is not None:
                        if finish != "stop":
                            raise ValueError("incomplete completion")
                        stopped = True
                    if text:
                        content_size += len(text.encode("utf-8"))
                        if content_size > content_limit:
                            raise ValueError("completion too large")
                        nonempty = nonempty or bool(text.strip())
                        yield text
                raise ValueError("missing DONE")
        except httpx.TimeoutException as exc:
            raise ServiceError(504, 50401, "LLM upstream timed out") from exc
        except httpx.HTTPError as exc:
            raise ServiceError(502, 50201, "LLM upstream request failed") from exc
        except (ValueError, KeyError, IndexError, TypeError, RecursionError) as exc:
            # 仅允许代码内固定原因进入诊断，不把 JSON 解析错误文本（可能带原文）入日志。
            reason = str(exc)
            allowed = {
                "stream too large",
                "SSE line too large",
                "SSE event too large",
                "unexpected SSE field",
                "truncated SSE event",
                "incomplete completion",
                "invalid upstream event",
                "invalid choices",
                "invalid choice",
                "invalid delta",
                "invalid reasoning metadata",
                "invalid role",
                "invalid content",
                "completion too large",
                "missing DONE",
            }
            logger.warning(
                "LLM stream rejected: reason=%s type=%s",
                reason if reason in allowed else "invalid encoding or JSON",
                type(exc).__name__,
            )
            raise ServiceError(502, 50201, "LLM upstream returned an invalid stream") from exc

    async def stream_chat(self, messages, *, tools, tool_choice="auto"):
        """独立工具流；旧正文流继续拒绝工具调用，失败均不自动重试。"""
        body = self._completion_body(
            messages, json_mode=False, max_tokens=self._settings.explanation_max_tokens
        )
        body.update(tools=tools, tool_choice=tool_choice)
        parser = ToolStream()
        try:
            async with self._client.stream(
                "POST", f"{self._settings.llm_base_url}/chat/completions", json=body
            ) as response:
                if response.status_code in {400, 422}:
                    # 仅接受明确的结构化“不支持工具”错误，不能把所有400当成可重试。
                    raw = bytearray()
                    async for block in response.aiter_bytes():
                        if len(raw) + len(block) > 16384:
                            break
                        raw.extend(block)
                    try:
                        error = json.loads(raw).get("error", {})
                        unsupported = (
                            isinstance(error, dict)
                            and error.get("param") in {"tools", "tool_choice"}
                            and error.get("code") in {"unsupported_parameter", "unsupported_value"}
                        )
                    except (ValueError, AttributeError, TypeError):
                        unsupported = False
                    if unsupported:
                        raise ServiceError(502, 50205, "LLM upstream does not support tools")
                response.raise_for_status()
                async for payload in _sse_payloads(response):
                    if payload == "[DONE]":
                        final = parser.finish()
                        if final:
                            yield final
                        return
                    event = parser.feed(json.loads(payload))
                    if event:
                        yield event
                raise ValueError("missing DONE")
        except httpx.TimeoutException as exc:
            raise ServiceError(504, 50401, "LLM upstream timed out") from exc
        except httpx.HTTPError as exc:
            raise ServiceError(502, 50201, "LLM upstream request failed") from exc
        except (ValueError, KeyError, IndexError, TypeError, RecursionError) as exc:
            raise ServiceError(502, 50204, "LLM upstream returned an invalid tool stream") from exc

    async def aclose(self) -> None:
        await self._client.aclose()
