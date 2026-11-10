"""跨语言本地回归替身：真实 Uvicorn/HMAC/coach，模型固定，不读任何真实配置。"""

import json
import sys
from pathlib import Path

import httpx
import uvicorn

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.core.config import Settings  # noqa: E402
from app.llm.openai_compatible import OpenAICompatibleProvider  # noqa: E402
from app.main import create_app  # noqa: E402


def upstream(request):
    """模拟供应商网络响应，但经过真实的 Provider 和 SSE 解析代码。"""
    body = json.loads(request.content)
    if not body.get("stream"):
        content = json.dumps({"action": "chat", "keys": [], "reply": "知识提问", "suggestions": []})
        return httpx.Response(
            200, json={"choices": [{"message": {"content": content}, "finish_reason": "stop"}]}
        )
    lines = []
    for content in ('{"reply":"邯郸学步', '比喻盲目模仿。","suggestions":[]}'):
        data = {
            "choices": [
                {
                    "index": 0,
                    "delta": {
                        "content": content,
                        "reasoning_content": None,
                    },
                    "finish_reason": None,
                }
            ]
        }
        lines.append(f"data: {json.dumps(data, ensure_ascii=False)}\n\n")
    lines.append('data: {"choices":[{"delta":{},"finish_reason":"stop"}]}\n\n')
    lines.append("data: [DONE]\n\n")
    return httpx.Response(200, content="".join(lines).encode())


if __name__ == "__main__":
    config = Settings(
        _env_file=None,
        internal_service_token="test-token-for-internal-service-123456",
        internal_hmac_secret="test-hmac-secret-at-least-32-characters",
    )
    model_config = Settings(
        _env_file=None,
        llm_base_url="https://local-model.invalid/v1",
        llm_api_key="local-test-key",
        llm_model="local-test-model",
    )
    provider = OpenAICompatibleProvider(model_config, transport=httpx.MockTransport(upstream))
    uvicorn.run(
        create_app(config, provider=provider),
        host="127.0.0.1",
        port=int(sys.argv[1]),
        access_log=False,
    )
