import json

import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient
from test_coach_explain import StreamProvider, events
from test_coach_route import RouteProvider

from app.agent.coach_capabilities import requests_document
from app.main import create_app


@pytest.mark.parametrize(
    "message",
    [
        "帮我梳理城市运行管理服务平台相关的常考提法，做成对比表。",
        "总结一下财政政策和货币政策的区别",
        "帮我整理这段考纲的重点和复习提纲",
        "只在聊天里输出，不用文档，列个表格",
    ],
)
@pytest.mark.parametrize("enabled", [True, False])
def test_content_organization_cannot_become_document_task(message, enabled):
    provider = RouteProvider({"action": "material", "keys": [], "reply": "创建文件。"})
    body = encode({"scope": "student", "message": message, "capabilities": {"materials": enabled}})
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = client.post(
            "/internal/coach-route",
            content=body,
            headers=signed(body, path="/internal/coach-route"),
        )
    assert response.json()["data"]["action"] == "chat"
    assert len(provider.messages) == 1


@pytest.mark.parametrize(
    "message",
    [
        "把我们聊的总结成文档",
        "把本次对话整理成复习材料",
        "生成一份可下载的PDF",
        "整理学习资料",
        "把这份对比表导出为Markdown文件",
    ],
)
def test_explicit_document_creation_is_preserved(message):
    assert requests_document(message)


@pytest.mark.parametrize("enabled", [True, False])
def test_suggestions_respect_features_but_keep_inline_comparison(enabled):
    suggestions = [
        {"action": "chat", "label": "梳理考点", "text": "梳理常考提法，做成对比表。"},
        {"action": "chat", "label": "生成文档", "text": "把本次对话总结成文档"},
        {
            "action": "chat",
            "label": "高级整理",
            "text": "继续整理",
            "required_feature": "materials",
        },
        {"action": "chat", "label": "联网查证", "text": "联网核对", "required_feature": "search"},
    ]
    provider = StreamProvider(
        [json.dumps({"reply": "正文", "suggestions": suggestions}, ensure_ascii=False)]
    )
    body = encode(
        {
            "scope": "student",
            "message": "比较概念",
            "capabilities": {"materials": enabled, "search": False},
        }
    )
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = client.post(
            "/internal/coach-explain",
            content=body,
            headers=signed(body, path="/internal/coach-explain"),
        )
    items = next(data["items"] for name, data in events(response) if name == "suggestions")
    assert [item["label"] for item in items] == (
        ["梳理考点", "生成文档", "高级整理"] if enabled else ["梳理考点"]
    )
    assert "capabilities" in provider.messages[0][1]["content"]
