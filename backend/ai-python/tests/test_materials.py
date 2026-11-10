import copy
import json

import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient

from app.core.errors import ServiceError
from app.main import create_app

PATH = "/internal/material-generate"


class MaterialProvider:
    def __init__(self, output=None, failure=None):
        self.output = (
            output
            if output is not None
            else encode(
                {"title": "逻辑复习", "markdown": "# 逻辑复习\n\n## AI归纳\n梳理概念。"}
            ).decode()
        )
        self.failure = failure
        self.messages = []

    async def generate_material(self, messages):
        self.messages.append(messages)
        if self.failure:
            raise self.failure
        return self.output

    async def aclose(self):
        pass


def payload():
    return {
        "scope": "student",
        "subject": "vocational_aptitude",
        "source_title": "逻辑讨论",
        "instructions": "重点整理推理方法",
        "messages": [
            {"id": "m1", "role": "user", "text": "解释充分条件"},
            {
                "id": "m2",
                "role": "assistant",
                "text": "先练一题。",
                "question": {
                    "id": "q1",
                    "type": "single_choice",
                    "stemMd": "以下哪项正确？",
                    "options": [{"key": "A", "textMd": "甲"}, {"key": "B", "textMd": "乙"}],
                },
            },
        ],
    }


def send(client, body=None, *, headers=None):
    raw = encode(payload() if body is None else body)
    return client.post(PATH, content=raw, headers=headers or signed(raw, path=PATH))


def test_material_hmac_scope_replay_tampering_and_queries():
    provider = MaterialProvider()
    raw = encode(payload())
    headers = signed(raw, path=PATH)
    with TestClient(create_app(settings(), provider=provider)) as client:
        assert client.post(PATH, content=raw).status_code == 401
        assert send(client, {**payload(), "scope": "admin"}).status_code == 403
        assert client.post(PATH, content=raw + b" ", headers=headers).status_code == 401
        assert client.post(PATH, content=raw, headers=headers).status_code == 200
        assert client.post(PATH, content=raw, headers=headers).status_code == 401
        assert (
            client.post(PATH + "?x=1", content=raw, headers=signed(raw, path=PATH)).status_code
            == 400
        )
    assert len(provider.messages) == 1


def test_material_returns_title_markdown_and_only_visible_camelcase_content():
    provider = MaterialProvider()
    data = payload()
    data["messages"][1]["result"] = {
        "correct": False,
        "selectedKeys": ["B"],
        "answerKeys": ["A"],
        "explanationMd": "甲正确。",
    }
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client, data)
    assert response.status_code == 200
    assert response.json()["data"] == json.loads(provider.output)
    assert response.json()["traceId"] == response.headers["X-Trace-Id"]
    assert len(provider.messages) == 1
    system, user = provider.messages[0]
    assert json.loads(user["content"])["messages"] == data["messages"]
    for term in (
        "不可信",
        "未作答",
        "原文",
        "AI归纳",
        "待核实",
        "不编新题",
        "Java",
        "重复",
        "纠正",
    ):
        assert term in system["content"]
    assert "流水账" in system["content"]


def test_long_context_is_not_trimmed_to_chat_limits_or_last_100_messages():
    provider = MaterialProvider()
    data = payload()
    data["messages"] = [
        {"id": str(i), "role": "user", "text": f"第{i}条" + "文" * 7000} for i in range(120)
    ]
    with TestClient(create_app(settings(), provider=provider)) as client:
        assert send(client, data).status_code == 200
    forwarded = json.loads(provider.messages[0][1]["content"])
    assert forwarded["messages"] == data["messages"]
    assert "answerKeys" not in provider.messages[0][1]["content"]


