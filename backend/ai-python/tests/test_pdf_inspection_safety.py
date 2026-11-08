import io
import logging
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest
from pypdf import PdfWriter
from pypdf.generic import (
    ArrayObject,
    DecodedStreamObject,
    DictionaryObject,
    NameObject,
    NumberObject,
)

from app.parser.pdf_inspection import MAX_CONTENT_BYTES, PdfInspectionError, inspect_pdf


def form_pdf(padding=0, *, copies=1, depth=1, cycle=False):
    writer = PdfWriter()
    page = writer.add_blank_page(width=595, height=842)
    font = DictionaryObject(
        {
            NameObject("/Type"): NameObject("/Font"),
            NameObject("/Subtype"): NameObject("/Type1"),
            NameObject("/BaseFont"): NameObject("/Helvetica"),
        }
    )
    forms = []
    for _ in range(copies):
        child = None
        for _ in range(depth):
            form = DecodedStreamObject()
            form.set_data(
                b"q /Child Do Q"
                if child
                else b"BT /F1 12 Tf 72 720 Td (Question 1. A or B?) Tj ET" + b" " * padding
            )
            resources = DictionaryObject(
                {
                    NameObject("/Font"): DictionaryObject(
                        {NameObject("/F1"): writer._add_object(font)}
                    )
                }
            )
            if child:
                resources[NameObject("/XObject")] = DictionaryObject({NameObject("/Child"): child})
            form.update(
                {
                    NameObject("/Type"): NameObject("/XObject"),
                    NameObject("/Subtype"): NameObject("/Form"),
                    NameObject("/BBox"): ArrayObject(
                        [NumberObject(0), NumberObject(0), NumberObject(595), NumberObject(842)]
                    ),
                    NameObject("/Resources"): resources,
                }
            )
            child = writer._add_object(form.flate_encode())
        forms.append(child)
    if cycle:
        forms[0].get_object()[NameObject("/Resources")][NameObject("/XObject")] = DictionaryObject(
            {NameObject("/Self"): forms[0]}
        )
    page[NameObject("/Resources")] = DictionaryObject(
        {
            NameObject("/XObject"): DictionaryObject(
                {NameObject(f"/Fm{i}"): form for i, form in enumerate(forms)}
            )
        }
    )
    stream = DecodedStreamObject()
    stream.set_data(b" ".join(f"q /Fm{i} Do Q".encode() for i in range(copies)))
    page[NameObject("/Contents")] = writer._add_object(stream)
    out = io.BytesIO()
    writer.write(out)
    return out.getvalue()


def test_nonstandard_xobject_is_rejected_before_text_extraction(monkeypatch):
    from pypdf import PdfReader
    from pypdf._page import PageObject

    reader = PdfReader(io.BytesIO(form_pdf(1024)))
    obj = reader.pages[0]["/Resources"]["/XObject"]["/Fm0"]
    obj[NameObject("/Subtype")] = NameObject("/PS")
    writer = PdfWriter(clone_from=reader)
    output = io.BytesIO()
    writer.write(output)

    def forbidden(*args, **kwargs):
        pytest.fail("非标准XObject不应进入文本提取")

    monkeypatch.setattr(PageObject, "extract_text", forbidden)
    with pytest.raises(PdfInspectionError, match="资源"):
        inspect_pdf(output.getvalue())


def test_shared_form_dag_cannot_hide_longer_paths(monkeypatch):
    from pypdf import PdfReader
    from pypdf._page import PageObject

    reader = PdfReader(io.BytesIO(form_pdf(depth=12)))
    objects = reader.pages[0]["/Resources"]["/XObject"]
    chain = []
    child = objects["/Fm0"]
    while True:
        chain.append(child)
        nested = child["/Resources"].get("/XObject")
        if not nested:
            break
        child = nested["/Child"]
    for index, child in enumerate(reversed(chain)):
        objects[NameObject(f"/LeafFirst{index}")] = child.indirect_reference
    outer = objects.pop(NameObject("/Fm0"))
    objects[NameObject("/Fm0")] = outer
    writer = PdfWriter(clone_from=reader)
    output = io.BytesIO()
    writer.write(output)

    def forbidden(*args, **kwargs):
        pytest.fail("共享DAG的深路径不应进入文本提取")

    monkeypatch.setattr(PageObject, "extract_text", forbidden)
    with pytest.raises(PdfInspectionError, match="资源"):
        inspect_pdf(output.getvalue())


