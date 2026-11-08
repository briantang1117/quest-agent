import json

from conftest import encode, settings, signed
from fastapi.testclient import TestClient

from app.main import create_app

PATH = "/internal/public-import"


class Provider:
    def __init__(self, question):
        self.question = question
        self.calls = []
        self.closed = False

    async def generate_json(self, messages):
        self.calls.append(messages)
        return json.dumps({"questions": [self.question]})

    async def aclose(self):
        self.closed = True


def test_public_import_requires_hmac_and_admin_and_preserves_original(question):
    provider = Provider(question)
    body = encode(
        {
            "scope": "admin",
            "text": "合成题干\nA.甲 B.乙\n正确答案：B\n你的答案：A PRIVATE\n解析：原解析",
        }
    )
    app = create_app(settings(), provider=provider)
    with TestClient(app) as client:
        assert client.post(PATH, content=body).status_code == 401
        denied = encode({"scope": "student", "text": "合成题干"})
        assert (
            client.post(PATH, content=denied, headers=signed(denied, path=PATH)).status_code == 403
        )
        response = client.post(PATH, content=body, headers=signed(body, path=PATH))
        assert response.status_code == 200
        data = response.json()["data"]
        assert data["draft"]["answer"]["source"] == "imported"
        assert data["draft"]["answer"]["keys"] == ["B"]
        assert data["draft"]["explanation_md"] == "解析：原解析"
        assert data["provenance"]["confirmation_status"] == "pending_importer_confirmation"
        assert "PRIVATE" not in str(provider.calls)
    assert provider.closed
    assert app.state.candidate_graph is None


def test_pdf_candidate_retains_cross_page_reference_and_explanation(question):
    provider = Provider(question)
    text = "1.合成题干\nA.甲 B.乙\n正确答案：B\n解析：原文"
    body = encode(
        {
            "scope": "admin",
            "text": text,
            "candidate": {
                "question_no": 1,
                "source_text": text,
                "page_numbers": [2, 3],
                "reference_keys": ["B"],
                "reference_explanation": "解析：原文",
                "warnings": [],
            },
        }
    )
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = client.post(PATH, content=body, headers=signed(body, path=PATH))
        assert response.status_code == 200
        data = response.json()["data"]
        assert data["provenance"]["source_pages"] == [2, 3]
        assert data["draft"]["answer"]["keys"] == ["B"]
        assert data["draft"]["explanation_md"] == "解析：原文"


def test_public_import_without_llm_returns_unavailable():
    body = encode({"scope": "admin", "text": "合成题干"})
    with TestClient(create_app(settings())) as client:
        assert client.post(PATH, content=body, headers=signed(body, path=PATH)).status_code == 503


def test_explicit_single_question_does_not_guess_multiple_questions(question):
    provider = Provider(question)
    body = encode({"scope": "admin", "text": "1.第一题\n正确答案：A\n2.第二题\n正确答案：B"})
    with TestClient(create_app(settings(), provider=provider)) as client:
        response = client.post(PATH, content=body, headers=signed(body, path=PATH))
        assert response.status_code == 422
        assert not provider.calls
