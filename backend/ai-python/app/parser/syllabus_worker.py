"""可信路径下以 python -I 启动的离线考纲解析子进程。"""

import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(PROJECT_ROOT))

from app.parser.pdf_worker import (  # noqa: E402
    MAX_OUTPUT_BYTES,
    MAX_PDF_BYTES,
    apply_limits,
    disable_network,
)


def main():
    try:
        degraded = apply_limits()
        disable_network()
        # 资源限制和网络审计先于解析器导入及不可信输入读取。
        from app.parser.syllabus_extraction import extract_syllabus

        raw = sys.stdin.buffer.read(MAX_PDF_BYTES + 1)
        if not raw or len(raw) > MAX_PDF_BYTES:
            return 2
        result = extract_syllabus(raw)
        if degraded:
            result["warnings"].extend(
                ["address_space_limit_unavailable", "rss_watchdog_not_hard_memory_limit"]
            )
        output = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(output) > MAX_OUTPUT_BYTES:
            return 2
        sys.stdout.buffer.write(output)
        return 0
    except Exception:
        # 不记录异常消息/依赖诊断/原文，也不将PDF中的元数据传给日志。
        return 2


if __name__ == "__main__":
    sys.exit(main())
