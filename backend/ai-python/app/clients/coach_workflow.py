"""固定Java地址的受控业务回调；不跟随跳转、不重试。"""

import hashlib
import hmac
import json
import logging
import time
from uuid import uuid4

import httpx

from app.core.config import Settings
from app.core.errors import ServiceError

PATH = "/internal/coach/workflow-step"
logger = logging.getLogger(__name__)


class CoachWorkflowClient:
    def __init__(self, settings: Settings, *, transport=None):
        self.settings = settings
        self.client = httpx.AsyncClient(
            base_url=settings.coach_api_url,
            timeout=10,
            follow_redirects=False,
            trust_env=False,
            transport=transport,
        )

    async def step(self, grant, step, **kwargs):
        if not self.settings.internal_security_configured:
            raise ServiceError(503, 50301, "内部认证未配置")
        body = json.dumps(
            {"grant": grant, "step": step, **kwargs}, ensure_ascii=False, separators=(",", ":")
        ).encode()
        if len(body) > 16 * 1024:
            raise ServiceError(502, 50203, "业务工具参数过长")
        stamp, nonce = str(int(time.time())), uuid4().hex
        canonical = "\n".join((stamp, nonce, "POST", PATH, hashlib.sha256(body).hexdigest()))
        signature = hmac.new(
            self.settings.internal_hmac_secret.get_secret_value().encode(),
            canonical.encode(),
            hashlib.sha256,
        ).hexdigest()
        headers = {
            "Content-Type": "application/json",
            "X-Timestamp": stamp,
            "X-Nonce": nonce,
            "X-Signature": signature,
            "X-Service-Token": self.settings.internal_service_token.get_secret_value(),
        }
        try:
            async with self.client.stream("POST", PATH, content=body, headers=headers) as response:
                raw = bytearray()
                async for block in response.aiter_bytes():
                    raw.extend(block)
                    if len(raw) > 512 * 1024:
                        raise ServiceError(502, 50203, "业务工具响应过长")
            # 仅记录受控步骤、HTTP状态和数字错误码，不输出响应正文或轮次授权。
            if response.status_code != 200:
                code = None
                try:
                    error = json.loads(raw)
                    if isinstance(error, dict) and type(error.get("code")) is int:
                        code = error["code"]
                except (ValueError, RecursionError):
                    pass
                logger.warning(
                    "Coach callback failed: step=%s status=%d code=%s",
                    step
                    if step
                    in {
                        "context",
                        "submit_answer",
                        "next_question",
                        "suggest_answer",
                        "clarify",
                        "create_material",
                    }
                    else "unknown",
                    response.status_code,
                    code,
                )
                if code == 40341:
                    raise ServiceError(403, 40341, "此功能尚未开放，请联系管理员")
                raise ServiceError(502, 50203, "业务工具调用失败")
            result = json.loads(raw)
            if (
                not isinstance(result, dict)
                or result.get("code") != 0
                or not isinstance(result.get("data"), dict)
            ):
                raise ValueError("invalid envelope")
            context = result["data"]["context"]
            if not isinstance(context, dict):
                raise ValueError("invalid context")
            return context
        except (httpx.HTTPError, ValueError, TypeError, KeyError, RecursionError) as exc:
            raise ServiceError(502, 50203, "业务工具调用失败") from exc

    async def aclose(self):
        await self.client.aclose()
