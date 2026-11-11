"""工具循环接口接线与未作答保护。"""

from conftest import settings
from fastapi.testclient import TestClient

from app.main import create_app
from tests.test_coach_explain import QUESTION, StreamProvider, events, send, text_of
from tests.test_coach_tools import FINAL, Provider, Search, call


class ToolProvider(Provider, StreamProvider):
    def __init__(self, rounds):
        Provider.__init__(self, rounds)
        self.legacy_calls = 0

    async def stream_text(self, messages, **kwargs):
        self.legacy_calls += 1
        yield '{"reply":"直接讲解","suggestions":[]}'


class CloseableSearch(Search):
    async def aclose(self):
        pass


def test_enabled_tool_loop_replaces_keyword_retrieval_and_emits_safe_events():
    provider = ToolProvider([[{"type": "tool_calls", "calls": [call()]}], FINAL])
    search = CloseableSearch()
    with TestClient(
        create_app(settings(coach_tools_enabled=True), provider=provider, search=search)
    ) as client:
        response = send(client, {"scope": "student", "message": "解释这个概念"})
    assert search.queries == ["概念依据"]
    assert text_of(response) == "解释"
    names = [name for name, _ in events(response)]
    assert "tool_status" in names
    assert names[-3:] == ["sources", "suggestions", "done"]
    assert provider.legacy_calls == 0


def test_unanswered_question_disables_all_external_search():
    provider = ToolProvider([])
    search = CloseableSearch()
    with TestClient(
        create_app(settings(coach_tools_enabled=True), provider=provider, search=search)
    ) as client:
        response = send(client, {"scope": "student", "message": "查最新答案", "question": QUESTION})
    assert text_of(response) == "直接讲解"
    assert search.queries == []
    assert provider.requests == []


def test_feature_flag_can_be_disabled():
    provider = ToolProvider([])
    with TestClient(create_app(settings(coach_tools_enabled=False), provider=provider)) as client:
        response = send(client, {"scope": "student", "message": "概念"})
    assert text_of(response) == "直接讲解"
    assert provider.requests == []


def test_invalid_final_json_does_not_emit_sources_or_done():
    provider = ToolProvider(
        [
            [{"type": "tool_calls", "calls": [call()]}],
            [{"type": "content", "text": '{"reply":"半截'}],
        ]
    )
    with TestClient(
        create_app(settings(coach_tools_enabled=True), provider=provider, search=CloseableSearch())
    ) as client:
        response = send(client, {"scope": "student", "message": "概念"})
    names = [name for name, _ in events(response)]
    assert names[-1] == "error"
    assert "sources" not in names
    assert "done" not in names


def test_completed_plain_tool_answer_is_delivered_as_reply_not_json_fragments():
    provider = ToolProvider([[{"type": "content", "text": "\n\n## 政策概括\n正文"}]])
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client, {"scope": "student", "message": "说明政策"})
    assert text_of(response) == "## 政策概括\n正文"
    assert events(response)[-1][0] == "done"


def test_completed_fenced_json_tool_answer_only_delivers_inner_reply():
    provider = ToolProvider(
        [
            [
                {"type": "content", "text": chunk}
                for chunk in (
                    "\n\n",
                    "`",
                    '``json\n{"reply":"政策概括","suggestions":[]}\n```',
                )
            ]
        ]
    )
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client, {"scope": "student", "message": "说明政策"})
    assert text_of(response) == "政策概括"
    assert events(response)[-1][0] == "done"
