import subprocess
import sys

import pytest
from test_pdf_import import synthetic_pdf


def test_darwin_degradation_has_fixed_warning():
    if sys.platform != "darwin":
        pytest.skip("仅验证Darwin显式降级")
    from app.parser.pdf_runner import run_pdf_inspection

    result = run_pdf_inspection(synthetic_pdf())
    assert "address_space_limit_unavailable" in result["warnings"]
    assert "rss_watchdog_not_hard_memory_limit" in result["warnings"]


def test_rss_watchdog_kills_only_owned_child(monkeypatch, tmp_path):
    from app.parser import pdf_runner

    script = tmp_path / "trusted_memory_worker.py"
    script.write_text("import time\ndata=bytearray(8*1024*1024)\ntime.sleep(60)\n")
    real_popen = subprocess.Popen
    children = []
    independent = real_popen([sys.executable, "-I", "-c", "import time; time.sleep(60)"])

    def popen(args, **kwargs):
        child = real_popen([args[0], "-I", str(script)], **kwargs)
        children.append(child)
        return child

    monkeypatch.setattr(pdf_runner, "MAX_RSS_BYTES", 1024)
    monkeypatch.setattr(pdf_runner, "USE_RSS_WATCHDOG", True)
    monkeypatch.setattr(pdf_runner.subprocess, "Popen", popen)
    try:
        with pytest.raises(pdf_runner.PdfRunnerError) as exc:
            pdf_runner.run_pdf_inspection(synthetic_pdf())
        assert exc.value.reason == "memory_limit"
        assert children[0].poll() is not None
        assert independent.poll() is None
    finally:
        independent.kill()
        independent.wait()


def test_unknown_platform_fails_closed(monkeypatch):
    from app.parser import pdf_worker

    monkeypatch.setattr(pdf_worker.sys, "platform", "unknown")
    with pytest.raises(RuntimeError):
        pdf_worker.apply_limits()


def test_linux_as_failure_fails_closed(monkeypatch):
    from app.parser import pdf_worker

    monkeypatch.setattr(pdf_worker.sys, "platform", "linux")
    real_set = pdf_worker.resource.setrlimit

    def reject_as(kind, value):
        if kind == pdf_worker.resource.RLIMIT_AS:
            raise ValueError("unsupported")
        return real_set(kind, value)

    monkeypatch.setattr(pdf_worker.resource, "setrlimit", reject_as)
    with pytest.raises(ValueError):
        pdf_worker.apply_limits()
