import json

import pytest

from app.llm.json_reply_stream import JsonReplyStream


@pytest.mark.parametrize("prefix", [12345, -1.25e30, True, None, [1, {"reply": "不可外泄"}]])
def test_arbitrary_leading_values_and_field_order_at_every_character(prefix):
    raw = json.dumps({"metadata": prefix, "reply": "正文😀", "suggestions": []})
    parser = JsonReplyStream()
    result = "".join(parser.feed(char) for char in raw)
    assert result == "正文😀"
    assert parser.finish() == json.loads(raw)


@pytest.mark.parametrize(
    "raw",
    [
        '{"reply":"正文",}',
        '{"reply":"正文","metadata":{"x":1,"x":2}}',
        '{"reply":"正文","metadata":NaN}',
    ],
)
def test_malformed_and_ambiguous_json_is_rejected(raw):
    parser = JsonReplyStream()
    with pytest.raises(ValueError):
        parser.feed(raw)
        parser.finish()


def test_raw_json_bound_is_utf8_bytes_not_character_count():
    parser = JsonReplyStream()
    with pytest.raises(ValueError):
        parser.feed(json.dumps({"metadata": "字" * 22000}, ensure_ascii=False))
