"""手动离线烟测：python -I 本脚本 资料目录；只输出统计、不落盘原文。"""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

# 只能从可信脚本位置引入工程，不能把资料目录加入模块搜索路径。
PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

from app.parser.pdf_runner import MAX_PDF_BYTES  # noqa: E402
from app.parser.syllabus_runner import run_syllabus_inspection  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description="不调用模型的考纲独立进程烟测")
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    for source in sorted(args.directory.glob("*.pdf")):
        with source.open("rb") as handle:
            raw = handle.read(MAX_PDF_BYTES + 1)
        result = run_syllabus_inspection(raw)
        print(
            json.dumps(
                {
                    "file": source.name,
                    "bytes": len(raw),
                    "pages": result["pageCount"],
                    "sections": len(result["sections"]),
                    "scope": dict(Counter(s["scope"] for s in result["sections"])),
                    "kind": dict(Counter(s["kind"] for s in result["sections"])),
                    "maxSectionChars": max((len(s["text"]) for s in result["sections"]), default=0),
                    "crossPageSections": sum(
                        s["pageEnd"] > s["pageStart"] for s in result["sections"]
                    ),
                    "printedPages": sum(p["printedPage"] is not None for p in result["pages"]),
                    "enabled": sum(s["enabled"] for s in result["sections"]),
                    "cloudCalls": 0,
                    "warnings": result["warnings"],
                },
                ensure_ascii=False,
            )
        )


if __name__ == "__main__":
    main()
