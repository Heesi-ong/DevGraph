from __future__ import annotations

import copy
import hashlib
import re
import shutil
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

from docx import Document
from docx.enum.section import WD_ORIENT, WD_SECTION
from docx.enum.style import WD_STYLE_TYPE
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_BREAK
from docx.opc.constants import RELATIONSHIP_TYPE
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt, RGBColor
from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "DEVGRAPH_PRODUCT_DESIGN.md"
OUTPUT = ROOT / "docs-word"
MERMAID_CACHE = ROOT / "tools" / "diagrams" / "cache"

FONT_BODY = "Malgun Gothic"  # eastAsia/cs font: universal on Windows Word; "Arial Unicode MS" is a
                             # legacy Office font absent on most modern systems and was rendering
                             # Hangul as tofu boxes under LibreOffice/PDF conversion.
FONT_BODY_LATIN = "Arial"   # ascii/hAnsi companion so Latin text isn't forced into a CJK font.
FONT_CODE = "Menlo"
_LATIN_COMPANION = {FONT_BODY: FONT_BODY_LATIN}
BLACK = "000000"
NAVY = "1F4E79"
PALE_BLUE = "EAF2F8"
PALE_GRAY = "F5F7F9"
BORDER = "D9D9D9"
MID_GRAY = "666666"


@dataclass(frozen=True)
class DocSpec:
    number: int
    filename: str
    title: str
    purpose: str
    decision: str
    body: str
    related: tuple[str, ...]

    @property
    def doc_id(self) -> str:
        return f"DG-DOC-{self.number:02d}"


def read_sections(text: str) -> dict[int, str]:
    matches = list(re.finditer(r"(?m)^##\s+(\d+)\.\s+(.+?)\s*$", text))
    sections: dict[int, str] = {}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        sections[int(match.group(1))] = text[match.start():end].strip()
    return sections


def extract_h3(section: str, prefixes: Iterable[str]) -> str:
    prefixes = tuple(prefixes)
    lines = section.splitlines()
    chunks: list[str] = []
    active = False
    current: list[str] = []
    for line in lines:
        if line.startswith("### "):
            if active and current:
                chunks.append("\n".join(current).strip())
            heading = line[4:].strip()
            active = any(heading.startswith(prefix) for prefix in prefixes)
            current = [line] if active else []
        elif active:
            current.append(line)
    if active and current:
        chunks.append("\n".join(current).strip())
    return "\n\n".join(chunks)


def join_sections(sections: dict[int, str], *numbers: int) -> str:
    return "\n\n".join(sections[n] for n in numbers)


