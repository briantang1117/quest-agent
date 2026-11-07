import json

from app.api.schemas import ExtractResult
from app.llm.base import LLMProvider

PROMPT_VERSION = "extract-v1"


def parse_drafts(content: str) -> ExtractResult:
    payload = json.loads(content)
    # 模型输出不可信：先覆盖发布/审核声明，再通过严格DTO验证。
    if isinstance(payload, dict) and isinstance(payload.get("questions"), list):
        for question in payload["questions"]:
            if isinstance(question, dict):
                question["status"] = "draft"
                if isinstance(question.get("answer"), dict):
                    question["answer"]["source"] = "ai_generated"
                if "quality" not in question:
                    question["quality"] = {}
                if isinstance(question["quality"], dict):
                    question["quality"]["review_status"] = "pending"
    return ExtractResult.model_validate(payload)


async def extract_questions(provider: LLMProvider, text: str) -> ExtractResult:
    # 保留原调用契约；服务生命周期中预编译图，其他调用方可使用此兼容入口。
    from app.agent.extraction_graph import build_extraction_graph

    state = await build_extraction_graph(provider).ainvoke({"text": text})
    return state["result"]
