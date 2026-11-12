import json
from uuid import uuid4

from conftest import encode, settings, signed
from fastapi.testclient import TestClient

from app.core.errors import ServiceError
from app.main import create_app

PATH = "/internal/coach-workflow"


def context(result=False):
    value = {
        "scope": "student",
        "message": "我选A",
        "question": {
            "stem_md": "题目",
            "options": [{"key": "A", "text_md": "甲"}, {"key": "B", "text_md": "乙"}],
        },
    }
    if result:
        value["result"] = {
            "correct": True,
            "selected_keys": ["A"],
            "answer_keys": ["A"],
            "explanation_md": "权威解析",
        }
    return value


class Provider:
    def __init__(self, action="chat"):
        self.action = action
        self.routes = 0
        self.explanations = 0
        self.messages = []

    async def generate_json(self, messages):
        self.routes += 1
        self.messages.extend(messages)
        return json.dumps(
            {
                "action": self.action,
                "keys": ["A"] if self.action == "answer" else [],
                "reply": "请确认",
                "suggestions": [],
            }
        )

    async def stream_text(self, messages, **kwargs):
        self.explanations += 1
        self.messages.extend(messages)
        yield '{"reply":"解析内容","suggestions":[]}'

    async def aclose(self):
        pass


class Business:
    def __init__(self, fail=None):
        self.steps = []
        self.fail = fail

    async def step(self, grant, step, **kwargs):
        self.steps.append((grant, step, kwargs))
        if self.fail == step:
            raise ServiceError(502, 50203, "内部工具失败")
        return context(result=step == "submit_answer")

    async def aclose(self):
        pass


def submit(client, action="answer", workflow_id=None):
    body = encode(
        {
            "scope": "student",
            "workflow_id": workflow_id or f"{uuid4()}:{uuid4()}",
            "action": action,
            "grant": "secret-grant",
            "context": context(),
        }
    )
    return client.post(PATH, content=body, headers=signed(body, path=PATH))


def test_confirmed_answer_uses_fixed_grading_node_without_intent_model():
    provider, business = Provider(), Business()
    with TestClient(create_app(settings(), provider=provider)) as client:
        client.app.state.coach_workflow_client = business
        response = submit(client)
    assert response.status_code == 200
    assert [step for _, step, _ in business.steps] == ["context", "submit_answer"]
    assert provider.routes == 0
    assert provider.explanations == 1
    assert "event: workflow_state" in response.text
    assert "event: workflow_result" in response.text
    assert "event: done" in response.text
    assert "secret-grant" not in json.dumps(provider.messages)
    assert "权威解析" in json.dumps(provider.messages, ensure_ascii=False)


def test_text_answer_only_suggests_and_waits_for_confirmation():
    provider, business = Provider("answer"), Business()
    with TestClient(create_app(settings(), provider=provider)) as client:
        client.app.state.coach_workflow_client = business
        response = submit(client, "chat")
    assert [step for _, step, _ in business.steps] == ["context", "suggest_answer"]
    assert business.steps[-1][2]["keys"] == ["A"]
    assert provider.explanations == 0
    assert "state_only" in response.text
    assert "event: done" in response.text


def test_callback_failure_does_not_generate_explanation_or_retry():
    provider, business = Provider(), Business("submit_answer")
    with TestClient(create_app(settings(), provider=provider)) as client:
        client.app.state.coach_workflow_client = business
        response = submit(client)
    assert [step for _, step, _ in business.steps] == ["context", "submit_answer"]
    assert provider.explanations == 0
    assert "event: error" in response.text
    assert "event: done" not in response.text


def test_same_workflow_cannot_replay_paid_nodes():
    provider, business = Provider(), Business()
    workflow_id = f"{uuid4()}:{uuid4()}"
    with TestClient(create_app(settings(), provider=provider)) as client:
        client.app.state.coach_workflow_client = business
        assert "event: done" in submit(client, workflow_id=workflow_id).text
        response = submit(client, workflow_id=workflow_id)
    assert response.status_code == 409
    assert provider.explanations == 1


def test_next_question_has_no_model_call():
    provider, business = Provider(), Business()
    with TestClient(create_app(settings(), provider=provider)) as client:
        client.app.state.coach_workflow_client = business
        response = submit(client, "next")
    assert [step for _, step, _ in business.steps] == ["context", "next_question"]
    assert provider.routes == provider.explanations == 0
    assert "state_only" in response.text


