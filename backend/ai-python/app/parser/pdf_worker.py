"""仅由可信工程目录启动的离线预检子进程；资源限制不等同于完整OS沙箱。"""

import json
import resource
import sys
from pathlib import Path

# -I不导入当前目录；只显式加入由本脚本位置确定的可信工程根目录。
PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT))

from app.parser.pdf_inspection import MAX_PDF_BYTES, inspect_pdf  # noqa: E402
from app.parser.question_candidates import split_candidates  # noqa: E402

MAX_CANDIDATES = 100
MAX_SOURCE_BYTES = 2_000_000
MAX_CANDIDATE_CHARS = 12000
MAX_OUTPUT_BYTES = 6 * 1024 * 1024


class PdfBatchLimitError(ValueError):
    pass


def apply_limits():
    if sys.platform not in {"linux", "darwin"}:
        raise RuntimeError("不支持此平台的PDF隔离")
    degraded = False
    try:
        resource.setrlimit(resource.RLIMIT_AS, (2 * 1024**3, 2 * 1024**3))
    except (ValueError, OSError):
        if sys.platform != "darwin":
            raise
        # Darwin不支持AS时明确降级，由父进程RSS采样补充；存在采样窗口，非硬内存限制。
        degraded = True
    resource.setrlimit(resource.RLIMIT_CPU, (12, 15))
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_OUTPUT_BYTES, MAX_OUTPUT_BYTES))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    return degraded


def disable_network():
    # Python审计钩子拒绝socket创建/DNS；不是内核级网络隔离，也不防原生代码绕过。
    def guard(event, args):
        if event.startswith("socket."):
            raise PermissionError("离线PDF预检禁止网络访问")

    sys.addaudithook(guard)


def inspect_batch(raw: bytes) -> dict:
    report = inspect_pdf(raw)
    candidates = split_candidates(report.pages)
    if len(candidates) > MAX_CANDIDATES:
        raise PdfBatchLimitError("候选题数量超过上限")
    total = 0
    for item in candidates:
        total += len(item.source_text.encode("utf-8"))
        if len(item.source_text) > MAX_CANDIDATE_CHARS or total > MAX_SOURCE_BYTES:
            raise PdfBatchLimitError("候选原文超过上限")
    warnings = set(report.warnings)
    page_flags = {}
    for page in report.pages:
        flags = {f"page:{page.page_number}:{warning}" for warning in page.warnings}
        if page.needs_vision:
            flags.add(f"page:{page.page_number}:needs_vision")
        page_flags[page.page_number] = flags
        warnings.update(flags)
    if not candidates:
        warnings.add("requires_visual_segmentation")
    return {
        "sha256": report.sha256,
        "page_count": report.page_count,
        "candidates": [
            {
                "question_no": item.question_no,
                "source_text": item.source_text,
                "page_numbers": list(item.page_numbers),
                "reference_keys": list(item.reference_keys),
                "warnings": sorted(
                    set(item.warnings)
                    | {"pending_review"}
                    | set().union(*(page_flags[number] for number in item.page_numbers))
                ),
                "reference_explanation": item.reference_explanation,
            }
            for item in candidates
        ],
        "warnings": sorted(warnings),
    }


def main():
    try:
        degraded = apply_limits()
        disable_network()
        raw = sys.stdin.buffer.read(MAX_PDF_BYTES + 1)
        if not raw or len(raw) > MAX_PDF_BYTES:
            raise PdfBatchLimitError("PDF体积超过上限")
        result = inspect_batch(raw)
        if degraded:
            result["warnings"].extend(
                ["address_space_limit_unavailable", "rss_watchdog_not_hard_memory_limit"]
            )
        output = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(output) > MAX_OUTPUT_BYTES:
            raise PdfBatchLimitError("预检输出超过上限")
    except Exception:
        # 不透传异常、依赖诊断或原文；父进程仅按退出码生成固定错误。
        return 2
    sys.stdout.buffer.write(output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
