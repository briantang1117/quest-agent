"""陪练对话的联网检索增强：可选、有界、失败不影响主回答。"""

import json
from datetime import UTC, datetime

from conftest import encode, settings, signed
from fastapi.testclient import TestClient

from app.agent.coach_search import needs_search, search_context
from app.main import create_app
from app.providers.models import SearchHit, SearchResult

PATH = "/internal/coach-explain"


class Provider:
    def __init__(self, chunks=('{"reply":"讲解正文","suggestions":[]}',)):
        self.chunks = chunks
        self.messages = []

    async def stream_text(self, messages, *, json_mode=False, long_form=False):
        self.messages.append(messages)
        for chunk in self.chunks:
            yield chunk

    async def aclose(self):
        pass


class Search:
    def __init__(self, hits=(), failure=None):
        self.hits = list(hits)
        self.failure = failure
        self.queries = []

    async def aclose(self):
        pass

    async def search(self, query, *, count=5, need_content=False):
        self.queries.append(query)
        if self.failure:
            raise self.failure
        return SearchResult(
            query=query,
            results=tuple(SearchHit(title=t[0], url=t[1], snippet=t[2]) for t in self.hits),
            retrieved_at=datetime.now(UTC),
        )


def send(client, payload):
    body = encode(payload)
    return client.post(PATH, content=body, headers=signed(body, path=PATH))


def events(response):
    out = []
    for block in response.text.split("\n\n"):
        if not block.strip():
            continue
        name, data = "message", ""
        for line in block.splitlines():
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                data = line[5:].strip()
        out.append((name, json.loads(data)))
    return out


def test_timely_questions_are_detected_intent_driven():
    # 需要最新/时效信息时才检索，普通概念题不检索，避免无谓付费与噪音。
    assert needs_search("2026年最新的数据安全法是怎样的")
    assert needs_search("今年事业单位考试政策有什么变化")
    assert needs_search("请查一下最新的司法解释")
    assert not needs_search("财政政策和货币政策有什么区别")
    assert not needs_search("邯郸学步是什么意思")


def test_search_context_is_bounded_and_marked_untrusted():
    sources = [
        ("标题一", "https://example.com/a", "摘要一" * 100),
        ("标题二", "https://example.com/b", None),
    ]
    text = search_context(sources)
    assert "https://example.com/a" in text
    assert "标题二" in text
    # 明确告知模型这是外部未核验资料，不能当指令。
    assert "未核验" in text or "不可信" in text
    # 有界：不会无限拼接。
    assert len(text) <= 6000


def test_search_failure_does_not_break_the_answer():
    provider = Provider()
    search = Search(failure=RuntimeError("upstream down"))
    with TestClient(create_app(settings(), provider=provider, search=search)) as client:
        response = send(
            client,
            {"scope": "student", "subject": "administrative_aptitude", "message": "今年最新政策"},
        )
    # 检索失败仍然给出正常回答。
    assert response.status_code == 200
    names = [name for name, _ in events(response)]
    assert "done" in names


def test_search_hits_are_fed_into_prompt_with_sources():
    provider = Provider()
    search = Search(hits=[("人社部公告", "https://example.com/notice", "公告摘要")])
    with TestClient(create_app(settings(), provider=provider, search=search)) as client:
        send(
            client,
            {
                "scope": "student",
                "subject": "administrative_aptitude",
                "message": "今年最新考试政策",
            },
        )
    prompt = json.dumps(provider.messages[0], ensure_ascii=False)
    assert "https://example.com/notice" in prompt
    assert "不可信" in prompt
    assert search.queries


def test_plain_concept_question_skips_search_entirely():
    provider = Provider()
    search = Search(hits=[("不该出现", "https://example.com/x", "x")])
    with TestClient(create_app(settings(), provider=provider, search=search)) as client:
        send(
            client,
            {"scope": "student", "subject": "administrative_aptitude", "message": "什么是货币政策"},
        )
    assert not search.queries


def test_answer_never_leaks_search_service_error_text():
    provider = Provider()
    search = Search(failure=RuntimeError("SECRET_UPSTREAM_TOKEN"))
    with TestClient(create_app(settings(), provider=provider, search=search)) as client:
        response = send(
            client,
            {"scope": "student", "subject": "administrative_aptitude", "message": "最新政策"},
        )
    assert "SECRET_UPSTREAM_TOKEN" not in response.text


def test_search_hits_are_expanded_instead_of_pydantic_field_tuples():
    # 回归：SearchResult 是模型对象，迭代它会得到字段名/值，不是命中列表。
    provider = Provider()
    search = Search(hits=[("人社部公告", "https://example.com/notice", "公告摘要正文")])
    with TestClient(create_app(settings(), provider=provider, search=search)) as client:
        send(
            client,
            {
                "scope": "student",
                "subject": "administrative_aptitude",
                "message": "今年最新考试政策",
            },
        )
    prompt = json.dumps(provider.messages[0], ensure_ascii=False)
    assert "人社部公告" in prompt
    assert "公告摘要正文" in prompt
    # 不得把 Pydantic 对象 repr 或字段名当成标题/来源塞进提示词。
    assert "SearchHit(" not in prompt
    assert "retrieved_at" not in prompt