def test_checkpoint_survives_reopen_without_persisting_grant(tmp_path):
    import sqlite3

    path = tmp_path / "private" / "checkpoints.sqlite"
    workflow_id = f"{uuid4()}:{uuid4()}"
    provider, business = Provider(), Business()
    for attempt in range(2):
        with TestClient(
            create_app(settings(coach_checkpoint_path=str(path)), provider=provider)
        ) as client:
            client.app.state.coach_workflow_client = business
            response = submit(client, workflow_id=workflow_id)
            if attempt == 0:
                assert "event: done" in response.text
                snapshot = client.portal.call(
                    client.app.state.coach_workflow_graph.aget_state,
                    {"configurable": {"thread_id": workflow_id}},
                )
                assert snapshot.values["kind"] == "explain"
                assert snapshot.values["context"]["result"]["correct"] is True
            else:
                assert response.status_code == 409
    assert provider.explanations == 1
    assert path.stat().st_mode & 0o777 == 0o600
    assert path.parent.stat().st_mode & 0o777 == 0o700
    with sqlite3.connect(path) as db:
        for table in ("checkpoints", "writes"):
            for row in db.execute(f"SELECT * FROM {table}"):
                assert "secret-grant" not in repr(row)


def test_confirmed_next_works_without_text_model():
    business = Business()
    with TestClient(create_app(settings())) as client:
        client.app.state.coach_workflow_client = business
        response = submit(client, "next")
    assert response.status_code == 200
    assert "event: done" in response.text
    assert [step for _, step, _ in business.steps] == ["context", "next_question"]


def test_failed_workflow_is_not_automatically_resumed():
    business = Business("submit_answer")
    workflow_id = f"{uuid4()}:{uuid4()}"
    with TestClient(create_app(settings(), provider=Provider())) as client:
        client.app.state.coach_workflow_client = business
        assert "event: error" in submit(client, workflow_id=workflow_id).text
        assert submit(client, workflow_id=workflow_id).status_code == 409
    assert len(business.steps) == 2


def test_cancelling_graph_closes_upstream_and_never_finishes():
    import asyncio

    from langgraph.checkpoint.memory import InMemorySaver

    from app.agent.coach_workflow import WorkflowRuntime, build_coach_workflow

    async def run():
        closed = asyncio.Event()
        started = asyncio.Event()
        never = asyncio.Event()
        calls = []

        async def explain(data):
            calls.append(data)
            try:
                started.set()
                yield "delta", {"text": "部分正文"}
                await never.wait()
            finally:
                closed.set()

        async def route(data):
            raise AssertionError("明确提交不能调用意图模型")

        graph = build_coach_workflow(InMemorySaver())
        runtime = WorkflowRuntime("grant", Business(), lambda x: x, route, explain)
        events = []

        async def consume():
            async for event in graph.astream(
                {"action": "answer"},
                {"configurable": {"thread_id": "cancelled"}},
                context=runtime,
                stream_mode="custom",
            ):
                events.append(event)

        task = asyncio.create_task(consume())
        await asyncio.wait_for(started.wait(), 2)
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
        await asyncio.wait_for(closed.wait(), 2)
        assert len(calls) == 1
        assert not any(event["event"] == "workflow_result" for event in events)

    asyncio.run(run())


def test_workflow_preserves_agent_tool_events_and_validated_sources():
    from tests.test_coach_tool_explain import CloseableSearch, ToolProvider
    from tests.test_coach_tools import FINAL, call

    provider = ToolProvider([[{"type": "tool_calls", "calls": [call()]}], FINAL])
    business = Business()
    with TestClient(create_app(settings(), provider=provider, search=CloseableSearch())) as client:
        client.app.state.coach_workflow_client = business
        response = submit(client)
    assert "event: tool_status" in response.text
    assert "event: tool_audit" in response.text
    assert "event: sources" in response.text
    assert "event: workflow_result" in response.text
    assert response.text.index("event: sources") < response.text.index("event: workflow_result")
    assert "event: done" in response.text


def test_workflow_invalid_explanation_has_no_success_terminal():
    class BrokenProvider(Provider):
        async def stream_text(self, messages, **kwargs):
            self.explanations += 1
            yield '{"reply":"未完成'

    provider, business = BrokenProvider(), Business()
    with TestClient(create_app(settings(), provider=provider)) as client:
        client.app.state.coach_workflow_client = business
        response = submit(client)
    assert provider.explanations == 1
    assert "event: workflow_state" in response.text
    assert "event: error" in response.text
    assert "event: workflow_result" not in response.text
    assert "event: done" not in response.text
