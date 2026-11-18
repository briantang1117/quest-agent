"""适配已完整结束的工具模式回答；结构化内容始终严格校验。"""

import json
import logging
import re

from app.llm.json_reply_stream import MAX_JSON_BYTES, JsonReplyStream

logger = logging.getLogger(__name__)


def _without_trailing_prose(text: str) -> tuple[str, bool]:
    """只接纳首个完整对象后的普通说明；第二份/残缺结构仍视为歧义。"""
    _, end = json.JSONDecoder().raw_decode(text)
    suffix = text[end:].strip()
    if not suffix:
        return text, False
    if any(char in suffix for char in "{}[]`") or re.search(
        r'["\']?(?:reply|suggestions|memory|title)["\']?\s*:', suffix, re.I
    ):
        raise ValueError("JSON 存在尾随内容")
    return text[:end], True


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
        text, trimmed = _without_trailing_prose(text)
        candidate = [text]
        kind = "json_fence_trailing_prose" if trimmed else "json_fence"
    elif text.startswith("{"):
        text, trimmed = _without_trailing_prose(text)
        candidate = [text] if trimmed else chunks
        kind = "json_trailing_prose" if trimmed else "json"
    elif text.startswith(("[", '"')) or text in {"null", "true", "false"}:
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
    try:
        for chunk in candidate:
            parser.feed(chunk)
        parser.finish()
    except ValueError:
        # 工具回答先在这里校验，外层解析器尚为空；只记录本层结构，不能泄露题目或正文。
        logger.warning(
            "Coach final output rejected: kind=%s output_bytes=%d parser_state=%s parser_pos=%d",
            kind,
            len(raw.encode("utf-8")),
            parser.state,
            parser.pos,
        )
        raise
    if kind != "json":
        logger.info("Coach final output adapted: kind=%s output_bytes=%d", kind, len(raw.encode()))
    return candidate
