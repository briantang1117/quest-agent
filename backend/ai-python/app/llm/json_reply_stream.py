"""只提取顶层 reply 字符串的有界增量解析；完整 JSON 仍须契约验证。"""

import json

MAX_JSON_BYTES = 64 * 1024
MAX_REPLY_CHARS = 6000


def _reject_constant(value):
    raise ValueError("JSON 不允许非有限数字")


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("重复 JSON 字段")
        result[key] = value
    return result


class JsonReplyStream:
    def __init__(self):
        self.raw = ""
        self.size = 0
        self.pos = 0
        self.state = "start"
        self.key = None
        self.reply = ""
        self.decoder = json.JSONDecoder()

    def feed(self, chunk: str) -> str:
        self.size += len(chunk.encode("utf-8"))
        if self.size > MAX_JSON_BYTES:
            raise ValueError("JSON 过长")
        self.raw += chunk
        output = []
        while self.pos < len(self.raw):
            if self.state != "reply":
                while self.pos < len(self.raw) and self.raw[self.pos] in " \t\r\n":
                    self.pos += 1
                if self.pos == len(self.raw):
                    break
            char = self.raw[self.pos]
            if self.state == "start":
                self._expect("{")
                self.state = "key"
            elif self.state == "key":
                if char == "}":
                    self.pos += 1
                    self.state = "end"
                    continue
                try:
                    key, end = self.decoder.raw_decode(self.raw, self.pos)
                except json.JSONDecodeError:
                    break
                if not isinstance(key, str):
                    raise ValueError("JSON 字段名必须是字符串")
                self.key, self.pos, self.state = key, end, "colon"
            elif self.state == "colon":
                self._expect(":")
                self.state = "value"
            elif self.state == "value":
                if self.key == "reply":
                    self._expect('"')
                    self.state = "reply"
                else:
                    try:
                        value, end = self.decoder.raw_decode(self.raw, self.pos)
                    except json.JSONDecodeError:
                        break
                    # 数值等前置值可能尚未结束，必须看到顶层分隔符才提交游标。
                    delimiter = end
                    while delimiter < len(self.raw) and self.raw[delimiter] in " \t\r\n":
                        delimiter += 1
                    if delimiter == len(self.raw):
                        break
                    if self.raw[delimiter] not in ",}":
                        if (
                            isinstance(value, (int, float))
                            and not isinstance(value, bool)
                            and self.raw[delimiter] in ".eE+-0123456789"
                        ):
                            break
                        raise ValueError("JSON 值未结束")
                    self.pos, self.state = end, "after"
            elif self.state == "reply":
                if char == '"':
                    self.pos += 1
                    self.state = "after"
                    continue
                consumed = 1
                if char == "\\":
                    if self.pos + 1 >= len(self.raw):
                        break
                    consumed = 6 if self.raw[self.pos + 1] == "u" else 2
                    if self.pos + consumed > len(self.raw):
                        break
                    if consumed == 6:
                        code = int(self.raw[self.pos + 2 : self.pos + 6], 16)
                        if 0xD800 <= code <= 0xDBFF:
                            consumed = 12
                            if self.pos + consumed > len(self.raw):
                                break
                token = self.raw[self.pos : self.pos + consumed]
                decoded = json.loads('"' + token + '"')
                if any(0xD800 <= ord(value) <= 0xDFFF for value in decoded):
                    raise ValueError("无效 Unicode 代理对")
                self.reply += decoded
                if len(self.reply) > MAX_REPLY_CHARS:
                    raise ValueError("正文过长")
                output.append(decoded)
                self.pos += consumed
            elif self.state == "after":
                if char == ",":
                    self.pos += 1
                    self.state = "key"
                elif char == "}":
                    self.pos += 1
                    self.state = "end"
                else:
                    raise ValueError("JSON 缺少分隔符")
            else:
                raise ValueError("JSON 存在尾随内容")
        return "".join(output)

    def finish(self) -> dict:
        value = json.loads(
            self.raw, object_pairs_hook=_unique_object, parse_constant=_reject_constant
        )
        if not isinstance(value, dict) or self.state != "end":
            raise ValueError("JSON 不完整")
        if value.get("reply") != self.reply or not self.reply.strip():
            raise ValueError("缺少有效正文")
        return value

    def _expect(self, char: str):
        if self.raw[self.pos] != char:
            raise ValueError("JSON 结构无效")
        self.pos += 1
