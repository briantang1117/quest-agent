import dataclasses
import json
import subprocess
import sys
from pathlib import Path

import pytest
from test_pdf_import import synthetic_pdf


def runner():
    from app.parser import pdf_runner

    return pdf_runner


def test_runner_uses_real_isolated_process_from_trusted_directory(monkeypatch, tmp_path):
    monkeypatch.chdir(tmp_path)
    (tmp_path / "json.py").write_text("raise RuntimeError('PLANTED_MODULE')")
    monkeypatch.setenv("PYTHONPATH", str(tmp_path))
    result = runner().run_pdf_inspection(synthetic_pdf("1. Question"))
    assert result["page_count"] == 1
    assert result["candidates"][0]["source_text"] == "1. Question"


def test_runner_process_has_isolated_flags_no_shell_and_no_inherited_secrets(monkeypatch):
    module = runner()
    real_popen = subprocess.Popen
    observed = []
    monkeypatch.setenv("PRIVATE_SERVICE_KEY", "PRIVATE_SECRET")

    def popen(args, **kwargs):
        observed.append((args, kwargs))
        return real_popen(args, **kwargs)

    monkeypatch.setattr(module.subprocess, "Popen", popen)
    assert module.run_pdf_inspection(synthetic_pdf())["page_count"] == 1
    args, options = observed[0]
    assert args[0] == sys.executable
    assert args[1] == "-I"
    assert Path(args[2]).is_absolute()
    assert Path(options["cwd"]) == Path(args[2]).parent
    assert options["shell"] is False
    assert options["stderr"] == subprocess.DEVNULL
    assert "PRIVATE_SERVICE_KEY" not in options["env"]
    assert "PYTHONPATH" not in options["env"]


def test_timeout_kills_and_reaps_real_child_without_logging_stderr(monkeypatch, tmp_path, caplog):
    module = runner()
    script = tmp_path / "trusted_test_worker.py"
    script.write_text("import sys,time\nsys.stderr.write('PRIVATE_CHILD_ERROR')\ntime.sleep(60)\n")
    real_popen = subprocess.Popen
    children = []

    def popen(args, **kwargs):
        child = real_popen([args[0], "-I", str(script)], **kwargs)
        children.append(child)
        return child

    monkeypatch.setattr(module, "TIMEOUT_SECONDS", 0.1)
    monkeypatch.setattr(module.subprocess, "Popen", popen)
    with pytest.raises(module.PdfRunnerError) as exc:
        module.run_pdf_inspection(synthetic_pdf())
    assert exc.value.reason == "timeout"
    assert children[0].poll() is not None
    assert "PRIVATE_CHILD_ERROR" not in str(exc.value) + caplog.text


@pytest.mark.parametrize(
    "output", [b"PRIVATE_BROKEN_JSON", b"[]", b'{"error":"PRIVATE_EXCEPTION"}']
)
def test_bad_child_output_is_sanitized(monkeypatch, tmp_path, output, caplog):
    module = runner()
    script = tmp_path / "trusted_bad_worker.py"
    script.write_text(f"import sys\nsys.stdout.buffer.write({output!r})\n")
    real_popen = subprocess.Popen
    monkeypatch.setattr(
        module.subprocess,
        "Popen",
        lambda args, **kwargs: real_popen([args[0], "-I", str(script)], **kwargs),
    )
    with pytest.raises(module.PdfRunnerError) as exc:
        module.run_pdf_inspection(synthetic_pdf())
    assert exc.value.reason == "worker_failed"
    assert "PRIVATE" not in str(exc.value) + caplog.text


def test_output_limit_rejects_instead_of_reading_unbounded_bytes(monkeypatch, tmp_path):
    module = runner()
    script = tmp_path / "trusted_output_worker.py"
    script.write_text("import sys\nsys.stdout.buffer.write(b'x'*2000)\n")
    real_popen = subprocess.Popen
    monkeypatch.setattr(module, "MAX_OUTPUT_BYTES", 1024)
    monkeypatch.setattr(
        module.subprocess,
        "Popen",
        lambda args, **kwargs: real_popen([args[0], "-I", str(script)], **kwargs),
    )
    with pytest.raises(module.PdfRunnerError) as exc:
        module.run_pdf_inspection(synthetic_pdf())
    assert exc.value.reason == "output_limit"


