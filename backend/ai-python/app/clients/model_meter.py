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
        response = None
        safe_step = step if step in {"reserve", "settle"} else "unknown"
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
            if not isinstance(payload, dict):
                raise ValueError("invalid envelope")
            code = payload.get("code")
            if response.status_code != 200 or type(code) is not int or code != 0:
                logger.warning(
                    "Meter callback failed: step=%s status=%d code=%s",
                    safe_step,
                    response.status_code,
                    code if type(code) is int and 0 <= code <= 99999 else None,
                )
                if code == 40240:
                    raise ServiceError(402, 40240, "Token 余额不足，请联系管理员充值后重试。")
                if code == 40341:
                    raise ServiceError(403, 40341, "此功能尚未开放，请联系管理员")
                raise ServiceError(503, 50345, "模型额度服务暂不可用")
        except (httpx.HTTPError, ValueError, TypeError) as exc:
            logger.warning(
                "Meter callback failed: step=%s status=%s reason=%s type=%s",
                safe_step,
                response.status_code if response is not None else None,
                "transport_error" if isinstance(exc, httpx.HTTPError) else "invalid_response",
                type(exc).__name__,
            )
            raise ServiceError(503, 50345, "模型额度服务暂不可用") from exc

    def usage(self, payload):
        value = payload.get("usage") if isinstance(payload, dict) else None
        if not isinstance(value, dict):
            return
        prompt = value.get("prompt_tokens", value.get("input_tokens"))
        completion = value.get("completion_tokens", value.get("output_tokens", 0))
        if type(prompt) is int and type(completion) is int and min(prompt, completion) >= 0:
            self.tokens = (prompt, completion)


def estimate_reservation(settings, body, model, capability="text"):
    if model is None:
        return 0
    images = 0

    def estimate_data(value):
        nonlocal images
        if isinstance(value, dict):
            image = value.get("image_url")
            if (
                capability == "OCR"
                and value.get("type") == "image_url"
                and isinstance(image, dict)
                and str(image.get("url", "")).startswith("data:image/")
                and ";base64," in image["url"]
            ):
                images += 1
                return {**value, "image_url": {**image, "url": "inline-image"}}
            return {key: estimate_data(item) for key, item in value.items()}
        if isinstance(value, list):
            return [estimate_data(item) for item in value]
        return value

    text_bytes = len(json.dumps(estimate_data(body), ensure_ascii=False).encode())
    return (
        text_bytes * 2
        + body.get("max_tokens", 0)
        + 1024
        + images * settings.ocr_image_reservation_tokens
    )


@asynccontextmanager
async def meter_call(settings, body, model, capability="text"):
    context = meter_context.get()
    if not settings.ai_metering_enabled or context is None:
        yield None
        return
    grant, root_capability = context
    # 只用于保守预占，不作为实际扣费；按供应商usage结算。
    budget = estimate_reservation(settings, body, model, capability)
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
