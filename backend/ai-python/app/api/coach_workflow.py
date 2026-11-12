"""Java认领轮次后调用的LangGraph入口，不接受浏览器权限或任意工具地址。"""

import asyncio
from contextlib import aclosing
from typing import Literal

from fastapi import APIRouter, Request
from fastapi.responses import StreamingResponse
from pydantic import Field, field_validator

from app.agent.coach_workflow import WorkflowRuntime
from app.api.coach import (
    CoachRequest,
    Contract,
    _diagnose,
    _event,
    explanation_events,
    route_intent,
)
from app.core.errors import ServiceError

router = APIRouter()


class WorkflowRequest(Contract):
    scope: Literal["student"]
    workflow_id: str = Field(pattern=r"^[0-9a-f-]{36}:[0-9a-f-]{36}$")
    action: Literal["answer", "next", "chat"]
    grant: str = Field(min_length=1, max_length=10000, repr=False, exclude=True)
    context: CoachRequest

    @field_validator("context")
    @classmethod
    def student_only(cls, value):
        if value.scope != "student":
            raise ValueError("仅允许学生上下文")
        return value


def validated_context(data):
    value = CoachRequest.model_validate(data)
    if value.scope != "student":
        raise ServiceError(502, 50203, "业务工具返回无效权限")
    return value.model_dump(exclude_none=True)


@router.post("/internal/coach-workflow")
async def coach_workflow(payload: WorkflowRequest, request: Request):
    graph = request.app.state.coach_workflow_graph
    config = {"configurable": {"thread_id": payload.workflow_id}, "recursion_limit": 12}
    # 锁只保护本机同一轮次的认领；Java仍负责全局业务幂等和活跃轮次校验。
    async with request.app.state.coach_workflow_claim_lock:
        state = await graph.aget_state(config)
        active = request.app.state.coach_workflow_active
        if state.values or payload.workflow_id in active:
            raise ServiceError(409, 40901, "该编排轮次已执行，请由Java读取已保存结果")
        active.add(payload.workflow_id)

    async def route(data):
        return await route_intent(CoachRequest.model_validate(data), request)

    def explain(data):
        return explanation_events(CoachRequest.model_validate(data), request)

    runtime = WorkflowRuntime(
        grant=payload.grant,
        callback=request.app.state.coach_workflow_client,
        validate_context=validated_context,
        route=route,
        explain=explain,
    )

    async def events():
        try:
            async with (
                asyncio.timeout(130),
                aclosing(
                    graph.astream(
                        {"action": payload.action}, config, context=runtime, stream_mode="custom"
                    )
                ) as stream,
            ):
                async for event in stream:
                    yield _event(event["event"], event["data"])
            yield _event("done", {})
        except ServiceError as exc:
            _diagnose("coach_workflow", exc, request)
            yield _event("error", {"code": exc.code})
        except (ValueError, TypeError, RecursionError, TimeoutError) as exc:
            _diagnose("coach_workflow", exc, request)
            yield _event("error", {"code": 50203})
        finally:
            request.app.state.coach_workflow_active.discard(payload.workflow_id)

    return StreamingResponse(events(), media_type="text/event-stream")