def test_total_source_text_byte_limit_rejects_even_under_character_limit(monkeypatch):
    from app.parser import pdf_worker
    from app.parser.pdf_inspection import PageInspection, PdfInspection
    from app.parser.question_candidates import QuestionCandidate

    page = PageInspection(1, 1, 1, "", "", False, 0, 0, False, ())
    report = PdfInspection("a" * 64, 1, 1, False, (page,), ())
    candidates = tuple(QuestionCandidate(i, "题" * 11000, (1,), (), ()) for i in range(100))
    monkeypatch.setattr(pdf_worker, "inspect_pdf", lambda raw: report)
    monkeypatch.setattr(pdf_worker, "split_candidates", lambda pages: candidates)
    with pytest.raises(pdf_worker.PdfBatchLimitError):
        pdf_worker.inspect_batch(b"x")


def test_worker_keeps_page_diagnostics_and_original_answers(monkeypatch):
    from app.parser import pdf_worker
    from app.parser.pdf_inspection import PageInspection, PdfInspection

    page = PageInspection(
        1,
        1,
        1,
        "",
        "1. 题目\n正确答案：B\n解析：原解析",
        False,
        0,
        0,
        True,
        ("layout_text_unavailable",),
    )
    report = PdfInspection("a" * 64, 1, 1, False, (page,), ("parser_diagnostics",))
    monkeypatch.setattr(pdf_worker, "inspect_pdf", lambda raw: report)
    data = pdf_worker.inspect_batch(b"x")
    assert "parser_diagnostics" in data["warnings"]
    assert "page:1:needs_vision" in data["warnings"]
    candidate = data["candidates"][0]
    assert candidate["reference_keys"] == ["B"]
    assert candidate["reference_explanation"] == "解析：原解析"
    assert "page:1:layout_text_unavailable" in candidate["warnings"]
    assert "pending_review" in candidate["warnings"]
    assert dataclasses.is_dataclass(page)


def test_worker_resource_limits_and_python_network_guard_run_in_child():
    module = runner()
    worker = Path(module.__file__).with_name("pdf_worker.py")
    code = f"""
import runpy,resource,socket,json
namespace = runpy.run_path({str(worker)!r}, run_name="guard_test")
namespace["apply_limits"]()
namespace["disable_network"]()
blocked = False
try:
    socket.socket()
except PermissionError:
    blocked = True
print(json.dumps({{
    "as": resource.getrlimit(resource.RLIMIT_AS),
    "cpu": resource.getrlimit(resource.RLIMIT_CPU),
    "file": resource.getrlimit(resource.RLIMIT_FSIZE),
    "blocked": blocked,
}}))
"""
    process = subprocess.run(
        [sys.executable, "-I", "-c", code],
        capture_output=True,
        timeout=5,
        env={"LANG": "C.UTF-8"},
        cwd=worker.parent,
        check=True,
    )
    limits = json.loads(process.stdout)
    if sys.platform != "darwin":
        assert 0 < limits["as"][1] <= 2 * 1024**3
    assert 0 < limits["cpu"][1] < 20
    assert 0 < limits["file"][1] <= 6 * 1024**2
    assert limits["blocked"]


def test_runner_spawn_error_is_safe(monkeypatch, caplog):
    module = runner()

    def fail(*args, **kwargs):
        raise OSError("PRIVATE_PATH_AND_KEY")

    monkeypatch.setattr(module.subprocess, "Popen", fail)
    with pytest.raises(module.PdfRunnerError) as exc:
        module.run_pdf_inspection(synthetic_pdf())
    assert "PRIVATE" not in str(exc.value) + caplog.text
