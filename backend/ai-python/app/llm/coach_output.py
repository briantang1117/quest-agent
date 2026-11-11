"""适配已完整结束的工具模式回答；结构化内容始终严格校验。"""

import json
import logging
import re

from app.llm.json_reply_stream import MAX_JSON_BYTES, JsonReplyStream

logger = logging.getLogger(__name__)


def final_reply_chunks(chunks: list[str]) -> list[str]:
    raw = "".join(chunks)
    if len(raw.encode("utf-8")) > MAX_JSON_BYTES:
        raise ValueError("JSON 过长")
    text = raw.strip()
    kind = "json"
    if text.startswith("```"):
        fence = re.fullmatch(r"```(?:json)?[ \t]*\r?\n(.*?)\r?\n```", text, re.DOTALL | re.I)
        if fence is None:
            raise ValueError("JSON 结构无效")
        text = fence[1].strip()
        if not text.startswith("{"):
            raise ValueError("JSON 结构无效")
        candidate = [text]
        kind = "json_fence"
    elif text.startswith(("{", "[", '"')) or text in {"null", "true", "false"}:
        candidate = chunks
    else:
        # 只有完整自然语言可以包装；混杂/残缺的结构化输出不能冒充正文。
        if (
            not text
            or text.startswith(("`", "~~~"))
            or re.search(r'"(?:reply|suggestions)"\s*:|^\s*(?:reply|suggestions)\s*:', text)
        ):
            raise ValueError("JSON 结构无效")
        candidate = [json.dumps({"reply": text, "suggestions": []}, ensure_ascii=False)]
        kind = "plain_text"
    parser = JsonReplyStream()
    for chunk in candidate:
        parser.feed(chunk)
    parser.finish()
    if kind != "json":
        logger.info("Coach final output adapted: kind=%s output_bytes=%d", kind, len(raw.encode()))
    return candidate
