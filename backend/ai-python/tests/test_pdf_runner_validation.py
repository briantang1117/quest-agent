import hashlib
import json
import subprocess

import pytest
from test_pdf_import import synthetic_pdf


def test_child_output_contract_is_validated_before_returning(monkeypatch, tmp_path):
    from app.parser import pdf_runner

    raw = synthetic_pdf()
    # 有效顶层字段不应掩盖超长、格式错误或注入额外字段的候选输出。
    malformed = {
        "sha256": hashlib.sha256(raw).hexdigest(),
        "page_count": 1,
        "candidates": [{"source_text": "PRIVATE_UNEXPECTED", "pdf_base64": "PRIVATE_DATA"}],
        "warnings": [],
    }
    script = tmp_path / "trusted_contract_worker.py"
    script.write_text(f"import sys\nsys.stdout.write({json.dumps(malformed)!r})\n")
    real_popen = subprocess.Popen
    monkeypatch.setattr(
        pdf_runner.subprocess,
        "Popen",
        lambda args, **kwargs: real_popen([args[0], "-I", str(script)], **kwargs),
    )
    with pytest.raises(pdf_runner.PdfRunnerError) as exc:
        pdf_runner.run_pdf_inspection(raw)
    assert exc.value.reason == "worker_failed"
    assert "PRIVATE" not in str(exc.value)