def plain_heading(text: str) -> str:
    text = re.sub(r"[`*_#]", "", text)
    text = re.sub(r"[^0-9A-Za-z가-힣\s]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def set_cell_shading(cell, fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_border(cell, color: str = BORDER, size: str = "6") -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    tc_borders = tc_pr.first_child_found_in("w:tcBorders")
    if tc_borders is None:
        tc_borders = OxmlElement("w:tcBorders")
        tc_pr.append(tc_borders)
    for edge in ("top", "left", "bottom", "right", "insideH", "insideV"):
        tag = f"w:{edge}"
        element = tc_borders.find(qn(tag))
        if element is None:
            element = OxmlElement(tag)
            tc_borders.append(element)
        element.set(qn("w:val"), "single")
        element.set(qn("w:sz"), size)
        element.set(qn("w:color"), color)


def set_cell_margins(cell, top=70, start=100, bottom=70, end=100) -> None:
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for margin, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{margin}"))
        if node is None:
            node = OxmlElement(f"w:{margin}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def set_repeat_table_header(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    tbl_header = OxmlElement("w:tblHeader")
    tbl_header.set(qn("w:val"), "true")
    tr_pr.append(tbl_header)


def prevent_row_split(row) -> None:
    tr_pr = row._tr.get_or_add_trPr()
    cant_split = OxmlElement("w:cantSplit")
    tr_pr.append(cant_split)


def set_run_font(run, name: str, size: float | None = None, color: str | None = None) -> None:
    latin_name = _LATIN_COMPANION.get(name, name)
    run.font.name = latin_name
    if size is not None:
        run.font.size = Pt(size)
    if color is not None:
        run.font.color.rgb = RGBColor.from_string(color)
    r_pr = run._element.get_or_add_rPr()
    r_fonts = r_pr.rFonts
    if r_fonts is None:
        r_fonts = OxmlElement("w:rFonts")
        r_pr.insert(0, r_fonts)
    for attr in ("ascii", "hAnsi"):
        r_fonts.set(qn(f"w:{attr}"), latin_name)
    for attr in ("eastAsia", "cs"):
        r_fonts.set(qn(f"w:{attr}"), name)
    lang = r_pr.find(qn("w:lang"))
    if lang is None:
        lang = OxmlElement("w:lang")
        r_pr.append(lang)
    lang.set(qn("w:eastAsia"), "ko-KR")


def add_inline(paragraph, text: str, *, size: float = 11, color: str = BLACK) -> None:
    pattern = re.compile(r"(\*\*.+?\*\*|`.+?`|\[[^\]]+\]\([^)]+\))")
    cursor = 0
    for match in pattern.finditer(text):
        if match.start() > cursor:
            run = paragraph.add_run(text[cursor:match.start()])
            set_run_font(run, FONT_BODY, size, color)
        token = match.group(0)
        if token.startswith("**"):
            run = paragraph.add_run(token[2:-2])
            run.bold = True
            set_run_font(run, FONT_BODY, size, color)
        elif token.startswith("`"):
            run = paragraph.add_run(token[1:-1])
            set_run_font(run, FONT_CODE, max(9, size - 0.5), color)
        else:
            label, url = re.match(r"\[([^\]]+)\]\(([^)]+)\)", token).groups()
            run = paragraph.add_run(f"{label} ({url})")
            run.underline = True
            set_run_font(run, FONT_BODY, size, color)
        cursor = match.end()
    if cursor < len(text):
        run = paragraph.add_run(text[cursor:])
        set_run_font(run, FONT_BODY, size, color)


def add_page_field(paragraph) -> None:
    run = paragraph.add_run()
    fld_char1 = OxmlElement("w:fldChar")
    fld_char1.set(qn("w:fldCharType"), "begin")
    instr_text = OxmlElement("w:instrText")
    instr_text.set(qn("xml:space"), "preserve")
    instr_text.text = " PAGE "
    fld_char2 = OxmlElement("w:fldChar")
    fld_char2.set(qn("w:fldCharType"), "end")
    run._r.append(fld_char1)
    run._r.append(instr_text)
    run._r.append(fld_char2)
    set_run_font(run, FONT_BODY, 8.5, MID_GRAY)


def set_column_widths(table, rows: list[list[str]], page_width: float = 7.05) -> None:
    count = len(rows[0])
    lengths = []
    for column in range(count):
        values = [len(re.sub(r"[`*]", "", row[column])) for row in rows[:25]]
        peak = max(values) if values else 1
        average = sum(values) / max(1, len(values))
        # peak이 average보다 가중치가 높다 — Endpoint/오류 코드처럼 한두 셀만 유난히 긴
        # 열이 sqrt 감쇠 때문에 지나치게 좁게 잡혀 여러 줄로 쪼개지는 문제를 줄인다.
        lengths.append(max(0.7, min(3.6, 0.55 + (peak ** 0.55) * 0.24 + (average ** 0.5) * 0.06)))
    total = sum(lengths)
    widths = [page_width * value / total for value in lengths]
    if count >= 4:
        widths[0] = min(widths[0], 1.45)
        remainder = page_width - sum(widths)
        widths[-1] += remainder
    for row in table.rows:
        for index, cell in enumerate(row.cells):
            cell.width = Inches(max(0.55, widths[index]))


def add_table(doc: Document, rows: list[list[str]], page_width: float = 7.05) -> None:
    if not rows:
        return
    width = max(len(row) for row in rows)
    normalized = [row + [""] * (width - len(row)) for row in rows]
    table = doc.add_table(rows=len(normalized), cols=width)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    set_column_widths(table, normalized, page_width)
    font_size = 8.5 if width <= 4 else 8.0 if width <= 6 else 7.3
    for row_index, (row, values) in enumerate(zip(table.rows, normalized)):
        prevent_row_split(row)
        if row_index == 0:
            set_repeat_table_header(row)
        for column_index, (cell, value) in enumerate(zip(row.cells, values)):
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            set_cell_border(cell)
            set_cell_margins(cell)
            if row_index == 0:
                set_cell_shading(cell, NAVY)
            elif row_index % 2 == 0:
                set_cell_shading(cell, PALE_BLUE)
            else:
                set_cell_shading(cell, "FFFFFF")
            paragraph = cell.paragraphs[0]
            paragraph.paragraph_format.space_after = Pt(0)
            paragraph.paragraph_format.line_spacing = 1.12
            if column_index == 0 and width <= 4 and len(value) < 24:
                paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
            add_inline(paragraph, value, size=font_size, color="FFFFFF" if row_index == 0 else BLACK)
            for run in paragraph.runs:
                if row_index == 0:
                    run.bold = True
    spacer = doc.add_paragraph()
    spacer.paragraph_format.space_after = Pt(1)


def add_code_block(doc: Document, code: str) -> None:
    paragraph = doc.add_paragraph(style="Code Block")
    paragraph.paragraph_format.keep_together = False
    for index, line in enumerate(code.splitlines() or [""]):
        if index:
            paragraph.add_run().add_break()
        run = paragraph.add_run(line.rstrip())
        set_run_font(run, FONT_CODE, 8.7, BLACK)


def render_mermaid(code: str) -> Path | None:
    """Mermaid 원문을 PNG로 렌더링한다. 내용 해시로 캐싱해 반복 빌드에서 재렌더링하지 않는다.

    mmdc(npx @mermaid-js/mermaid-cli)가 없거나 네트워크가 막혀 실패하면 None을 반환해
    호출부가 코드 블록 표시로 되돌아갈 수 있게 한다 — 그림 렌더링은 빌드를 막지 않는다.
    """
    digest = hashlib.sha256(code.encode("utf-8")).hexdigest()[:16]
    cached = MERMAID_CACHE / f"{digest}.png"
    if cached.exists():
        return cached
    MERMAID_CACHE.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "diagram.mmd"
        src.write_text(code, encoding="utf-8")
        out = Path(tmp) / "diagram.png"
        try:
            subprocess.run(
                ["npx", "-y", "@mermaid-js/mermaid-cli", "-i", str(src), "-o", str(out),
                 "--backgroundColor", "white", "--scale", "3"],
                check=True, capture_output=True, timeout=120,
            )
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError):
            return None
        if not out.exists():
            return None
        shutil.copy(out, cached)
    return cached


def add_mermaid_diagram(doc: Document, code: str, page_width: float, appendix: list[str]) -> None:
    image_path = render_mermaid(code)
    appendix.append(code)
    if image_path is None:
        label = doc.add_paragraph()
        run = label.add_run("MERMAID 표기 (렌더링 실패 — 원문 그대로 표시)")
        run.bold = True
        set_run_font(run, FONT_BODY, 8.5, MID_GRAY)
        add_code_block(doc, code)
        return
    with Image.open(image_path) as image:
        width_px, height_px = image.size
    max_width = min(page_width, 6.5)
    display_width = min(max_width, width_px / 144)
    paragraph = doc.add_paragraph()
    paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
    paragraph.add_run().add_picture(str(image_path), width=Inches(display_width))
    caption = doc.add_paragraph()
    caption.alignment = WD_ALIGN_PARAGRAPH.CENTER
    caption_run = caption.add_run(f"그림 {len(appendix)}. Mermaid 다이어그램 (원문은 부록 참고)")
    set_run_font(caption_run, FONT_BODY, 8.5, MID_GRAY)


def add_markdown(
    doc: Document, markdown: str, page_width: float = 7.05, mermaid_appendix: list[str] | None = None
) -> None:
    lines = markdown.splitlines()
    index = 0
    while index < len(lines):
        raw = lines[index]
        line = raw.rstrip()
        stripped = line.strip()
        if not stripped or stripped == "---":
            index += 1
            continue
        if stripped == "<!--PAGEBREAK-->":
            doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)
            index += 1
            continue
        if stripped.startswith("```"):
            language = stripped[3:].strip()
            code_lines: list[str] = []
            index += 1
            while index < len(lines) and not lines[index].strip().startswith("```"):
                code_lines.append(lines[index])
                index += 1
            code_text = "\n".join(code_lines)
            if language.lower() == "mermaid":
                add_mermaid_diagram(doc, code_text, page_width, mermaid_appendix if mermaid_appendix is not None else [])
                index += 1
                continue
            if language:
                label = doc.add_paragraph()
                label.paragraph_format.space_before = Pt(3)
                label.paragraph_format.space_after = Pt(2)
                run = label.add_run(f"{language.upper()} 표기")
                run.bold = True
                set_run_font(run, FONT_BODY, 8.5, MID_GRAY)
            add_code_block(doc, code_text)
            index += 1
            continue
        if stripped.startswith("|"):
            table_lines: list[str] = []
            while index < len(lines) and lines[index].strip().startswith("|"):
                table_lines.append(lines[index].strip())
                index += 1
            parsed = []
            for table_line in table_lines:
                values = [cell.strip() for cell in table_line.strip("|").split("|")]
                if all(re.fullmatch(r":?-{3,}:?", value.replace(" ", "")) for value in values):
                    continue
                parsed.append(values)
            add_table(doc, parsed, page_width)
            continue
        heading = re.match(r"^(#{2,4})\s+(.+)$", stripped)
        if heading:
            source_level = len(heading.group(1))
            word_level = max(1, source_level - 1)
            title = plain_heading(heading.group(2))
            paragraph = doc.add_heading(title, level=word_level)
            paragraph.paragraph_format.keep_with_next = True
            index += 1
            continue
        bullet = re.match(r"^\s*[-*]\s+(.+)$", line)
        numbered = re.match(r"^\s*\d+\.\s+(.+)$", line)
        quote = re.match(r"^>\s?(.*)$", stripped)
        if bullet:
            paragraph = doc.add_paragraph(style="List Bullet")
            add_inline(paragraph, bullet.group(1))
        elif numbered:
            paragraph = doc.add_paragraph(style="List Number")
            add_inline(paragraph, numbered.group(1))
        elif quote:
            paragraph = doc.add_paragraph()
            paragraph.paragraph_format.left_indent = Inches(0.28)
            paragraph.paragraph_format.right_indent = Inches(0.15)
            paragraph.paragraph_format.space_before = Pt(4)
            paragraph.paragraph_format.space_after = Pt(6)
            add_inline(paragraph, quote.group(1), size=10.5, color=MID_GRAY)
            for run in paragraph.runs:
                run.italic = True
        else:
            paragraph_lines = [stripped]
            index += 1
            while index < len(lines):
                next_line = lines[index].strip()
                if not next_line or next_line == "---" or next_line.startswith(("##", "```", "|", ">")):
                    break
                if re.match(r"^[-*]\s+", next_line) or re.match(r"^\d+\.\s+", next_line):
                    break
                paragraph_lines.append(next_line)
                index += 1
            paragraph = doc.add_paragraph()
            add_inline(paragraph, " ".join(paragraph_lines))
            continue
        index += 1


