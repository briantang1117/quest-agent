import json

from conftest import encode, settings, signed
from fastapi.testclient import TestClient

from app.main import create_app

PATH = "/internal/coach-turn"


class Provider:
    def __init__(self, result):
        self.result = result
        self.messages = []

    async def generate_json(self, messages, *, background=False):
        self.messages.append(messages)
        return json.dumps(self.result, ensure_ascii=False)

    async def aclose(self):
        pass


def send(client, payload):
    body = encode(payload)
    return client.post(PATH, content=body, headers=signed(body, path=PATH))


def test_coach_turn_requires_student_hmac_and_returns_validated_intent():
    provider = Provider({"action": "answer", "keys": ["B"], "reply": "我们先核对你的选择。"})
    with TestClient(create_app(settings(), provider=provider)) as client:
        assert client.post(PATH, json={}).status_code == 401
        assert send(client, {"scope": "admin", "message": "选B"}).status_code == 403
        response = send(
            client,
            {
                "scope": "student",
                "message": "选B",
                "subject": "vocational_aptitude",
                "question": {
                    "stem_md": "合成题",
                    "options": [{"key": "A", "text_md": "甲"}, {"key": "B", "text_md": "乙"}],
                },
            },
        )
        assert response.status_code == 200
        assert response.json()["data"]["action"] == "answer"
        assert response.json()["data"]["keys"] == ["B"]
    assert len(provider.messages) == 1
    system = provider.messages[0][0]["content"]
    assert "不能" in system
    assert "不可信" in system


def test_coach_bounded_retries_invalid_model_output_and_never_echoes_it():
    # 非法 action 属于"输出不合规"，按上限重试，但绝不把原文回显给调用方。
    provider = Provider({"action": "publish", "keys": [], "reply": "PRIVATE_OUTPUT"})
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client, {"scope": "student", "subject": "vocational_aptitude", "message": "继续"}
        )
    assert response.status_code == 502
    assert "PRIVATE_OUTPUT" not in response.text
    assert len(provider.messages) == 3


class FlakyProvider:
    """前 n 次返回截断/非法 JSON，之后返回合法内容。"""

    def __init__(self, valid, bad_times, bad='{"action":"chat","keys":[],"reply":"截'):
        self.valid = valid
        self.bad_times = bad_times
        self.bad = bad
        self.calls = 0

    async def generate_json(self, messages, *, background=False):
        self.calls += 1
        return (
            self.bad if self.calls <= self.bad_times else json.dumps(self.valid, ensure_ascii=False)
        )

    async def aclose(self):
        pass


def test_invalid_model_output_is_retried_up_to_three_times():
    # 用户诉求：偶发坏输出重试 3 次；第 3 次成功应当拿到正常回复。
    provider = FlakyProvider({"action": "chat", "keys": [], "reply": "这就是解析。"}, bad_times=2)
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client, {"scope": "student", "subject": "vocational_aptitude", "message": "讲讲"}
        )
    assert response.status_code == 200
    assert response.json()["data"]["reply"] == "这就是解析。"
    assert provider.calls == 3


def test_retry_stops_after_three_attempts_and_never_echoes_bad_output():
    provider = FlakyProvider({"action": "chat", "keys": [], "reply": "不该出现"}, bad_times=99)
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client, {"scope": "student", "subject": "vocational_aptitude", "message": "讲讲"}
        )
    assert response.status_code == 502
    # 恰好 3 次，不无限重试也不重复计费。
    assert provider.calls == 3
    assert "截" not in response.text


class TimeoutProvider:
    """模拟上游超时：这类错误不该被重试，否则放大延迟与计费。"""

    def __init__(self):
        self.calls = 0

    async def generate_json(self, messages, *, background=False):
        self.calls += 1
        from app.core.errors import ServiceError

        raise ServiceError(504, 50401, "LLM upstream timed out")

    async def aclose(self):
        pass


