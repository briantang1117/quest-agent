"""练习轮次的固定业务节点与动态讲解；Java仍是业务事实源。"""

from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import aclosing
from dataclasses import dataclass, field
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph
from langgraph.runtime import Runtime

from app.core.errors import ServiceError


class WorkflowState(TypedDict, total=False):
    action: str
    context: dict
    intent: dict
    kind: str


@dataclass
class WorkflowRuntime:
    # runtime context不会作为图state/configurable写入检查点，授权只在本次调用中存活。
    grant: str = field(repr=False)
    callback: Any = field(repr=False)
    validate_context: Callable[[dict], dict] = field(repr=False)
    route: Callable[[dict], Awaitable[dict]] = field(repr=False)
    explain: Callable[[dict], AsyncIterator[tuple[str, dict]]] = field(repr=False)


def build_coach_workflow(checkpointer):
    async def business(runtime, step, **kwargs):
        data = await runtime.context.callback.step(runtime.context.grant, step, **kwargs)
        return runtime.context.validate_context(data)

    async def prepare(state: WorkflowState, runtime: Runtime[WorkflowRuntime]):
        return {"context": await business(runtime, "context")}

    def dispatch(state: WorkflowState):
        return {"answer": "submit_answer", "next": "next_question", "chat": "route_intent"}[
            state["action"]
        ]

    async def route(state: WorkflowState, runtime: Runtime[WorkflowRuntime]):
        return {"intent": await runtime.context.route(state["context"])}

    def intent_edge(state: WorkflowState):
        return {
            "answer": "suggest_answer",
            "next": "next_question",
            "chat": "explain",
            "clarify": "clarify",
            "material": "create_material",
        }[state["intent"]["action"]]

    def mutation(step):
        async def run(state: WorkflowState, runtime: Runtime[WorkflowRuntime]):
            intent = state.get("intent", {})
            kwargs = {}
            if step in {"suggest_answer", "clarify"}:
                kwargs = {key: intent[key] for key in ("reply", "suggestions")}
            if step == "suggest_answer":
                kwargs["keys"] = intent["keys"]
            context = await business(runtime, step, **kwargs)
            runtime.stream_writer({"event": "workflow_state", "data": {}})
            return {"context": context, "kind": "state_only"}

        return run

    async def explain(state: WorkflowState, runtime: Runtime[WorkflowRuntime]):
        complete = False
        async with aclosing(runtime.context.explain(state["context"])) as stream:
            async for name, data in stream:
                if name == "error":
                    raise ServiceError(502, data.get("code", 50203), "陪练讲解失败")
                if name == "done":
                    complete = True
                else:
                    runtime.stream_writer({"event": name, "data": data})
        if not complete:
            raise ServiceError(502, 50203, "陪练讲解未完成")
        return {"kind": "explain"}

    async def finish(state: WorkflowState, runtime: Runtime[WorkflowRuntime]):
        runtime.stream_writer({"event": "workflow_result", "data": {"kind": state["kind"]}})
        return {}

    graph = StateGraph(WorkflowState, context_schema=WorkflowRuntime)
    graph.add_node("prepare", prepare)
    graph.add_node("route_intent", route)
    for step in ("submit_answer", "next_question", "suggest_answer", "clarify", "create_material"):
        graph.add_node(step, mutation(step))
    graph.add_node("explain", explain)
    graph.add_node("finish", finish)
    graph.add_edge(START, "prepare")
    graph.add_conditional_edges("prepare", dispatch)
    graph.add_conditional_edges("route_intent", intent_edge)
    graph.add_edge("submit_answer", "explain")
    for step in ("next_question", "suggest_answer", "clarify", "create_material", "explain"):
        graph.add_edge(step, "finish")
    graph.add_edge("finish", END)
    return graph.compile(checkpointer=checkpointer)
