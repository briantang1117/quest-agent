"""离线合成考纲，不使用用户附件或真实服务。"""

import io

from pypdf import PdfWriter
from pypdf.generic import (
    ArrayObject,
    DecodedStreamObject,
    DictionaryObject,
    NameObject,
    NumberObject,
    TextStringObject,
)


def syllabus_pdf(*texts, labels=None):
    writer = PdfWriter()
    cid = DictionaryObject(
        {
            NameObject("/Type"): NameObject("/Font"),
            NameObject("/Subtype"): NameObject("/CIDFontType0"),
            NameObject("/BaseFont"): NameObject("/STSong-Light"),
            NameObject("/CIDSystemInfo"): DictionaryObject(
                {
                    NameObject("/Registry"): TextStringObject("Adobe"),
                    NameObject("/Ordering"): TextStringObject("GB1"),
                    NameObject("/Supplement"): NumberObject(4),
                }
            ),
        }
    )
    font = writer._add_object(
        DictionaryObject(
            {
                NameObject("/Type"): NameObject("/Font"),
                NameObject("/Subtype"): NameObject("/Type0"),
                NameObject("/BaseFont"): NameObject("/STSong-Light"),
                NameObject("/Encoding"): NameObject("/UniGB-UCS2-H"),
                NameObject("/DescendantFonts"): ArrayObject([writer._add_object(cid)]),
            }
        )
    )
    for text in texts:
        page = writer.add_blank_page(width=595, height=842)
        page[NameObject("/Resources")] = DictionaryObject(
            {NameObject("/Font"): DictionaryObject({NameObject("/F1"): font})}
        )
        stream = DecodedStreamObject()
        commands = ["BT /F1 10 Tf 40 800 Td"]
        for line in text.splitlines():
            commands.append(f"<{line.encode('utf-16-be').hex()}> Tj 0 -12 Td")
        stream.set_data(("\n".join(commands) + "\nET").encode())
        page[NameObject("/Contents")] = writer._add_object(stream)
    if labels:
        writer._root_object[NameObject("/PageLabels")] = DictionaryObject(
            {NameObject("/Nums"): ArrayObject(labels)}
        )
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


CAREER_PAGES = (
    "事业单位公开招聘分类考试公共科目笔试考试大纲\n"
    "第三部分 综合管理类（A类）\n一、职业能力倾向测验\n"
    "（一）考试性质和目标\n测查从事管理工作所需的基本能力。\n"
    "（二）考试内容\n要求掌握常识并理解文字材料，后续内容跨页\n— I —",
    "继续说明分析与判断的能力要求。\n【例题】\n阅读材料，选择正确答案。\n"
    "A. 选项甲\nB. 选项乙\n参考答案：A\n解析：合成例题。\n"
    "二、综合应用能力\n要求撰写分析材料。\n— II —",
    "第四部分 社会科学专技类（B类）\n一、职业能力倾向测验\n要求理解社会科学材料。\n— 1 —",
)

SHANGHAI_PAGES = (
    "上海市考试录用公务员公共科目考试大纲\n一、行政职业能力测验\n"
    "（一）考试内容\n主要测查言语理解和判断推理。\n"
    "【例题】\n合成题干\n答案：B\n二、申论\n要求完成文章写作。\n1",
)