def test_upstream_timeout_is_not_retried():
    provider = TimeoutProvider()
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client, {"scope": "student", "subject": "vocational_aptitude", "message": "讲讲"}
        )
    assert response.status_code == 504
    # 只调用一次：超时不重试。
    assert provider.calls == 1


def test_suggestions_are_passed_through_and_unknown_actions_rejected():
    provider = Provider(
        {
            "action": "chat",
            "keys": [],
            "reply": "这就是解析。",
            "suggestions": [
                {"action": "next", "label": "继续下一题"},
                {"action": "chat", "label": "再讲讲", "text": "再讲讲这个知识点"},
                {"action": "publish", "label": "发布题目"},
            ],
        }
    )
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client, {"scope": "student", "subject": "vocational_aptitude", "message": "讲讲"}
        )
    assert response.status_code == 200
    got = response.json()["data"]["suggestions"]
    # 未知动作在模型契约层就被拒绝，不进入下游。
    assert [item["action"] for item in got] == ["next", "chat"]
    assert got[1]["text"] == "再讲讲这个知识点"


def test_missing_suggestions_defaults_to_empty_list():
    provider = Provider({"action": "chat", "keys": [], "reply": "好的。"})
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client, {"scope": "student", "subject": "vocational_aptitude", "message": "你好"}
        )
    assert response.status_code == 200
    assert response.json()["data"]["suggestions"] == []


def test_extra_metadata_from_model_is_ignored_not_rejected():
    # 真实故障：模型在 action=chat 时多回了 correct=null，整条回复被 extra=forbid 判死，
    # 用户看到"AI讲解暂时不可用"，但模型其实给了解释。多余字段应当丢弃而不是整单失败。
    provider = Provider(
        {"action": "chat", "keys": [], "reply": "这就是解析。", "correct": None, "answer_keys": []}
    )
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client, {"scope": "student", "subject": "vocational_aptitude", "message": "讲讲"}
        )
    assert response.status_code == 200
    data = response.json()["data"]
    # suggestions 是新增的可选字段，缺省为空列表。
    assert data == {"action": "chat", "keys": [], "reply": "这就是解析。", "suggestions": []}
    assert len(provider.messages) == 1


def test_model_cannot_smuggle_answers_through_extra_fields():
    # 丢多余字段不能变成"偷偷接受答案"：chat 时带的选项仍必须被忽略，且不外泄。
    provider = Provider(
        {"action": "chat", "keys": [], "reply": "先自己想想。", "answer": {"keys": ["C"]}}
    )
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client, {"scope": "student", "subject": "vocational_aptitude", "message": "选哪个"}
        )
    assert response.status_code == 200
    assert "C" not in response.json()["data"]["keys"]


def test_question_contract_rejects_answer_or_explanation_before_submission():
    provider = Provider({"action": "chat", "keys": [], "reply": "你好"})
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client,
            {
                "scope": "student",
                "subject": "vocational_aptitude",
                "message": "解释一下",
                "question": {"stem_md": "题干", "answer": {"keys": ["A"]}},
            },
        )
    assert response.status_code == 422
    assert not provider.messages


def test_coach_uses_authoritative_submitted_result_only_for_feedback():
    provider = Provider({"action": "chat", "keys": [], "reply": "这次选择不正确，我们对照解析。"})
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = send(
            client,
            {
                "scope": "student",
                "subject": "administrative_aptitude",
                "message": "为什么错了",
                "result": {
                    "correct": False,
                    "selected_keys": ["A"],
                    "answer_keys": ["B"],
                    "explanation_md": "合成解析",
                },
            },
        )
    assert response.status_code == 200
    assert "合成解析" in provider.messages[0][1]["content"]


def test_missing_text_model_is_explicitly_unavailable():
    with TestClient(create_app(settings())) as client:
        response = send(
            client, {"scope": "student", "subject": "vocational_aptitude", "message": "你好"}
        )
    assert response.status_code == 503
