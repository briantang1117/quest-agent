"""仅在有界离线子进程中检查并渲染私人PDF，不联网或执行文档脚本。"""

import base64
import io
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from app.parser.pdf_inspection import inspect_pdf  # noqa: E402
from app.parser.pdf_worker import apply_limits, disable_network  # noqa: E402


def convert(raw):
    report = inspect_pdf(raw)
    if report.has_active_content or report.page_count > 20:
        raise ValueError("PDF活动内容或页数超限")
    import pypdfium2 as pdfium

    pages, pixels, image_bytes, text_chars = [], 0, 0, 0
    with pdfium.PdfDocument(raw) as document:
        for info in report.pages:
            text_chars += len(info.layout_text)
            if text_chars > 160000:
                raise ValueError("正文超限")
            page = {"page": info.page_number, "text": info.layout_text, "image": None}
            if info.needs_vision or info.image_count:
                pdf_page = document[info.page_number - 1]
                try:
                    width, height = pdf_page.get_size()
                    scale = min(1.7, 1600 / max(width, height))
                    pixels += int(width * scale) * int(height * scale)
                    if pixels > 32_000_000:
                        raise ValueError("渲染像素超限")
                    bitmap = pdf_page.render(scale=scale)
                    try:
                        output = io.BytesIO()
                        bitmap.to_pil().save(output, format="PNG")
                        png = output.getvalue()
                    finally:
                        bitmap.close()
                finally:
                    pdf_page.close()
                image_bytes += len(png)
                if image_bytes > 3_500_000:
                    raise ValueError("渲染结果超限")
                page["image"] = base64.b64encode(png).decode()
            pages.append(page)
    return {"pages": pages}


if __name__ == "__main__":
    try:
        apply_limits()
        disable_network()
        raw = sys.stdin.buffer.read(8 * 1024 * 1024 + 1)
        if len(raw) > 8 * 1024 * 1024:
            raise ValueError()
        sys.stdout.write(json.dumps(convert(raw), ensure_ascii=False))
    except Exception:
        sys.exit(2)