def configure_styles(doc: Document, landscape: bool = False) -> None:
    section = doc.sections[0]
    if landscape:
        section.orientation = WD_ORIENT.LANDSCAPE
        section.page_width = Inches(11)
        section.page_height = Inches(8.5)
    else:
        section.page_width = Inches(8.5)
        section.page_height = Inches(11)
    section.top_margin = Inches(0.72)
    section.bottom_margin = Inches(0.68)
    section.left_margin = Inches(0.72)
    section.right_margin = Inches(0.72)

    normal = doc.styles["Normal"]
    normal.font.name = FONT_BODY_LATIN
    normal.font.size = Pt(11)
    normal.font.color.rgb = RGBColor.from_string(BLACK)
    normal._element.rPr.rFonts.set(qn("w:eastAsia"), FONT_BODY)
    normal._element.rPr.rFonts.set(qn("w:cs"), FONT_BODY)
    normal.paragraph_format.space_after = Pt(5)
    normal.paragraph_format.line_spacing = 1.22

    title = doc.styles["Title"]
    title.font.name = FONT_BODY_LATIN
    title.font.size = Pt(24)
    title.font.bold = True
    title.font.color.rgb = RGBColor.from_string(BLACK)
    title._element.rPr.rFonts.set(qn("w:eastAsia"), FONT_BODY)
    title._element.rPr.rFonts.set(qn("w:cs"), FONT_BODY)
    title.paragraph_format.space_after = Pt(12)
    title_p_pr = title._element.get_or_add_pPr()
    for tag in ("w:pBdr", "w:shd"):
        node = title_p_pr.find(qn(tag))
        if node is not None:
            title_p_pr.remove(node)

    heading_sizes = {1: 16, 2: 13, 3: 11.5}
    for level, size in heading_sizes.items():
        style = doc.styles[f"Heading {level}"]
        style.font.name = FONT_BODY_LATIN
        style.font.size = Pt(size)
        style.font.bold = True
        style.font.color.rgb = RGBColor.from_string(BLACK)
        style._element.rPr.rFonts.set(qn("w:eastAsia"), FONT_BODY)
        style._element.rPr.rFonts.set(qn("w:cs"), FONT_BODY)
        style.paragraph_format.space_before = Pt(14 if level == 1 else 10)
        style.paragraph_format.space_after = Pt(5)
        style.paragraph_format.keep_with_next = True

    for list_name in ("List Bullet", "List Number"):
        style = doc.styles[list_name]
        style.font.name = FONT_BODY_LATIN
        style.font.size = Pt(11)
        style._element.rPr.rFonts.set(qn("w:eastAsia"), FONT_BODY)
        style._element.rPr.rFonts.set(qn("w:cs"), FONT_BODY)
        style.paragraph_format.space_after = Pt(3)
        style.paragraph_format.line_spacing = 1.16

    if "Code Block" not in [style.name for style in doc.styles]:
        code_style = doc.styles.add_style("Code Block", WD_STYLE_TYPE.PARAGRAPH)
    else:
        code_style = doc.styles["Code Block"]
    code_style.font.name = FONT_CODE
    code_style.font.size = Pt(8.7)
    code_style.font.color.rgb = RGBColor.from_string(BLACK)
    code_style._element.rPr.rFonts.set(qn("w:eastAsia"), FONT_CODE)
    code_style.paragraph_format.left_indent = Inches(0.18)
    code_style.paragraph_format.right_indent = Inches(0.12)
    code_style.paragraph_format.space_before = Pt(3)
    code_style.paragraph_format.space_after = Pt(7)
    code_style.paragraph_format.line_spacing = 1.05


