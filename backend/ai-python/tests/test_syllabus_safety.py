import copy
import hashlib
import json
import subprocess

import pytest
from conftest import settings
from fastapi.testclient import TestClient
from syllabus_fixtures import syllabus_pdf
from test_syllabus import send


def worker_result(raw):
    return {
        "sha256": hashlib.sha256(raw).hexdigest(),
        "pageCount": 1,
        "pages": [{"page": 1, "printedPage": "I", "text": "正文"}],
        "sections": [
            {
                "ordinal": 1,
                "title": "正文",
                "path": "正文",
                "kind": "other",
                "scope": "excluded",
                "enabled": False,
                "pageStart": 1,
                "pageEnd": 1,
                "printedPages": "I",
                "text": "正文",
            }
        ],
        "warnings": ["pending_review"],
    }


def replace_worker(monkeypatch, tmp_path, source):
    from app.parser import syllabus_runner

    script = tmp_path / "trusted_fixture_worker.py"
    script.write_text(source)
    monkeypatch.setattr(syllabus_runner, "WORKER_SCRIPT", script)


@pytest.mark.parametrize(
    ("location", "change"),
    [
        ("root", {"sha256": "0" * 64}),
        ("root", {"pageCount": 2}),
        ("root", {"warnings": []}),
        ("root", {"examYear": 2027}),
        ("section", {"enabled": True}),
        ("section", {"enabled": 0}),
        ("section", {"pageEnd": 2}),
        ("section", {"pageStart": 2}),
        ("section", {"printedPages": "II"}),
        ("section", {"ordinal": 2}),
        ("section", {"text": "x" * 12001}),
        ("section", {"path": "x" * 1001}),
        ("section", {"scope": "all"}),
        ("page", {"page": 2}),
        ("page", {"text": 3}),
    ],
)
def test_malformed_child_output_fails_closed_without_echo(monkeypatch, tmp_path, location, change):
    from app.parser.pdf_runner import PdfRunnerError
    from app.parser.syllabus_runner import run_syllabus_inspection

    raw = syllabus_pdf("正文")
    result = copy.deepcopy(worker_result(raw))
    target = (
        result
        if location == "root"
        else result["sections" if location == "section" else "pages"][0]
    )
    target.update(change)
    replace_worker(monkeypatch, tmp_path, f"import sys\nsys.stdout.write({json.dumps(result)!r})\n")
    with pytest.raises(PdfRunnerError) as caught:
        run_syllabus_inspection(raw)
    assert caught.value.reason == "worker_failed"
    assert str(caught.value) == "PDF预检失败"


def test_child_output_is_bounded(monkeypatch, tmp_path):
    from app.parser.pdf_runner import PdfRunnerError
    from app.parser.syllabus_runner import run_syllabus_inspection

    replace_worker(monkeypatch, tmp_path, "import sys\nsys.stdout.write('x' * (6*1024*1024+1))\n")
    with pytest.raises(PdfRunnerError) as caught:
        run_syllabus_inspection(syllabus_pdf("正文"))
    assert caught.value.reason == "output_limit"


def test_timeout_kills_child_and_returns_safe_envelope(monkeypatch, tmp_path, caplog):
    from app.main import create_app
    from app.parser import pdf_runner

    replace_worker(monkeypatch, tmp_path, "import time\ntime.sleep(60)\n")
    monkeypatch.setattr(pdf_runner, "TIMEOUT_SECONDS", 0.05)
    real_popen = subprocess.Popen
    owned = []

    def popen(*args, **kwargs):
        child = real_popen(*args, **kwargs)
        owned.append(child)
        return child

    monkeypatch.setattr(pdf_runner.subprocess, "Popen", popen)
    with TestClient(create_app(settings())) as client:
        response = send(client, syllabus_pdf("PRIVATE_BODY"))
    assert response.status_code == 504
    assert response.json()["data"] is None
    assert all(child.poll() is not None for child in owned)
    assert "PRIVATE_BODY" not in response.text + caplog.text


def test_rss_limit_kills_only_syllabus_child(monkeypatch, tmp_path):
    from app.parser import pdf_runner
    from app.parser.syllabus_runner import run_syllabus_inspection

    replace_worker(
        monkeypatch, tmp_path, "import time\ndata=bytearray(8*1024*1024)\ntime.sleep(60)\n"
    )
    monkeypatch.setattr(pdf_runner, "MAX_RSS_BYTES", 1024)
    monkeypatch.setattr(pdf_runner, "USE_RSS_WATCHDOG", True)
    with pytest.raises(pdf_runner.PdfRunnerError) as caught:
        run_syllabus_inspection(syllabus_pdf("正文"))
    assert caught.value.reason == "memory_limit"


def test_content_stream_budget_is_applied_before_text_extraction(caplog):
    import io

    from pypdf import PdfWriter
    from pypdf.generic import DecodedStreamObject, NameObject

    from app.main import create_app

    writer = PdfWriter()
    page = writer.add_blank_page(width=100, height=100)
    stream = DecodedStreamObject()
    stream.set_data(b"%PRIVATE_COMPRESSED_CONTENT\n" + b" " * (8 * 1024 * 1024))
    page[NameObject("/Contents")] = writer._add_object(stream.flate_encode())
    output = io.BytesIO()
    writer.write(output)
    with TestClient(create_app(settings())) as client:
        response = send(client, output.getvalue())
    assert response.status_code == 422
    assert "PRIVATE_COMPRESSED_CONTENT" not in response.text + caplog.text


def test_worker_limits_and_network_denial_are_real(monkeypatch, tmp_path):
    from app.parser import syllabus_runner
    from app.parser.pdf_runner import run_pdf_worker

    script = tmp_path / "trusted_limits_probe.py"
    project = str(syllabus_runner.WORKER_SCRIPT.parents[2])
    script.write_text(
        f"import sys; sys.path.insert(0, {project!r})\n"
        "from app.parser.pdf_worker import apply_limits, disable_network\n"
        "import resource, json, socket\n"
        "apply_limits(); disable_network()\n"
        "try:\n    socket.socket()\nexcept PermissionError:\n    denied = True\n"
        "print(json.dumps({'cpu': resource.getrlimit(resource.RLIMIT_CPU), "
        "'output': resource.getrlimit(resource.RLIMIT_FSIZE), 'network_denied': denied}))\n"
    )
    result = json.loads(run_pdf_worker(b"probe", script))
    assert result == {"cpu": [12, 15], "output": [6291456, 6291456], "network_denied": True}
