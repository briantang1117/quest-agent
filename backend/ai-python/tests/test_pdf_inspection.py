import hashlib
import io
import json

import pytest
from pypdf import PdfWriter
from pypdf.generic import DecodedStreamObject, DictionaryObject, NameObject


def pdf_bytes(pages=1, password=None, text=False, active=False):
    writer = PdfWriter()
    for _ in range(pages):
        page = writer.add_blank_page(width=595, height=842)
        if text:
            font = DictionaryObject(
                {
                    NameObject("/Type"): NameObject("/Font"),
                    NameObject("/Subtype"): NameObject("/Type1"),
                    NameObject("/BaseFont"): NameObject("/Helvetica"),
                }
            )
            page[NameObject("/Resources")] = DictionaryObject(
                {
                    NameObject("/Font"): DictionaryObject(
                        {NameObject("/F1"): writer._add_object(font)}
                    )
                }
            )
            stream = DecodedStreamObject()
            stream.set_data(b"BT /F1 12 Tf 72 720 Td (Question 1. A or B?) Tj ET")
            page[NameObject("/Contents")] = writer._add_object(stream)
    if password:
        writer.encrypt(password)
    if active:
        writer.add_js('app.alert("untrusted")')
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


def inspect(data, **limits):
    from app.parser.pdf_inspection import inspect_pdf

    return inspect_pdf(data, **limits)


def test_blank_pdf_keeps_page_numbers_and_requires_visual_review():
    raw = pdf_bytes(2)
    report = inspect(raw)
    assert report.page_count == 2
    assert report.sha256 == hashlib.sha256(raw).hexdigest()
    assert [page.page_number for page in report.pages] == [1, 2]
    assert all(page.text_length == 0 and page.needs_vision for page in report.pages)
    assert all(page.width == 595 and page.height == 842 for page in report.pages)


def test_text_pdf_extracts_source_without_network_or_generating_answers():
    report = inspect(pdf_bytes(text=True))
    assert "Question 1. A or B?" in report.pages[0].text
    assert report.pages[0].text_length == len(report.pages[0].text)
    assert not report.pages[0].needs_vision
    assert report.pages[0].image_count == 0
    assert "Question 1. A or B?" in report.pages[0].layout_text
    assert report.pages[0].has_answer_annotations is False


@pytest.mark.parametrize("raw", [b"", b"not a pdf", b"%PDF-1.7\ntruncated"])
def test_invalid_pdf_returns_sanitized_error(raw):
    from app.parser.pdf_inspection import PdfInspectionError

    with pytest.raises(PdfInspectionError):
        inspect(raw)


def test_encrypted_pdf_is_not_silently_opened_or_password_guessed():
    from app.parser.pdf_inspection import PdfInspectionError

    with pytest.raises(PdfInspectionError, match="加密"):
        inspect(pdf_bytes(password="test-password"))


def test_limits_reject_oversized_or_excessive_pages():
    from app.parser.pdf_inspection import PdfInspectionError

    raw = pdf_bytes(2)
    with pytest.raises(PdfInspectionError, match="体积"):
        inspect(raw, max_bytes=10)
    with pytest.raises(PdfInspectionError, match="页数"):
        inspect(raw, max_pages=1)
    with pytest.raises(PdfInspectionError, match="文本"):
        inspect(pdf_bytes(text=True), max_text_chars=5)


def test_active_content_is_detected_not_executed():
    report = inspect(pdf_bytes(active=True))
    assert report.has_active_content
    assert "active_content" in report.warnings


def test_image_resources_are_counted_without_decoding_image_data():
    writer = PdfWriter()
    page = writer.add_blank_page(width=595, height=842)
    image = DecodedStreamObject()
    image.set_data(b"\x00\x5a\xff")
    from pypdf.generic import NumberObject

    image.update(
        {
            NameObject("/Type"): NameObject("/XObject"),
            NameObject("/Subtype"): NameObject("/Image"),
            NameObject("/Width"): NumberObject(1),
            NameObject("/Height"): NumberObject(1),
            NameObject("/ColorSpace"): NameObject("/DeviceRGB"),
            NameObject("/BitsPerComponent"): NumberObject(8),
        }
    )
    page[NameObject("/Resources")] = DictionaryObject(
        {NameObject("/XObject"): DictionaryObject({NameObject("/Im0"): writer._add_object(image)})}
    )
    raw = io.BytesIO()
    writer.write(raw)
    report = inspect(raw.getvalue())
    assert report.pages[0].image_count == 1
    assert report.pages[0].needs_vision
    assert "contains_images" in report.pages[0].warnings


def test_cli_writes_report_exclusively_without_overwriting_source_or_existing_report(tmp_path):
    from app.parser.pdf_inspection import PdfInspectionError, inspect_file

    source = tmp_path / "sample.pdf"
    source.write_bytes(pdf_bytes(text=True))
    output = tmp_path / "report.json"
    inspect_file(source, output)
    result = json.loads(output.read_text())
    assert result["page_count"] == 1
    assert result["cloud_calls"] == 0
    assert "Question" in result["pages"][0]["text"]
    with pytest.raises(PdfInspectionError, match="已存在"):
        inspect_file(source, output)
    with pytest.raises(PdfInspectionError):
        inspect_file(source, source)
    assert source.read_bytes().startswith(b"%PDF-")
