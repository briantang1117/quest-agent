"""工具模式最终回答兼容；绝不把残缺JSON当正文。"""

import pytest

from app.llm.coach_output import final_reply_chunks
from app.llm.json_reply_stream import JsonReplyStream


def parsed(chunks):
    parser = JsonReplyStream()
    for chunk in final_reply_chunks(chunks):
        parser.feed(chunk)
    return parser.finish()


def test_complete_json_fence_split_at_first_backtick_is_unwrapped():
    # 对应线上先收到两字节空白、再收到一字节反引号时立即失败的轨迹。
    assert parsed(["\n\n", "`", "``json\n", '{"reply":"政策概括","suggestions":[]}', "\n```"]) == {
        "reply": "政策概括",
        "suggestions": [],
    }


def test_complete_natural_language_is_wrapped_without_another_model_call():
    assert parsed(["\n\n", "#", "# 政策要点\n", "先说明时间范围。"]) == {
        "reply": "## 政策要点\n先说明时间范围。",
        "suggestions": [],
    }


def test_valid_json_chunks_remain_unchanged():
    chunks = ['{"reply":', '"讲解","suggestions":[]}']
    assert final_reply_chunks(chunks) == chunks


@pytest.mark.parametrize(
    "text",
    [
        '{"reply":"半截',
        '```json\n{"reply":"半截\n```',
        '```json\n{"reply":"完整"}',
        '{"reply":"完整"} trailing',
        '[{"reply":"列表不符合契约"}]',
        '{"reply":"甲","reply":"乙"}',
        '下面是结果：{"reply":"不能作为正文展示JSON"}',
        "   ",
    ],
)
def test_incomplete_or_ambiguous_json_never_becomes_plain_reply(text):
    with pytest.raises(ValueError):
        final_reply_chunks([text])


def test_plain_reply_keeps_existing_length_limit():
    with pytest.raises(ValueError):
        final_reply_chunks(["文" * 6001])
