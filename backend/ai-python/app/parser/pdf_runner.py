"""有界独立进程PDF预检入口，不在服务进程解析PDF。

Darwin的AS不可用时采用RSS看门狗，采样存在窗口，非硬内存/完整OS沙箱。
仅用于本机受信Admin调试，不构成公网不可信生产上传安全保证。
"""

import hashlib
import json
import subprocess
import sys
import tempfile
import threading
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

MAX_PDF_BYTES = 20 * 1024 * 1024
MAX_OUTPUT_BYTES = 6 * 1024 * 1024
TIMEOUT_SECONDS = 20
MAX_RSS_BYTES = 2 * 1024**3
USE_RSS_WATCHDOG = sys.platform == "darwin"
_RSS_POPEN = subprocess.Popen
WORKER_SCRIPT = Path(__file__).resolve().with_name("pdf_worker.py")


class PdfRunnerError(Exception):
    def __init__(self, reason: str):
        self.reason = reason
        super().__init__("PDF预检失败")


class _Candidate(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    question_no: int | None = Field(ge=1, le=9999)
    source_text: str = Field(min_length=1, max_length=12000)
    page_numbers: list[int] = Field(min_length=1, max_length=200)
    reference_keys: list[Literal["A", "B", "C", "D", "E", "F", "G", "H"]] = Field(max_length=8)
    warnings: list[str] = Field(max_length=1000)
    reference_explanation: str = Field(max_length=12000)


class _Result(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    page_count: int = Field(ge=1, le=200)
    candidates: list[_Candidate] = Field(max_length=100)
    warnings: list[str] = Field(max_length=5000)

    @model_validator(mode="after")
    def check_sources(self):
        if sum(len(item.source_text.encode("utf-8")) for item in self.candidates) > 2_000_000:
            raise ValueError("原文总量超限")
        for item in self.candidates:
            if (
                any(not 1 <= page <= self.page_count for page in item.page_numbers)
                or "pending_review" not in item.warnings
            ):
                raise ValueError("候选来源无效")
        return self


def _watch_rss(process, stop, exceeded):
    while not stop.wait(0.05):
        if process.poll() is not None:
            return
        try:
            # 固定可信系统工具，仅查询本次创建的PID；不扫描/终止应用其他进程。
            with _RSS_POPEN(
                ["/bin/ps", "-o", "rss=", "-p", str(process.pid)],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                env={"LANG": "C"},
                shell=False,
            ) as probe:
                try:
                    rss, _ = probe.communicate(timeout=1)
                except subprocess.TimeoutExpired:
                    probe.kill()
                    probe.communicate()
                    raise
            if process.poll() is not None:
                return
            if probe.returncode != 0 or int(rss.strip()) * 1024 > MAX_RSS_BYTES:
                exceeded.set()
                process.kill()
                return
        except (OSError, ValueError, subprocess.TimeoutExpired):
            if process.poll() is None:
                exceeded.set()
                process.kill()
            return


def run_pdf_inspection(raw: bytes) -> dict:
    if not raw or len(raw) > MAX_PDF_BYTES:
        raise PdfRunnerError("invalid_pdf")
    try:
        # stdout写临时文件，避免communicate无界累积内存；worker的FSIZE硬限制约束磁盘。
        # 文件在退出时删除，不保存原PDF，不把路径或stderr送回调用方。
        with tempfile.TemporaryFile() as output:
            process = subprocess.Popen(
                [sys.executable, "-I", str(WORKER_SCRIPT)],
                cwd=WORKER_SCRIPT.parent,
                env={"LANG": "C.UTF-8"},
                stdin=subprocess.PIPE,
                stdout=output,
                stderr=subprocess.DEVNULL,
                shell=False,
                close_fds=True,
            )
            stop = threading.Event()
            exceeded = threading.Event()
            watcher = None
            if USE_RSS_WATCHDOG:
                watcher = threading.Thread(
                    target=_watch_rss, args=(process, stop, exceeded), daemon=True
                )
                watcher.start()
            try:
                process.communicate(input=raw, timeout=TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
                raise PdfRunnerError("timeout") from None
            finally:
                stop.set()
                if watcher is not None:
                    watcher.join(timeout=2)
                if process.poll() is None:
                    process.kill()
                    process.wait()
            if exceeded.is_set():
                raise PdfRunnerError("memory_limit")
            output.seek(0)
            serialized = output.read(MAX_OUTPUT_BYTES + 1)
            if len(serialized) > MAX_OUTPUT_BYTES:
                raise PdfRunnerError("output_limit")
            if process.returncode != 0:
                raise PdfRunnerError("invalid_pdf")
        result = _Result.model_validate(json.loads(serialized))
        if result.sha256 != hashlib.sha256(raw).hexdigest():
            raise PdfRunnerError("worker_failed")
        return result.model_dump()
    except PdfRunnerError:
        raise
    except (OSError, ValueError, TypeError):
        raise PdfRunnerError("worker_failed") from None