def add_footer(doc: Document, doc_id: str) -> None:
    footer = doc.sections[0].footer
    table = footer.add_table(rows=1, cols=2, width=Inches(7.0))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = False
    left, right = table.rows[0].cells
    left.width = Inches(5.8)
    right.width = Inches(1.2)
    for cell in (left, right):
        cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
        set_cell_margins(cell, top=20, bottom=20, start=0, end=0)
    left_p = left.paragraphs[0]
    left_p.paragraph_format.space_after = Pt(0)
    left_run = left_p.add_run(f"DevGraph 프로젝트 설계 기준 문서  {doc_id}")
    set_run_font(left_run, FONT_BODY, 8.5, MID_GRAY)
    right_p = right.paragraphs[0]
    right_p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    right_p.paragraph_format.space_after = Pt(0)
    add_page_field(right_p)


def add_cover(doc: Document, spec: DocSpec) -> None:
    spacer = doc.add_paragraph()
    spacer.paragraph_format.space_after = Pt(28)
    title = doc.add_paragraph(style="Title")
    title.alignment = WD_ALIGN_PARAGRAPH.LEFT
    title_run = title.add_run(spec.title)
    set_run_font(title_run, FONT_BODY, 24, BLACK)
    title_run.bold = True
    subtitle = doc.add_paragraph()
    subtitle.paragraph_format.space_after = Pt(22)
    run = subtitle.add_run("DevGraph 프로젝트 설계 기준 문서")
    set_run_font(run, FONT_BODY, 12, MID_GRAY)

    metadata = [
        ["문서 ID", spec.doc_id],
        ["문서 상태", "기준안"],
        ["버전", "1.0"],
        ["기준일", "2026년 9월 28일"],
        ["적용 범위", "DevGraph MVP 및 1차 서비스"],
    ]
    table = doc.add_table(rows=len(metadata), cols=2)
    table.alignment = WD_TABLE_ALIGNMENT.LEFT
    table.autofit = False
    for row, values in zip(table.rows, metadata):
        row.cells[0].width = Inches(1.35)
        row.cells[1].width = Inches(5.45)
        for index, (cell, value) in enumerate(zip(row.cells, values)):
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            set_cell_border(cell)
            set_cell_margins(cell, top=90, start=120, bottom=90, end=120)
            set_cell_shading(cell, PALE_BLUE if index == 0 else "FFFFFF")
            paragraph = cell.paragraphs[0]
            paragraph.paragraph_format.space_after = Pt(0)
            add_inline(paragraph, value, size=10)
            if index == 0:
                paragraph.runs[0].bold = True

    doc.add_paragraph()
    doc.add_heading("문서 목적", level=1)
    paragraph = doc.add_paragraph()
    add_inline(paragraph, spec.purpose)
    doc.add_heading("핵심 결정", level=1)
    paragraph = doc.add_paragraph()
    add_inline(paragraph, spec.decision)
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)


def body_headings(markdown: str) -> list[str]:
    result = []
    for line in markdown.splitlines():
        match = re.match(r"^##\s+(.+)$", line.strip())
        if match:
            result.append(plain_heading(match.group(1)))
    return result


def add_front_matter(doc: Document, spec: DocSpec) -> None:
    doc.add_heading("문서 구성", level=1)
    for heading in body_headings(spec.body):
        paragraph = doc.add_paragraph(style="List Bullet")
        add_inline(paragraph, heading)
    doc.add_heading("문서 책임 경계", level=1)
    paragraph = doc.add_paragraph()
    add_inline(paragraph, spec.purpose)
    if spec.related:
        doc.add_heading("관련 기준 문서", level=1)
        for related in spec.related:
            paragraph = doc.add_paragraph(style="List Bullet")
            add_inline(paragraph, related)
    doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)


