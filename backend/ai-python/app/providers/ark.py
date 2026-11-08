import base64
import math

import httpx

from app.core.config import Settings
from app.core.errors import ServiceError
from app.providers._base import ToolProvider
from app.providers.models import EmbeddingResult, OCRResult

MAX_IMAGE_BYTES = 10 * 1024 * 1024
MAX_VECTOR_DIMENSIONS = 65536


class ArkOCRProvider(ToolProvider):
    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None):
        super().__init__(
            settings,
            capability="OCR",
            base_field="ocr_api_base_url",
            key_field="ocr_api_key",
            model_field="ocr_model",
            transport=transport,
        )

    async def recognize(self, image: bytes) -> OCRResult:
        """只接收已获授权外发的图片字节，输出原文，不生成位置框。"""
        if not isinstance(image, bytes) or not 0 < len(image) <= MAX_IMAGE_BYTES:
            raise ServiceError(400, 40001, "OCR图片必须为不超过10MiB的PNG/JPEG/WebP字节")
        if image.startswith(b"\x89PNG\r\n\x1a\n"):
            mime = "image/png"
        elif image.startswith(b"\xff\xd8\xff"):
            mime = "image/jpeg"
        elif image.startswith(b"RIFF") and image[8:12] == b"WEBP":
            mime = "image/webp"
        else:
            raise ServiceError(400, 40001, "OCR图片类型校验失败，仅支持PNG/JPEG/WebP")
        image_url = f"data:{mime};base64,{base64.b64encode(image).decode('ascii')}"
        payload = await self._post(
            "/chat/completions",
            {
                "model": self._model,
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "text",
                                "text": (
                                    "请忠实提取图片中的原文，保留题号、选项和原答案；"
                                    "不猜测、不补全。仅返回文本。"
                                ),
                            },
                            {"type": "image_url", "image_url": {"url": image_url}},
                        ],
                    }
                ],
                "temperature": 0,
                "max_tokens": self._settings.llm_max_tokens,
            },
        )
        choices = payload.get("choices")
        if not isinstance(choices, list) or len(choices) != 1 or not isinstance(choices[0], dict):
            raise self._invalid_output()
        choice = choices[0]
        message = choice.get("message")
        if choice.get("finish_reason") != "stop" or not isinstance(message, dict):
            raise self._invalid_output()
        text = message.get("content")
        if not isinstance(text, str) or not text.strip():
            raise self._invalid_output()
        return OCRResult(text=text, model=self._model)


class ArkEmbeddingProvider(ToolProvider):
    def __init__(self, settings: Settings, *, transport: httpx.AsyncBaseTransport | None = None):
        super().__init__(
            settings,
            capability="Embedding",
            base_field="embedding_base_url",
            key_field="embedding_api_key",
            model_field="embedding_model",
            transport=transport,
        )

    async def embed(self, text: str) -> EmbeddingResult:
        """解析实际返回的向量维度；不写入数据库，也不猜测旧索引维度。"""
        if not isinstance(text, str) or not text.strip():
            raise ServiceError(400, 40001, "Embedding输入必须为非空文本")
        payload = await self._post(
            "/embeddings/multimodal",
            {"model": self._model, "input": [{"type": "text", "text": text}]},
        )
        data = payload.get("data")
        vector = data.get("embedding") if isinstance(data, dict) else None
        if not isinstance(vector, list) or not 1 <= len(vector) <= MAX_VECTOR_DIMENSIONS:
            raise self._invalid_output()
        try:
            if any(type(value) not in {int, float} or not math.isfinite(value) for value in vector):
                raise self._invalid_output()
        except (OverflowError, ValueError):
            raise self._invalid_output() from None
        expected = self._settings.embedding_dimensions
        if expected is not None and len(vector) != expected:
            raise ServiceError(502, 50202, "Embedding上游向量维度与配置不一致")
        return EmbeddingResult(
            vector=tuple(float(value) for value in vector),
            dimensions=len(vector),
            model=self._model,
        )
