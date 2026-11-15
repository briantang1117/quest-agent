"""每次供应商请求独立预占/结算；未知用量保持冻结，不能自动记零。"""

import base64
import hashlib
import hmac
import json
import logging
import time
from contextlib import asynccontextmanager
from contextvars import ContextVar
from uuid import uuid4

import httpx

from app.core.errors import ServiceError

meter_context = ContextVar("meter_context", default=None)
logger = logging.getLogger(__name__)


def checked_grant(value, path, settings):
    try:
        encoded, signature = value.split(".")
        expected = hmac.new(
            settings.internal_hmac_secret.get_secret_value().encode(),
            ("meter\n" + encoded).encode(),
            hashlib.sha256,
        ).hexdigest()
        if not hmac.compare_digest(expected, signature):
            raise ValueError()
        claims = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
        if claims["path"] != path or claims["expires"] < time.time():
            raise ValueError()
        return value
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        raise ServiceError(403, 40340, "模型调用缺少有效计量授权") from exc


class MeterCall:
    def __init__(self, settings, grant, model, capability, budget):
        self.settings, self.grant = settings, grant
        self.model, self.capability, self.budget = model, capability, budget
        self.id = str(uuid4())
        self.sent = False
        self.tokens = None
        self.client = None

    async def callback(self, step, data):
        path = "/internal/meter/" + step
        raw = json.dumps(
            {"grant": self.grant, "id": self.id, **data}, separators=(",", ":")
        ).encode()
        stamp, nonce = str(int(time.time())), uuid4().hex
        canonical = "\n".join((stamp, nonce, "POST", path, hashlib.sha256(raw).hexdigest()))
        signature = hmac.new(
            self.settings.internal_hmac_secret.get_secret_value().encode(),
            canonical.encode(),
            hashlib.sha256,
        ).hexdigest()
        try:
            response = await self.client.post(
                path,
                content=raw,
                headers={
                    "Content-Type": "application/json",
                    "X-Timestamp": stamp,
                    "X-Nonce": nonce,
                    "X-Signature": signature,
                    "X-Service-Token": self.settings.internal_service_token.get_secret_value(),
                },
            )
            if len(response.content) > 32768:
                raise ValueError()
            payload = response.json()
            if response.status_code != 200 or payload.get("code") != 0:
                code = payload.get("code")
                if code == 40240:
                    raise ServiceError(402, 40240, "Token余额不足，请联系管理员充值")
                if code == 40341:
                    raise ServiceError(403, 40341, "此功能尚未开放，请联系管理员")
                raise ServiceError(503, 50345, "模型额度服务暂不可用")
        except (httpx.HTTPError, ValueError, TypeError) as exc:
            raise ServiceError(503, 50345, "模型额度服务暂不可用") from exc

    def usage(self, payload):
        value = payload.get("usage") if isinstance(payload, dict) else None
        if not isinstance(value, dict):
            return
        prompt = value.get("prompt_tokens", value.get("input_tokens"))
        completion = value.get("completion_tokens", value.get("output_tokens", 0))
        if type(prompt) is int and type(completion) is int and min(prompt, completion) >= 0:
            self.tokens = (prompt, completion)


@asynccontextmanager
async def meter_call(settings, body, model, capability="text"):
    context = meter_context.get()
    if not settings.ai_metering_enabled or context is None:
        yield None
        return
    grant, root_capability = context
    # 只用于保守预占，不作为实际扣费；按供应商usage结算。
    maximum = body.get("max_tokens", 0)
    budget = (
        0
        if model is None
        else len(json.dumps(body, ensure_ascii=False).encode()) * 2 + maximum + 1024
    )
    meter = MeterCall(
        settings,
        grant,
        model or "non-token:" + capability,
        root_capability if capability == "text" else capability,
        budget,
    )
    async with httpx.AsyncClient(
        base_url=settings.coach_api_url, timeout=10, follow_redirects=False, trust_env=False
    ) as client:
        meter.client = client
        await meter.callback(
            "reserve", {"model": meter.model, "capability": meter.capability, "reservation": budget}
        )
        outcome = "failed"
        try:
            yield meter
            outcome = "ok"
        finally:
            tokens = meter.tokens
            if not meter.sent or model is None:
                tokens = (0, 0)
            try:
                await meter.callback(
                    "settle",
                    {
                        "inputTokens": tokens[0] if tokens else None,
                        "outputTokens": tokens[1] if tokens else None,
                        "outcome": outcome,
                    },
                )
            except Exception as exc:
                # 保留预占，不重试供应商；异常文本可能含响应，不写日志。
                logger.warning("Token结算未确认：call=%s type=%s", meter.id, type(exc).__name__)