DOC_ID_PATTERN = re.compile(r"DG-DOC-\d{2}")


def _clone_run_props(template_run, rpr: OxmlElement) -> None:
    template_rpr = template_run._r.find(qn("w:rPr"))
    if template_rpr is not None:
        for child in template_rpr:
            rpr.append(copy.deepcopy(child))


def _make_plain_run(text: str, template_run) -> OxmlElement:
    r = OxmlElement("w:r")
    rpr = OxmlElement("w:rPr")
    _clone_run_props(template_run, rpr)
    r.append(rpr)
    t = OxmlElement("w:t")
    t.set(qn("xml:space"), "preserve")
    t.text = text
    r.append(t)
    return r


def _make_hyperlink_run(paragraph, text: str, target_filename: str, template_run) -> OxmlElement:
    r_id = paragraph.part.relate_to(target_filename, RELATIONSHIP_TYPE.HYPERLINK, is_external=True)
    hyperlink = OxmlElement("w:hyperlink")
    hyperlink.set(qn("r:id"), r_id)
    r = OxmlElement("w:r")
    rpr = OxmlElement("w:rPr")
    _clone_run_props(template_run, rpr)
    for tag in ("w:color", "w:u"):
        existing = rpr.find(qn(tag))
        if existing is not None:
            rpr.remove(existing)
    color = OxmlElement("w:color")
    color.set(qn("w:val"), "1155CC")
    rpr.append(color)
    underline = OxmlElement("w:u")
    underline.set(qn("w:val"), "single")
    rpr.append(underline)
    r.append(rpr)
    t = OxmlElement("w:t")
    t.set(qn("xml:space"), "preserve")
    t.text = text
    r.append(t)
    hyperlink.append(r)
    return hyperlink


def _linkify_run(paragraph, run, doc_id_to_filename: dict[str, str]) -> None:
    text = run.text
    if not text:
        return
    matches = [m for m in DOC_ID_PATTERN.finditer(text) if m.group() in doc_id_to_filename]
    if not matches:
        return
    original = run._r
    new_elements = []
    cursor = 0
    for match in matches:
        if match.start() > cursor:
            new_elements.append(_make_plain_run(text[cursor:match.start()], run))
        new_elements.append(
            _make_hyperlink_run(paragraph, match.group(), doc_id_to_filename[match.group()], run)
        )
        cursor = match.end()
    if cursor < len(text):
        new_elements.append(_make_plain_run(text[cursor:], run))
    for element in new_elements:
        original.addprevious(element)
    original.getparent().remove(original)


def linkify_doc_references(doc: Document, doc_id_to_filename: dict[str, str]) -> None:
    """본문·표 안의 'DG-DOC-06' 같은 문서 ID 언급을 실제 파일로 가는 하이퍼링크로 바꾼다.

    문서 인덱스의 기준 소유/파생 문서 열, 각 문서 앞머리의 '관련 기준 문서' 목록이 대상이다.
    같은 폴더의 다른 .docx로의 상대경로 링크이므로 15개 파일을 한 폴더에 같이 두고 열어야 동작한다.
    """
    paragraphs = list(doc.paragraphs)
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                paragraphs.extend(cell.paragraphs)
    for paragraph in paragraphs:
        for run in list(paragraph.runs):
            _linkify_run(paragraph, run, doc_id_to_filename)


def create_doc(spec: DocSpec, doc_id_to_filename: dict[str, str]) -> Path:
    doc = Document()
    configure_styles(doc, landscape=(spec.number == 0))
    add_footer(doc, spec.doc_id)
    properties = doc.core_properties
    properties.title = spec.title
    properties.subject = "DevGraph 프로젝트 설계"
    properties.author = ""
    properties.keywords = "DevGraph 설계 문서"
    properties.comments = ""
    add_cover(doc, spec)
    add_front_matter(doc, spec)
    # 절 번호(예: "14.2")는 그대로 둔다 — 이전에 개별 문서에서 번호를 지웠더니 본문의
    # "§14.2" 상호 참조가 가리키는 대상이 사라져 문서 내부 참조가 깨졌다. 번호를 유지해야
    # 통합 문서(DEVGRAPH_PRODUCT_DESIGN.md)와 분리 문서 양쪽에서 §참조가 그대로 유효하다.
    page_width = 9.56 if spec.number == 0 else 7.05
    mermaid_appendix: list[str] = []
    add_markdown(doc, spec.body, page_width, mermaid_appendix)
    if mermaid_appendix:
        doc.add_paragraph().add_run().add_break(WD_BREAK.PAGE)
        doc.add_heading("부록. Mermaid 원문", level=1)
        note = doc.add_paragraph()
        add_inline(note, "본문의 그림은 아래 Mermaid 원문을 렌더링한 것이다. 다이어그램 수정은 이 원문(통합 설계서의 해당 절)을 고친 뒤 재생성한다.")
        for order, code in enumerate(mermaid_appendix, start=1):
            label = doc.add_paragraph()
            run = label.add_run(f"그림 {order} 원문")
            run.bold = True
            set_run_font(run, FONT_BODY, 8.5, MID_GRAY)
            add_code_block(doc, code)
    linkify_doc_references(doc, doc_id_to_filename)
    output_path = OUTPUT / spec.filename
    doc.save(output_path)
    return output_path


