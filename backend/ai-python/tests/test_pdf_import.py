import base64
import hashlib
import io

import pytest
from conftest import encode, settings, signed
from fastapi.testclient import TestClient
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject

from app.main import create_app

PATH = "/internal/pdf-inspect"


def synthetic_pdf(text=None, active=False):
    writer = PdfWriter()
    page = writer.add_blank_page(width=595, height=842)
    if text is not None:
        font = DictionaryObject(
            {
                NameObject("/Type"): NameObject("/Font"),
                NameObject("/Subtype"): NameObject("/Type1"),
                NameObject("/BaseFont"): NameObject("/Helvetica"),
            }
        )
        page[NameObject("/Resources")] = DictionaryObject(
            {NameObject("/Font"): DictionaryObject({NameObject("/F1"): writer._add_object(font)})}
        )
        stream = DecodedStreamObject()
        lines = text.splitlines()
        commands = [b"BT /F1 10 Tf 40 800 Td"]
        for line in lines:
            escaped = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            commands.append(b"(" + escaped.encode("ascii") + b") Tj 0 -12 Td")
        stream.set_data(b"\n".join(commands) + b"\nET")
        page[NameObject("/Contents")] = writer._add_object(stream)
    if active:
        from pypdf.generic import TextStringObject

        writer.add_open_action(
            DictionaryObject(
                {
                    NameObject("/S"): NameObject("/JavaScript"),
                    NameObject("/JS"): TextStringObject('app.alert("PRIVATE_SCRIPT")'),
                }
            )
        )
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def payload(raw, scope="admin"):
    return encode({"scope": scope, "pdf_base64": base64.b64encode(raw).decode()})


def post(client, body):
    return client.post(PATH, content=body, headers=signed(body, path=PATH))


def test_pdf_inspect_requires_hmac_admin_and_signed_body():
    body = payload(synthetic_pdf("1. Synthetic question\nA. One\nB. Two"))
    with TestClient(create_app(settings())) as client:
        assert client.post(PATH, content=body).status_code == 401
        assert post(client, payload(synthetic_pdf(), scope="student")).status_code == 403
        headers = signed(body, path=PATH)
        assert client.post(PATH, content=body + b" ", headers=headers).status_code == 401
        response = post(client, body)
    assert response.status_code == 200
    result = response.json()
    assert set(result) == {"code", "message", "data", "traceId"}
    assert result["traceId"] == response.headers["X-Trace-Id"]
    assert result["code"] == 0
    assert result["data"]["candidates"][0]["question_no"] == 1


def test_real_pdf_is_prechecked_offline_with_safe_candidate_contract():
    raw = synthetic_pdf("1. Synthetic question\nA. One\nB. Two\n2. Second question")
    with TestClient(create_app(settings())) as client:
        response = post(client, payload(raw))
    assert response.status_code == 200
    data = response.json()["data"]
    assert set(data) == {"sha256", "page_count", "candidates", "warnings"}
    assert data["sha256"] == hashlib.sha256(raw).hexdigest()
    assert data["page_count"] == 1
    assert len(data["candidates"]) == 2
    first = data["candidates"][0]
    assert set(first) == {
        "question_no",
        "source_text",
        "page_numbers",
        "reference_keys",
        "warnings",
        "reference_explanation",
    }
    assert first["source_text"] == "1. Synthetic question\nA. One\nB. Two"
    assert first["page_numbers"] == [1]
    assert first["reference_keys"] == []
    assert first["reference_explanation"] == ""
    assert "pending_review" in first["warnings"]
    assert "pdf_base64" not in response.text
    assert base64.b64encode(raw).decode() not in response.text


@pytest.mark.parametrize("text", [None, "No numbered questions here"])
def test_no_candidates_requires_visual_segmentation(text):
    with TestClient(create_app(settings())) as client:
        response = post(client, payload(synthetic_pdf(text)))
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["candidates"] == []
    assert "requires_visual_segmentation" in data["warnings"]
    if text is None:
        assert "page:1:needs_vision" in data["warnings"]
        assert "page:1:no_text_layer" in data["warnings"]


def test_active_pdf_diagnostics_are_preserved_without_executing_scripts():
    with TestClient(create_app(settings())) as client:
        response = post(client, payload(synthetic_pdf(active=True)))
    assert response.status_code == 200
    assert "active_content" in response.json()["data"]["warnings"]
    assert "PRIVATE_SCRIPT" not in response.text


@pytest.mark.parametrize("encoded", ["%%%PRIVATE_BASE64%%%", "", "YW Jj", "abc", 1, None])
def test_bad_base64_is_rejected_without_input_echo(encoded, caplog):
    body = encode({"scope": "admin", "pdf_base64": encoded})
    with TestClient(create_app(settings())) as client:
        response = post(client, body)
    assert response.status_code == 422
    assert response.json()["data"] is None
    assert "PRIVATE_BASE64" not in response.text + caplog.text


def test_more_than_twenty_mib_is_rejected_before_parsing():
    with TestClient(create_app(settings())) as client:
        response = post(client, payload(b"%PDF-" + b"x" * (20 * 1024 * 1024 - 4)))
    assert response.status_code == 413


@pytest.mark.parametrize(
    "text", ["\n".join(f"{i}. Question" for i in range(1, 102)), "1. " + "X" * 12000]
)
def test_candidate_count_and_single_text_limits_reject_instead_of_truncating(text):
    with TestClient(create_app(settings())) as client:
        response = post(client, payload(synthetic_pdf(text)))
    assert response.status_code == 422
    assert response.json()["data"] is None


def test_parser_exception_never_leaks_pdf_or_dependency_details(caplog):
    with TestClient(create_app(settings())) as client:
        response = post(client, payload(b"%PDF-1.7\nPRIVATE_PDF_CONTENT"))
    assert response.status_code == 422
    assert "PRIVATE_PDF_CONTENT" not in response.text + caplog.text


def test_subprocess_timeout_becomes_safe_envelope(monkeypatch, caplog):
    from app.api import pdf_import
    from app.parser.pdf_runner import PdfRunnerError

    def timeout(raw):
        raise PdfRunnerError("timeout")

    monkeypatch.setattr(pdf_import, "run_pdf_inspection", timeout)
    with TestClient(create_app(settings())) as client:
        response = post(client, payload(synthetic_pdf()))
    assert response.status_code == 504
    assert response.json()["data"] is None
    assert response.json()["traceId"]
    assert "Traceback" not in response.text + caplog.text