@pytest.mark.parametrize(
    "change",
    [
        lambda p: p.update(snapshot={"answer": "SECRET"}),
        lambda p: p["messages"][1].update(snapshot={"answer": "SECRET"}),
        lambda p: p["messages"][1]["question"].update(answer={"keys": ["A"]}),
        lambda p: p["messages"][1]["question"].update(stem_md="SECRET"),
        lambda p: p["messages"][1]["question"]["options"][0].update(text_md="SECRET"),
        lambda p: p["messages"][0].update(role="system"),
        lambda p: p["messages"][0].update(text=123),
        lambda p: p["messages"][1]["question"].update(options=[{"key": "A", "textMd": "甲"}] * 2),
        lambda p: p["messages"][1].update(
            result={
                "correct": True,
                "selected_keys": ["A"],
                "answer_keys": ["A"],
                "explanation_md": "SECRET",
            }
        ),
        lambda p: p["messages"][0].update(
            result={
                "correct": True,
                "selectedKeys": ["A"],
                "answerKeys": ["A"],
                "explanationMd": "SECRET",
            }
        ),
        lambda p: p["messages"][1].update(
            result={
                "correct": True,
                "selectedKeys": ["B"],
                "answerKeys": ["A"],
                "explanationMd": "SECRET",
            }
        ),
        lambda p: p["messages"][1].update(
            result={
                "correct": False,
                "selectedKeys": ["B"],
                "answerKeys": ["H"],
                "explanationMd": "SECRET",
            }
        ),
        lambda p: p.update(messages=[]),
    ],
)
def test_private_or_invalid_projection_is_rejected_before_provider(change):
    provider = MaterialProvider()
    data = copy.deepcopy(payload())
    change(data)
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client, data)
    assert response.status_code == 422
    assert "SECRET" not in response.text
    assert not provider.messages


def test_material_input_character_budget_is_configurable_and_not_silently_trimmed():
    provider = MaterialProvider()
    data = payload()
    data["messages"][0]["text"] = "长" * 1001
    with TestClient(
        create_app(settings(material_max_input_chars=1000), provider=provider)
    ) as client:
        assert send(client, data).status_code == 422
    assert not provider.messages


def test_material_raw_body_budget_is_bounded_even_for_chunked_requests():
    provider = MaterialProvider()
    raw = encode(payload()) + b" " * 2000
    with TestClient(
        create_app(settings(material_max_input_bytes=1024), provider=provider)
    ) as client:
        response = client.post(
            PATH, content=iter([raw[:500], raw[500:]]), headers=signed(raw, path=PATH)
        )
    assert response.status_code == 413
    assert not provider.messages


@pytest.mark.parametrize(
    "output",
    [
        "not-json SECRET",
        '{"title":"半截","markdown":"SECRET',
        encode({"title": " ", "markdown": "SECRET"}).decode(),
        encode({"title": "复习", "markdown": "  "}).decode(),
        encode({"title": "复习", "markdown": "SECRET", "saved": True}).decode(),
        encode({"title": "复习", "markdown": "文" * 100001}).decode(),
    ],
)
def test_invalid_material_output_fails_safely_without_retry(output, caplog):
    provider = MaterialProvider(output=output)
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client)
    assert response.status_code == 502
    assert "SECRET" not in response.text + caplog.text
    assert len(provider.messages) == 1


def test_material_allows_full_100000_character_markdown():
    provider = MaterialProvider(
        output=encode({"title": "复习", "markdown": "文" * 100000}).decode()
    )
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(client)
    assert response.status_code == 200
    assert len(response.json()["data"]["markdown"]) == 100000


def test_missing_provider_and_upstream_timeout():
    with TestClient(create_app(settings())) as client:
        assert send(client).status_code == 503
    provider = MaterialProvider(failure=ServiceError(504, 50401, "超时"))
    with TestClient(create_app(settings(), provider=provider)) as client:
        assert send(client).status_code == 504
    assert len(provider.messages) == 1


@pytest.mark.parametrize("path", ["/internal/coach-route", "/internal/coach-turn"])
def test_material_intent_is_accepted_with_one_call_and_no_explanation(path):
    from test_coach_route import RouteProvider

    provider = RouteProvider({"action": "material", "keys": [], "reply": "整理本次学习内容。"})
    raw = encode({"scope": "student", "message": "把本次对话整理成复习材料"})
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = client.post(path, content=raw, headers=signed(raw, path=path))
    assert response.status_code == 200
    assert response.json()["data"]["action"] == "material"
    assert len(provider.messages) == 1
    prompt = provider.messages[0][0]["content"]
    for term in ("material", "否定", "询问", "引用", "Java", "不生成完整讲解"):
        assert term in prompt


@pytest.mark.parametrize("path", ["/internal/coach-route", "/internal/coach-turn"])
def test_material_intent_does_not_accept_answer_keys(path):
    from test_coach_route import RouteProvider

    provider = RouteProvider({"action": "material", "keys": ["A"], "reply": "整理"})
    raw = encode({"scope": "student", "message": "整理学习资料"})
    with TestClient(create_app(settings(), provider=provider)) as client:
        assert client.post(path, content=raw, headers=signed(raw, path=path)).status_code == 502
