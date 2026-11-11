"""工具流协议边界：参数仅在完整结束后解析，不输出参数片段。"""

import json


class ToolStream:
    def __init__(self):
        self.calls = {}
        self.finish_reason = None
        self.content_size = 0
        self.argument_size = 0
        self.nonempty = False

    def feed(self, chunk):
        if not isinstance(chunk, dict) or "error" in chunk:
            raise ValueError("invalid event")
        choices = chunk.get("choices")
        if choices == [] and isinstance(chunk.get("usage"), dict):
            return None
        if not isinstance(choices, list) or len(choices) != 1 or self.finish_reason:
            raise ValueError("invalid choices")
        choice = choices[0]
        if not isinstance(choice, dict) or choice.get("index", 0) != 0:
            raise ValueError("invalid choice")
        delta = choice.get("delta")
        if not isinstance(delta, dict) or set(delta) - {
            "role",
            "content",
            "tool_calls",
            "reasoning",
            "reasoning_content",
        }:
            raise ValueError("invalid delta")
        if "role" in delta and delta["role"] != "assistant":
            raise ValueError("invalid role")
        for key in ("content", "reasoning", "reasoning_content"):
            if delta.get(key) is not None and not isinstance(delta[key], str):
                raise ValueError("invalid text")
        text = delta.get("content") or ""
        self.content_size += len(text.encode("utf-8"))
        self.nonempty |= bool(text.strip())
        if self.content_size > 64 * 1024:
            raise ValueError("content too large")
        calls = delta.get("tool_calls") or []
        if not isinstance(calls, list):
            raise ValueError("invalid calls")
        for part in calls:
            if not isinstance(part, dict):
                raise ValueError("invalid call")
            index = part.get("index")
            if type(index) is not int or not 0 <= index < 6:
                raise ValueError("invalid call index")
            call = self.calls.setdefault(
                index, {"id": "", "type": "function", "function": {"name": "", "arguments": ""}}
            )
            if part.get("type", "function") != "function":
                raise ValueError("invalid tool type")
            if "id" in part:
                if not isinstance(part["id"], str) or not part["id"] or call["id"]:
                    raise ValueError("invalid call id")
                call["id"] = part["id"]
            function = part.get("function", {})
            if not isinstance(function, dict) or set(function) - {"name", "arguments"}:
                raise ValueError("invalid function")
            for key, value in function.items():
                if not isinstance(value, str):
                    raise ValueError("invalid function fragment")
                self.argument_size += len(value.encode("utf-8"))
                if self.argument_size > 64 * 1024:
                    raise ValueError("arguments too large")
                call["function"][key] += value
            if len(call["id"]) > 200 or len(call["function"]["name"]) > 100:
                raise ValueError("tool identifier too large")
        finish = choice.get("finish_reason")
        if finish is not None:
            if finish not in {"stop", "tool_calls"}:
                raise ValueError("incomplete completion")
            self.finish_reason = finish
        return {"type": "content", "text": text} if text else None

    def finish(self):
        if self.finish_reason == "stop" and self.nonempty and not self.calls:
            return None
        if self.finish_reason != "tool_calls" or not self.calls:
            raise ValueError("incomplete completion")
        if sorted(self.calls) != list(range(len(self.calls))):
            raise ValueError("missing call index")
        calls = list(self.calls.values())
        ids = [call["id"] for call in calls]
        if len(set(ids)) != len(ids):
            raise ValueError("duplicate call id")
        for call in calls:
            if not call["id"] or not call["function"]["name"]:
                raise ValueError("incomplete tool")
            if not isinstance(json.loads(call["function"]["arguments"]), dict):
                raise ValueError("invalid arguments")
        return {"type": "tool_calls", "calls": calls}