def build_specs(sections: dict[int, str]) -> list[DocSpec]:
    docs_catalog = """## 문서 체계

DevGraph 설계 문서는 제품 의사결정부터 구현과 운영까지 책임이 겹치지 않도록 14개 기준 문서와 1개 인덱스로 구성한다. 변경이 여러 문서에 영향을 줄 때는 문서 인덱스의 영향 관계를 확인하고 관련 문서를 함께 갱신한다. `기준 소유 문서`는 해당 주제의 최종 결정권을 갖는 문서이며, `파생 문서`는 그 결정을 인용만 하고 직접 수정하지 않는 문서다.

문서 ID와 기준 소유/파생 문서 열의 `DG-DOC-XX`는 해당 파일로 바로 이동하는 링크다. 15개 파일을 이 폴더 안에 함께 두었을 때만 정상 동작하며, 파일을 개별적으로 옮기거나 다른 사람에게 한 개만 보내면 링크가 깨진다.

| 순서 | 문서 ID | 문서명 | 주 책임 | 기준 소유 문서 | 파생 문서 | 최종 승인자 | 마지막 검토일 |
|---|---|---|---|---|---|---|---|
| 1 | DG-DOC-01 | 프로젝트 개요서 | 제품 목적, 대상 사용자, 가치와 차별점 | DG-DOC-01 | DG-DOC-13 | 미지정 | 2026-09-28 |
| 2 | DG-DOC-02 | 제품 요구사항 정의서 | 사용자 시나리오, 기능 요구사항, MVP 범위 | DG-DOC-02 | DG-DOC-13, DG-DOC-14 | 미지정 | 2026-09-28 |
| 3 | DG-DOC-03 | UX IA 화면 설계서 | 정보 구조, 화면, 공통 UX 규칙 | DG-DOC-03 | DG-DOC-08 | 미지정 | 2026-09-28 |
| 4 | DG-DOC-04 | 도메인 관계 모델 설계서 | Aggregate, Node 타입, Relation 의미 | DG-DOC-04 | DG-DOC-05, DG-DOC-06, DG-DOC-10 | 미지정 | 2026-09-28 |
| 5 | DG-DOC-05 | 데이터베이스 설계서 | ERD, Table, Key, Index, Constraint | DG-DOC-05 | DG-DOC-06, DG-DOC-07 | 미지정 | 2026-09-28 |
| 6 | DG-DOC-06 | API 명세서 | REST 규칙, Endpoint, 요청, 응답과 오류 | DG-DOC-06 | DG-DOC-07, DG-DOC-08 | 미지정 | 2026-09-28 |
| 7 | DG-DOC-07 | 백엔드 아키텍처 설계서 | Package, module, transaction, migration | DG-DOC-07 | - | 미지정 | 2026-09-28 |
| 8 | DG-DOC-08 | 프론트엔드 아키텍처 설계서 | 구조, 상태, Graph와 Editor UI 전략 | DG-DOC-08 | - | 미지정 | 2026-09-28 |
| 9 | DG-DOC-09 | 인증 보안 설계서 | 인증, 데이터 격리, 웹 보안, secret 정책 | DG-DOC-09 | DG-DOC-05, DG-DOC-06 | 미지정 | 2026-09-28 |
| 10 | DG-DOC-10 | 검색 지식그래프 설계서 | 검색 ranking, Graph query, 확장 기준 | DG-DOC-10 | DG-DOC-05, DG-DOC-06, DG-DOC-08 | 미지정 | 2026-09-28 |
| 11 | DG-DOC-11 | 테스트 전략서 | 테스트 계층, 계약, fixture와 수동 QA | DG-DOC-11 | - | 미지정 | 2026-09-28 |
| 12 | DG-DOC-12 | 인프라 운영 비기능 설계서 | 배포, 로그, backup, 삭제, NFR | DG-DOC-12 | - | 미지정 | 2026-09-28 |
| 13 | DG-DOC-13 | 개발 로드맵 완료기준서 | Phase, release gate, Definition of Done | DG-DOC-13 | - | 미지정 | 2026-09-28 |
| 14 | DG-DOC-14 | 고도화 확장 위험관리서 | 우선순위, AI, 팀 확장, 과설계, 위험 | DG-DOC-14 | - | 미지정 | 2026-09-28 |

`최종 승인자`가 "미지정"인 항목은 아직 실제 승인 절차가 없다는 뜻이며, 구현 착수 전 담당자를 배정해 이 표만 갱신한다.

## 권장 읽기 순서

처음 참여하는 개발자는 프로젝트 개요서, 제품 요구사항 정의서, 도메인 관계 모델 설계서를 먼저 읽는다. Backend 개발자는 데이터베이스, API, Backend, 인증 보안, 검색 지식그래프 설계서를 이어서 읽는다. Frontend 개발자는 UX IA, API, Frontend, 검색 지식그래프 설계서를 읽는다. 배포 전에는 테스트, 인프라 운영, 로드맵 완료기준서를 공통으로 확인한다.

## 문서 변경 규칙

- 제품 범위 또는 우선순위가 바뀌면 DG-DOC-01, DG-DOC-02, DG-DOC-13을 함께 확인한다.
- Node 또는 Relation 규칙이 바뀌면 DG-DOC-04, DG-DOC-05, DG-DOC-06, DG-DOC-10을 함께 확인한다.
- 인증이나 Workspace 격리 규칙이 바뀌면 DG-DOC-05, DG-DOC-06, DG-DOC-07, DG-DOC-09, DG-DOC-11을 함께 확인한다.
- API 계약이 바뀌면 Backend와 Frontend 구현 전에 DG-DOC-06을 먼저 갱신한다.
- 운영 제한이나 용량 정책이 바뀌면 DG-DOC-12와 관련 API 오류 계약을 함께 갱신한다.
- 구현과 문서가 충돌하면 승인된 최신 문서 결정을 우선하되, 실제 동작과 차이가 생긴 즉시 문서 또는 구현을 같은 변경 단위에서 정정한다.

## 문서 상태 기준

기준안은 구현을 시작할 수 있는 승인 대상 상태다. 구현 중 발견한 변경은 근거, 영향 범위, migration 또는 호환성 계획을 기록한 뒤 반영한다. 실제 검증 수치가 없는 성능과 사용성 값은 달성 결과가 아니라 검증 목표로 해석한다.
"""

    relation_semantics = (
        "## 도메인 모델\n\n" + sections[11].split("\n", 1)[1].strip()
        + "\n\n## 관계 의미 모델\n\n"
        + extract_h3(sections[13], ("13.1", "13.2"))
    )
    search_graph = "\n\n".join([
        "## 검색 기능 요구사항\n\n" + extract_h3(sections[9], ("9.5",)),
        "## 그래프 기능 요구사항\n\n" + extract_h3(sections[9], ("9.4",)),
        "## 그래프 저장과 전환 기준\n\n" + extract_h3(sections[13], ("13.3", "13.4")),
        "## 검색 구현 전략\n\n" + extract_h3(sections[15], ("15.4",)),
        "## 그래프 사용자 인터페이스 전략\n\n" + extract_h3(sections[16], ("16.3",)),
    ])

    return [
        DocSpec(0, "00-문서_인덱스.docx", "DevGraph 설계 문서 인덱스",
                "설계 문서의 구성, 책임 경계, 읽기 순서와 변경 영향 관계를 정의한다.",
                "제품, 기술, 운영 결정을 문서별로 분리하되 Node, Relation, 인증처럼 교차 영향이 큰 변경은 관련 문서를 함께 갱신한다.",
                docs_catalog, tuple()),
        DocSpec(1, "01-프로젝트_개요서.docx", "DevGraph 프로젝트 개요서",
                "제품이 해결하는 문제, 대상 사용자, 핵심 가치, 차별점과 주요 기술 결정을 정의한다.",
                "개인용 Developer Knowledge Base로 시작하고 저장보다 검색과 재사용을 우선한다.",
                join_sections(sections, 0, 1, 2, 3, 4, 5, 6),
                ("DG-DOC-02 제품 요구사항 정의서", "DG-DOC-13 개발 로드맵 완료기준서")),
        DocSpec(2, "02-제품_요구사항_정의서.docx", "DevGraph 제품 요구사항 정의서",
                "사용자 시나리오, 기능별 요구사항, 우선순위, MVP 범위와 출시 조건을 정의한다.",
                "MVP는 Concept, Note, Snippet, Relation, Graph, Search의 회수와 재사용 흐름을 검증하고 Error, Solution, Project는 1차 서비스에서 완성한다.",
                join_sections(sections, 7, 9, 18),
                ("DG-DOC-03 UX IA 화면 설계서", "DG-DOC-04 도메인 관계 모델 설계서", "DG-DOC-13 개발 로드맵 완료기준서")),
        DocSpec(3, "03-UX_IA_화면_설계서.docx", "DevGraph UX IA 화면 설계서",
                "서비스 정보 구조, 전역 내비게이션, 화면별 목적과 행동, 공통 UX 규칙을 정의한다.",
                "목록과 검색을 주 사용 경로로 두고 Graph는 중심 Node와 제한된 depth를 탐색하는 보조 화면으로 설계한다.",
                join_sections(sections, 8, 10),
                ("DG-DOC-02 제품 요구사항 정의서", "DG-DOC-08 프론트엔드 아키텍처 설계서", "DG-DOC-10 검색 지식그래프 설계서")),
        DocSpec(4, "04-도메인_관계_모델_설계서.docx", "DevGraph 도메인 관계 모델 설계서",
                "Aggregate의 책임, Knowledge Node 타입, 상태와 삭제 정책, 시스템 및 사용자 Relation의 의미를 정의한다.",
                "모든 연결 대상은 Knowledge Node 정체성을 공유하고 관계는 한 방향 Edge와 inverse label로 표현한다.",
                relation_semantics,
                ("DG-DOC-05 데이터베이스 설계서", "DG-DOC-06 API 명세서", "DG-DOC-10 검색 지식그래프 설계서")),
        DocSpec(5, "05-데이터베이스_설계서.docx", "DevGraph 데이터베이스 설계서",
                "PostgreSQL ERD, Entity 목적, Column, Key, Index, Unique Constraint와 삭제 규칙을 정의한다.",
                "Workspace를 모든 사용자 콘텐츠의 격리 경계로 두고 Relation의 source와 target이 같은 Workspace인지 데이터베이스에서도 보장한다.",
                sections[12],
                ("DG-DOC-04 도메인 관계 모델 설계서", "DG-DOC-07 백엔드 아키텍처 설계서", "DG-DOC-09 인증 보안 설계서")),
        DocSpec(6, "06-API_명세서.docx", "DevGraph API 명세서",
                "REST API 공통 규칙, 인증, Knowledge, Snippet, Relation, Graph, Search, Project와 데이터 API 계약을 정의한다.",
                "모든 API는 인증 사용자의 active Workspace를 서버에서 결정하며 오류 코드와 optimistic locking 충돌을 표준 형식으로 반환한다.",
                sections[14],
                ("DG-DOC-02 제품 요구사항 정의서", "DG-DOC-07 백엔드 아키텍처 설계서", "DG-DOC-08 프론트엔드 아키텍처 설계서", "DG-DOC-09 인증 보안 설계서")),
        DocSpec(7, "07-백엔드_아키텍처_설계서.docx", "DevGraph 백엔드 아키텍처 설계서",
                "Spring Boot 모듈형 모놀리스의 Package, 의존 방향, Transaction, 동시성, Search와 Migration 규칙을 정의한다.",
                "기능별 Package와 내부 계층을 사용하고 다른 모듈의 Repository에 직접 접근하지 않는다.",
                sections[15],
                ("DG-DOC-05 데이터베이스 설계서", "DG-DOC-06 API 명세서", "DG-DOC-09 인증 보안 설계서", "DG-DOC-11 테스트 전략서")),
        DocSpec(8, "08-프론트엔드_아키텍처_설계서.docx", "DevGraph 프론트엔드 아키텍처 설계서",
                "React와 TypeScript 구조, 의존 규칙, 상태 관리, Graph와 Code Editor 구현 전략을 정의한다.",
                "축약형 Feature 중심 구조를 사용하고 서버 상태, URL 상태, Graph 일시 상태를 서로 다른 책임으로 관리한다.",
                sections[16],
                ("DG-DOC-03 UX IA 화면 설계서", "DG-DOC-06 API 명세서", "DG-DOC-10 검색 지식그래프 설계서")),
        DocSpec(9, "09-인증_보안_설계서.docx", "DevGraph 인증 보안 설계서",
                "Workspace 데이터 격리, JWT와 Refresh Session, 웹 보안, URL, Snippet secret, Rate Limit와 Export 보안을 정의한다.",
                "짧은 Access JWT와 회전형 Refresh Session을 사용하고 Repository와 데이터베이스 Constraint에서 Workspace 경계를 중복 검증한다.",
                sections[17],
                ("DG-DOC-05 데이터베이스 설계서", "DG-DOC-06 API 명세서", "DG-DOC-11 테스트 전략서", "DG-DOC-12 인프라 운영 비기능 설계서")),
        DocSpec(10, "10-검색_지식그래프_설계서.docx", "DevGraph 검색 지식그래프 설계서",
                "통합 검색 대상과 Ranking, Graph traversal와 응답 상한, PostgreSQL 적합성, Graph UI와 전환 기준을 정의한다.",
                "PostgreSQL FTS와 trigram 검색, 최대 3 depth 인접 목록 탐색으로 시작하고 측정된 한계가 있을 때만 별도 검색 또는 Graph 저장소를 도입한다.",
                search_graph,
                ("DG-DOC-04 도메인 관계 모델 설계서", "DG-DOC-05 데이터베이스 설계서", "DG-DOC-06 API 명세서", "DG-DOC-08 프론트엔드 아키텍처 설계서")),
        DocSpec(11, "11-테스트_전략서.docx", "DevGraph 테스트 전략서",
                "Domain, Application, Integration, API, Frontend, E2E와 비기능 테스트의 책임과 필수 자동화 계약을 정의한다.",
                "인증과 사용자 데이터 격리, Relation 정합성, Graph 상한, Search Ranking, Snippet Version을 출시 전 자동화한다.",
                sections[20],
                ("DG-DOC-02 제품 요구사항 정의서", "DG-DOC-06 API 명세서", "DG-DOC-09 인증 보안 설계서", "DG-DOC-13 개발 로드맵 완료기준서")),
        DocSpec(12, "12-인프라_운영_비기능_설계서.docx", "DevGraph 인프라 운영 비기능 설계서",
                "Docker 배포, 환경 변수, 로그, Health Check, Backup, 삭제, 용량, 배포 절차와 비기능 목표를 정의한다.",
                "초기 운영은 Frontend, Backend, PostgreSQL의 단순 구성을 사용하고 복구 훈련과 측정 결과를 출시 근거로 남긴다.",
                sections[21] + "\n\n" + sections[22],
                ("DG-DOC-09 인증 보안 설계서", "DG-DOC-11 테스트 전략서", "DG-DOC-13 개발 로드맵 완료기준서")),
        DocSpec(13, "13-개발_로드맵_완료기준서.docx", "DevGraph 개발 로드맵 완료기준서",
                "Phase별 목표와 Backend, Frontend, DB, Test 작업, Release Gate, 최종 Workflow와 구현 시작 조건을 정의한다.",
                "Auth부터 운영 안정화까지 Phase 완료 조건을 통과한 뒤 다음 대형 기능으로 확장하며 1차 서비스는 전체 재사용 Workflow로 판정한다.",
                join_sections(sections, 19, 27, 28),
                ("DG-DOC-02 제품 요구사항 정의서", "DG-DOC-11 테스트 전략서", "DG-DOC-12 인프라 운영 비기능 설계서")),
        DocSpec(14, "14-고도화_확장_위험관리서.docx", "DevGraph 고도화 확장 위험관리서",
                "MVP 이후 기능 우선순위, AI 도입 원칙, SaaS와 Team 확장, 기술적 과설계와 위험 대응을 정의한다.",
                "기능과 기술은 실제 사용자 문제와 측정된 병목이 있을 때 추가하며 개인용과 Team 기능을 별도 단계로 유지한다.",
                join_sections(sections, 23, 24, 25, 26, 29),
                ("DG-DOC-01 프로젝트 개요서", "DG-DOC-02 제품 요구사항 정의서", "DG-DOC-13 개발 로드맵 완료기준서")),
    ]


def main() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    sections = read_sections(source)
    expected = set(range(30))
    missing = expected - set(sections)
    if missing:
        raise RuntimeError(f"통합 설계서 섹션 누락: {sorted(missing)}")
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    OUTPUT.mkdir(parents=True)
    specs = build_specs(sections)
    if len(specs) != 15:
        raise RuntimeError(f"문서 수 오류: {len(specs)}")
    doc_id_to_filename = {spec.doc_id: spec.filename for spec in specs}
    outputs = [create_doc(spec, doc_id_to_filename) for spec in specs]
    for path in outputs:
        print(path)


if __name__ == "__main__":
    main()
