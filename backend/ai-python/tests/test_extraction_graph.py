import asyncio
import copy
import json

import pytest

from app.core.errors import ServiceError


class SequenceProvider:
    def __init__(self, responses):
        self.responses = iter(responses)
        self.calls = []

    async def generate_json(self, messages, *, background=False):
        self.calls.append(copy.deepcopy(messages))
        response = next(self.responses)
        if isinstance(response, Exception):
            raise response
        return response


def graph_for(provider):
    from app.agent.extraction_graph import build_extraction_graph

    return build_extraction_graph(provider)


def test_graph_has_explicit_generation_validation_and_repair_nodes():
    graph = graph_for(SequenceProvider([]))
    assert {"prepare", "generate", "validate", "repair"} <= set(graph.get_graph().nodes)


def test_graph_validates_and_forces_draft(question):
    question["status"] = "published"
    provider = SequenceProvider([json.dumps({"questions": [question]})])
    state = asyncio.run(graph_for(provider).ainvoke({"text": "题目原文"}))
    assert state["result"].questions[0].status == "draft"
    assert state["attempts"] == 1
    assert len(provider.calls) == 1


def test_graph_repairs_once_and_finishes(question):
    provider = SequenceProvider(["not-json", json.dumps({"questions": [question]})])
    state = asyncio.run(graph_for(provider).ainvoke({"text": "题目原文"}))
    assert state["attempts"] == 2
    assert state["result"].questions[0].stem_md == question["stem_md"]
    assert "validation" in provider.calls[1][-1]["content"]


def test_graph_rejects_repeated_invalid_output():
    provider = SequenceProvider(["{}", "{}"])
    with pytest.raises(ServiceError) as error:
        asyncio.run(graph_for(provider).ainvoke({"text": "题目原文"}))
    assert error.value.code == 50202
    assert len(provider.calls) == 2


def test_graph_does_not_retry_provider_failure():
    provider = SequenceProvider([ServiceError(504, 50401, "timeout")])
    with pytest.raises(ServiceError) as error:
        asyncio.run(graph_for(provider).ainvoke({"text": "题目原文"}))
    assert error.value.code == 50401
    assert len(provider.calls) == 1


def test_compiled_graph_isolates_concurrent_requests(question):
    class EchoProvider:
        async def generate_json(self, messages, *, background=False):
            source = messages[1]["content"]
            await asyncio.sleep(0)
            item = copy.deepcopy(question)
            item["stem_md"] = source
            return json.dumps({"questions": [item]})

    async def run():
        graph = graph_for(EchoProvider())
        return await asyncio.gather(
            graph.ainvoke({"text": "题目一"}), graph.ainvoke({"text": "题目二"})
        )

    states = asyncio.run(run())
    assert [s["result"].questions[0].stem_md for s in states] == ["题目一", "题目二"]
