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
        '{"reply":"完整"}{"reply":"另一份"}',
        '{"reply":"完整"} 补充：{"reply":"半截',
        '{"reply":"完整"} ]',
        '{"reply":"完整"} reply:另一份',
        '{"reply":"完整"} ```json',
        '{"reply":"甲","reply":"乙"} 普通说明',
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


def test_rejected_tool_output_logs_its_own_position_without_private_content(caplog):
    with pytest.raises(ValueError):
        final_reply_chunks(['{"reply":"PRIVATE_BODY","reply":"PRIVATE_TRAILING"}'])
    assert "Coach final output rejected" in caplog.text
    assert "output_bytes=" in caplog.text
    assert "PRIVATE_BODY" not in caplog.text
    assert "PRIVATE_TRAILING" not in caplog.text


@pytest.mark.parametrize("chunk_size", [1, 3, 1000])
@pytest.mark.parametrize(
    "suffix", ["补充说明：希望对你有帮助。", "\n以上是本题的讲解。", "\nExplanation complete."]
)
def test_complete_unique_object_with_trailing_prose_is_recovered(chunk_size, suffix):
    raw = '{"reply":"正文中有括号}和转义\\n","suggestions":[]}' + suffix
    chunks = [raw[i : i + chunk_size] for i in range(0, len(raw), chunk_size)]
    assert parsed(chunks) == {"reply": "正文中有括号}和转义\n", "suggestions": []}


def test_trailing_prose_recovery_preserves_title_and_memory():
    raw = '{"reply":"讲解","title":"逻辑判断","memory":{"summary":"概念"}}\n希望能帮到你。'
    assert parsed([raw]) == {"reply": "讲解", "title": "逻辑判断", "memory": {"summary": "概念"}}