def test_form_stream_size_is_checked_before_text_extraction(monkeypatch):
    from pypdf._page import PageObject

    def forbidden(*args, **kwargs):
        pytest.fail("超限Form不应进入文本提取")

    monkeypatch.setattr(PageObject, "extract_text", forbidden)
    with pytest.raises(PdfInspectionError, match="内容流"):
        inspect_pdf(form_pdf(MAX_CONTENT_BYTES + 1, depth=2))


def test_distinct_forms_share_a_decoded_content_budget():
    with pytest.raises(PdfInspectionError, match="内容流"):
        inspect_pdf(form_pdf(MAX_CONTENT_BYTES // 2, copies=9))


@pytest.mark.parametrize("options", [{"depth": 10}, {"cycle": True}, {"copies": 2001}])
def test_excessive_or_cyclic_resources_are_rejected_before_extraction(options, monkeypatch):
    from pypdf._page import PageObject

    def forbidden(*args, **kwargs):
        pytest.fail("危险资源图不应进入文本提取")

    monkeypatch.setattr(PageObject, "extract_text", forbidden)
    with pytest.raises(PdfInspectionError, match="资源"):
        inspect_pdf(form_pdf(**options))


def test_layout_loss_is_visible_and_falls_back_to_plain_text():
    report = inspect_pdf(form_pdf())
    assert report.pages[0].text.strip()
    assert report.pages[0].layout_text == report.pages[0].text
    assert report.pages[0].needs_vision
    assert "layout_text_unavailable" in report.pages[0].warnings


def test_dependency_diagnostics_never_log_document_content(monkeypatch, caplog):
    from pypdf._page import PageObject

    original = PageObject.extract_text
    factory = logging.getLogRecordFactory()

    def noisy(self, *args, **kwargs):
        logging.getLogger("pypdf.new_child").warning("%s", "SYNTHETIC_PRIVATE_MARKER")
        return original(self, *args, **kwargs)

    monkeypatch.setattr(PageObject, "extract_text", noisy)
    with caplog.at_level(logging.WARNING):
        report = inspect_pdf(form_pdf())
        logging.getLogger("pypdf.new_child").warning("outside_inspection")
    assert "SYNTHETIC_PRIVATE_MARKER" not in caplog.text
    assert "parser_diagnostics" in report.warnings
    assert "outside_inspection" in caplog.text
    assert logging.getLogRecordFactory() is factory
    assert logging.getLogger("pypdf").filters == []


def test_diagnostics_are_isolated_between_concurrent_calls(monkeypatch, caplog):
    from pypdf._page import PageObject

    original = PageObject.extract_text
    barrier = Barrier(2)
    factory = logging.getLogRecordFactory()

    def noisy(self, *args, **kwargs):
        barrier.wait(timeout=5)
        logging.getLogger("pypdf.concurrent").warning("PRIVATE_THREAD")
        logging.getLogger("quest.test").warning("unrelated_application_log")
        return original(self, *args, **kwargs)

    monkeypatch.setattr(PageObject, "extract_text", noisy)
    raw = form_pdf()
    with caplog.at_level(logging.WARNING), ThreadPoolExecutor(max_workers=2) as pool:
        reports = list(pool.map(inspect_pdf, [raw, raw]))
    assert all("parser_diagnostics" in report.warnings for report in reports)
    assert "PRIVATE_THREAD" not in caplog.text
    assert "unrelated_application_log" in caplog.text
    assert logging.getLogRecordFactory() is factory


def test_diagnostics_configuration_is_restored_after_parser_error(monkeypatch, caplog):
    from pypdf._page import PageObject

    factory = logging.getLogRecordFactory()

    def broken(*args, **kwargs):
        try:
            raise ValueError("PRIVATE_EXCEPTION")
        except ValueError:
            logging.getLogger("pypdf.failure").exception("PRIVATE_MESSAGE", stack_info=True)
        raise ValueError("PRIVATE_EXCEPTION")

    monkeypatch.setattr(PageObject, "extract_text", broken)
    with caplog.at_level(logging.WARNING), pytest.raises(PdfInspectionError) as error:
        inspect_pdf(form_pdf())
    assert "PRIVATE_" not in caplog.text
    assert "PRIVATE_" not in str(error.value)
    assert logging.getLogRecordFactory() is factory
