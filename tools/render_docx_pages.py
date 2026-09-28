"""docx -> PDF -> page-N.png, one folder per document.

Output layout matches what create_qa_contact_sheets.py expects:
  <out_dir>/<doc-stem>/page-1.png, page-2.png, ...

Usage: python3 render_docx_pages.py <docx_dir> <out_dir> [dpi]
"""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

import fitz  # PyMuPDF

DPI = 150


def convert_to_pdf(docx_path: Path, pdf_dir: Path) -> Path:
    subprocess.run(
        ["soffice", "--headless", "--convert-to", "pdf", "--outdir", str(pdf_dir), str(docx_path)],
        check=True, capture_output=True,
    )
    return pdf_dir / f"{docx_path.stem}.pdf"


def render_pages(pdf_path: Path, out_dir: Path, dpi: int) -> int:
    out_dir.mkdir(parents=True, exist_ok=True)
    doc = fitz.open(pdf_path)
    zoom = dpi / 72
    matrix = fitz.Matrix(zoom, zoom)
    for page_number, page in enumerate(doc, start=1):
        pixmap = page.get_pixmap(matrix=matrix)
        pixmap.save(out_dir / f"page-{page_number}.png")
    count = doc.page_count
    doc.close()
    return count


def main() -> None:
    docx_dir = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    dpi = int(sys.argv[3]) if len(sys.argv) > 3 else DPI
    out_dir.mkdir(parents=True, exist_ok=True)
    docx_files = sorted(p for p in docx_dir.glob("*.docx") if not p.name.startswith("~$"))
    with tempfile.TemporaryDirectory() as tmp:
        pdf_dir = Path(tmp)
        for docx_path in docx_files:
            pdf_path = convert_to_pdf(docx_path, pdf_dir)
            page_count = render_pages(pdf_path, out_dir / docx_path.stem, dpi)
            print(f"{docx_path.name}: {page_count} pages")


if __name__ == "__main__":
    main()
