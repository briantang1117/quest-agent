"""陪练只读工具注册与单轮执行预算，外部结果始终是不可信数据。"""

import asyncio
import hashlib
import json
import logging
import time
from typing import Literal
from urllib.parse import urlsplit, urlunsplit

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

from app.agent.local_time import TimeArguments, current_time
from app.core.errors import ServiceError
from app.providers._urls import public_url_host

logger = logging.getLogger(__name__)
MAX_CALLS = 12
MAX_OBSERVATION_BYTES = 24 * 1024


class SearchArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    query: str = Field(min_length=1, max_length=200)
    recency: Literal["any", "year"] = "any"

    @field_validator("query", mode="before")
    @classmethod
    def clean_query(cls, value):
        return value.strip() if isinstance(value, str) else value


SEARCH_TOOL = {
    "type": "function",
    "function": {
        "name": "search_web",
        "description": (
            "检索公开网页辅助知识答疑；不能用于未作答题目搜答案。时间筛选目前只支持any。"
        ),
        "parameters": SearchArguments.model_json_schema(),
    },
}


class FetchArguments(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    url: str = Field(min_length=1, max_length=2048)

    @field_validator("url")
    @classmethod
    def public_normalized_url(cls, value):
        try:
            host = public_url_host(value)
            parts = urlsplit(value)
            scheme = parts.scheme.lower()
            netloc = f"[{host}]" if ":" in host else host
            if parts.port and parts.port != {"http": 80, "https": 443}[scheme]:
                netloc += f":{parts.port}"
            return urlunsplit((scheme, netloc, parts.path or "/", parts.query, ""))
        except ServiceError as exc:
            raise ValueError("网页地址不安全") from exc


FETCH_TOOL = {
    "type": "function",
    "function": {
        "name": "fetch_page",
        "description": "读取公开HTTP(S)网页正文；可用于阅读搜索命中的来源，不用于查未作答题答案。",
        "parameters": FetchArguments.model_json_schema(),
    },
}


def encode_observation(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


class ToolExecutor:
    def __init__(self, search, *, fetch=None, clock=None, max_calls=MAX_CALLS):
        if not 1 <= max_calls <= 32:
            raise ValueError("工具调用上限必须为1–32")
        self.max_calls = max_calls
        self.search = search
        self.fetch = fetch
        self.clock = clock
        self.cache = {}
        self.count = 0
        self.observation_bytes = 0
        self.sources = []
        self.audits = []

    def _bounded(self, observation):
        # 每次返回都计字节，包括去重缓存和错误；为余下调用预留错误观察空间。
        allowance = (
            MAX_OBSERVATION_BYTES - self.observation_bytes - 128 * (self.max_calls - self.count)
        )
        result = json.loads(encode_observation(observation))
        while len(encode_observation(result).encode("utf-8")) > allowance:
            result["truncated"] = True
            if result.get("text"):
                result["text"] = result["text"][: len(result["text"]) // 2]
                continue
            if "text" in result:
                result = {"error": "observation_budget", "truncated": True}
                continue
            hits = result.get("results", [])
            if not hits:
                result = {"results": [], "truncated": True}
                break
            last = hits[-1]
            if last["snippet"]:
                last["snippet"] = last["snippet"][: len(last["snippet"]) // 2]
            else:
                hits.pop()
        self.observation_bytes += len(encode_observation(result).encode("utf-8"))
        return result

    async def execute(self, call):
        if self.count >= self.max_calls:
            raise ServiceError(502, 50203, "工具调用超过上限")
        self.count += 1
        tool = call["function"]["name"]
        if tool not in {"search_web", "fetch_page", "get_current_time"}:
            return self._bounded({"error": "unknown_tool", "truncated": False})
        try:
            model = {
                "search_web": SearchArguments,
                "fetch_page": FetchArguments,
                "get_current_time": TimeArguments,
            }[tool]
            arguments = model.model_validate_json(call["function"]["arguments"])
        except (ValidationError, ValueError):
            return self._bounded({"error": "invalid_arguments", "truncated": False})
        if tool == "search_web" and arguments.recency != "any":
            return self._bounded({"error": "unsupported_recency", "truncated": False})
        key = tool + ":" + arguments.model_dump_json()
        if key not in self.cache:
            started = time.monotonic()
            category, count = "ok", 0
            result = {"results": [], "truncated": False}
            try:
                async with asyncio.timeout(8):
                    if tool == "search_web":
                        result = await self._search(arguments)
                        count = len(result["results"])
                    elif tool == "fetch_page":
                        result = await self._fetch(arguments)
                        count = 1
                    else:
                        result = current_time(arguments, self.clock)
                        count = 1
            except asyncio.CancelledError:
                category = "cancelled"
                raise
            except TimeoutError:
                category = "timeout"
                result = {"error": category, "results": [], "truncated": False}
            except ServiceError as failure:
                category = "timeout" if failure.status == 504 else "unavailable"
                result = {"error": category, "results": [], "truncated": False}
            except Exception:
                category = "unavailable"
                result = {"error": category, "results": [], "truncated": False}
            finally:
                self.audits.append(
                    {
                        "tool": tool,
                        "parameter_hash": hashlib.sha256(key.encode()).hexdigest(),
                        "result_count": count,
                        "duration_ms": int((time.monotonic() - started) * 1000),
                        "failure": category,
                    }
                )
                logger.info(
                    "ai.tool_%s hash=%s count=%d duration_ms=%d failure=%s",
                    {"search_web": "search", "fetch_page": "fetch", "get_current_time": "time"}[
                        tool
                    ],
                    hashlib.sha256(key.encode()).hexdigest(),
                    count,
                    int((time.monotonic() - started) * 1000),
                    category,
                )
            self.cache[key] = result
        result = self._bounded(self.cache[key])
        known = {(source["tool"], source["url"]) for source in self.sources}
        hits = (
            result.get("results", [])
            if tool == "search_web"
            else ([result] if "url" in result and not result.get("error") else [])
        )
        for hit in hits:
            if (tool, hit["url"]) not in known:
                self.sources.append(
                    {"tool": tool, **{key: hit[key] for key in ("title", "url", "retrieved_at")}}
                )
                known.add((tool, hit["url"]))
        return result

    async def _search(self, arguments):
        if self.search is None:
            raise ServiceError(503, 50302, "检索未配置")
        found = await self.search.search(arguments.query, count=3, need_content=True)
        result = {"results": [], "truncated": False}
        for hit in found.results[:3]:
            if len(hit.url) > 2048:
                result["truncated"] = True
                continue
            try:
                public_url_host(hit.url)
            except ServiceError:
                continue
            snippet = hit.content or hit.snippet or ""
            if len(snippet) > 20000 or len(hit.title) > 200:
                result["truncated"] = True
            result["results"].append(
                {
                    "title": hit.title[:200],
                    "url": hit.url,
                    "snippet": snippet[:20000],
                    "retrieved_at": found.retrieved_at.isoformat(),
                }
            )
        return result

    async def _fetch(self, arguments):
        if self.fetch is None:
            raise ServiceError(503, 50302, "网页抓取未配置")
        found = await self.fetch.fetch(arguments.url)
        public_url_host(found.url)
        if len(found.url) > 2048:
            raise ValueError("网页来源地址过长")
        return {
            "title": found.title[:200],
            "url": found.url,
            "text": found.content[:20000],
            "retrieved_at": found.retrieved_at.isoformat(),
            "truncated": len(found.content) > 20000 or len(found.title) > 200,
        }
