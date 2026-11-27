"""Page-mode recognition (mode=page): Mathpix line_data → one bboxed line per handwritten line.

The default run mocks Mathpix with a saved JSON response (tests/fixtures/mathpix_page_response.json)
so CI needs no API key. `pytest --live` instead hits the real API with the fixture photo.
"""
import asyncio
import json
import pathlib

import httpx
import pytest

from app.config import settings
from app.services import recognition

FIXTURES = pathlib.Path(__file__).parent / "fixtures"
IMAGE = FIXTURES / "page_five_lines.jpg"
SAVED = json.loads((FIXTURES / "mathpix_page_response.json").read_text())
FORMULA_IMAGE = FIXTURES / "formula_int2x.jpg"
SAVED_FORMULA = json.loads((FIXTURES / "mathpix_formula_response.json").read_text())


def _assert_five_lines_in_order(page: recognition.PageResult):
    assert len(page.lines) == 5, [ln.latex for ln in page.lines]
    ys = [ln.bbox[1] for ln in page.lines]
    assert ys == sorted(ys)                     # top-to-bottom
    for ln in page.lines:
        x0, y0, x1, y1 = ln.bbox
        assert 0 <= x0 < x1 <= page.image_w, ln.bbox
        assert 0 <= y0 < y1 <= page.image_h, ln.bbox
        assert "$" not in ln.latex              # delimiters stripped


def test_page_mode_returns_five_ordered_lines_mocked(monkeypatch):
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    monkeypatch.setattr(settings, "MATHPIX_APP_ID", "x")
    monkeypatch.setattr(settings, "MATHPIX_APP_KEY", "y")

    async def fake_post(self, url, **kwargs):
        return httpx.Response(200, json=SAVED)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    page = asyncio.run(recognition.recognize_page(IMAGE.read_bytes()))
    _assert_five_lines_in_order(page)
    # The non-math header line was dropped; the "text with $…$" line was kept.
    assert any("e^x" in ln.latex for ln in page.lines)


def test_parse_line_data_sorts_and_filters():
    lines = recognition.parse_line_data(SAVED)
    assert len(lines) == 5
    assert lines[0].latex.startswith(r"\int")
    assert lines[-1].latex.startswith(r"\lim")


@pytest.mark.live
def test_page_mode_live(monkeypatch):
    """Opt-in (`pytest --live`): hit the real Mathpix API with the fixture photo."""
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    if not (settings.MATHPIX_APP_ID and settings.MATHPIX_APP_KEY):
        pytest.skip("no Mathpix credentials configured")
    page = asyncio.run(recognition.recognize_page(IMAGE.read_bytes()))
    assert len(page.lines) >= 3


# --- Real notebook page (ruled paper, margin line): Mathpix collapses it into one block ---

NOTEBOOK_IMAGE = FIXTURES / "five_lines_notebook.jpg"
SAVED_NOTEBOOK_LINES = json.loads((FIXTURES / "mathpix_notebook_line_response.json").read_text())
SAVED_NOTEBOOK_WORDS = json.loads((FIXTURES / "mathpix_notebook_word_response.json").read_text())


def test_notebook_page_returns_five_lines_mocked(monkeypatch, tmp_path):
    """
    The Pixel's notebook photo: line_data comes back as ONE `\begin{array}` entry in which Mathpix
    merged two handwritten lines (4 rows), so the word_data fallback has to supply the 5 lines.
    """
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    monkeypatch.setattr(settings, "MATHPIX_APP_ID", "x")
    monkeypatch.setattr(settings, "MATHPIX_APP_KEY", "y")
    monkeypatch.setattr(settings, "STORAGE_DIR", tmp_path)
    payloads = []

    async def fake_post(self, url, **kwargs):
        payloads.append(kwargs["json"])
        words = kwargs["json"].get("include_word_data")
        return httpx.Response(200, json=SAVED_NOTEBOOK_WORDS if words else SAVED_NOTEBOOK_LINES)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    # What Mathpix actually returned for this photo: a single entry, 4 rows after splitting.
    assert len(SAVED_NOTEBOOK_LINES["line_data"]) == 1
    assert len(recognition.parse_line_data(SAVED_NOTEBOOK_LINES)) == 4

    page = asyncio.run(recognition.recognize_page(NOTEBOOK_IMAGE.read_bytes()))
    _assert_five_lines_in_order(page)
    starts = [r"\int", r"\frac{d}{d x}", r"\frac{e^{x}}", r"\sum", r"\lim"]
    for ln, start in zip(page.lines, starts):
        assert ln.latex.startswith(start), [l.latex for l in page.lines]

    # Request options: line_data first, then the word_data retry; both with rm_spaces + `$` delimiters.
    assert payloads[0]["include_line_data"] is True
    assert payloads[1]["include_word_data"] is True
    for p in payloads:
        assert p["rm_spaces"] is True
        assert p["math_inline_delimiters"] == ["$", "$"]

    # The raw responses are always kept next to the uploaded image for diagnosis.
    saved = sorted(f.name.split("_", 3)[3] for f in (tmp_path / "debug").iterdir())
    assert saved == ["page_mathpix.json", "page_mathpix_words.json", "page_upload.jpg"]


def _entry(kind, text, y0, y1, x0=100, x1=900):
    return {"type": kind, "text": text, "cnt": [[x0, y0], [x1, y0], [x1, y1], [x0, y1]], "confidence": 0.9}


FIVE = [r"\int x^{2} d x", r"\frac{d}{d x} \sin (3 x)", r"\frac{e^{x}}{x+1}", r"\sum \frac{1}{n^{2}}",
        r"\lim _{x \rightarrow 0} \ln (1+x)"]


def test_five_separate_entries():
    entries = [_entry("math", f"${e}$", 100 + 200 * i, 250 + 200 * i) for i, e in enumerate(FIVE)]
    assert [ln.latex for ln in recognition.parse_entries(entries)] == FIVE


@pytest.mark.parametrize("env_open, env_close", [
    (r"\begin{array}{l}", r"\end{array}"),
    (r"\begin{gathered}", r"\end{gathered}"),
    (r"\begin{aligned}", r"\end{aligned}"),
])
def test_one_entry_with_rows_is_split_and_bbox_divided(env_open, env_close):
    text = "$" + env_open + r" \\ ".join(FIVE) + env_close + "$"
    lines = recognition.parse_entries([_entry("math", text, 100, 1100)])
    assert [ln.latex for ln in lines] == FIVE
    # The entry's bbox is divided vertically into five equal bands, top to bottom.
    assert [ln.bbox for ln in lines] == [[100, 100 + 200 * i, 900, 300 + 200 * i] for i in range(5)]


def test_bare_rows_without_environment_are_not_split():
    """`\\\\` only separates lines inside a row environment; a lone formula is left alone."""
    lines = recognition.parse_entries([_entry("math", r"$\frac{a}{b}$", 100, 300)])
    assert [ln.latex for ln in lines] == [r"\frac{a}{b}"]


def test_nested_environment_rows_stay_inside_their_line():
    text = r"$\begin{array}{l}x=1 \\ \left(\begin{array}{ll}a & b \\ c & d\end{array}\right)\end{array}$"
    lines = recognition.parse_entries([_entry("math", text, 100, 500)])
    assert len(lines) == 2
    assert lines[1].latex == r"\left(\begin{array}{ll}a & b \\ c & d\end{array}\right)"


@pytest.mark.parametrize("fmt", ["${}$", r"\({}\)"])
def test_text_entry_with_several_math_spans(fmt):
    text = "Solve " + " and ".join(fmt.replace("{}", e) for e in FIVE)
    lines = recognition.parse_entries([_entry("text", text, 100, 1100)])
    assert [ln.latex for ln in lines] == FIVE
    assert [ln.bbox[1] for ln in lines] == sorted(ln.bbox[1] for ln in lines)


def test_diagram_table_and_empty_entries_are_dropped():
    entries = [
        _entry("diagram", "", 0, 90),
        _entry("table", r"$\begin{array}{|l|l|}a & b\end{array}$", 0, 90),
        _entry("math", "   ", 0, 90),
        _entry("text", "Name: ________", 0, 90),
        _entry("header", "D", 0, 90),
    ] + [_entry("math", f"${e}$", 100 + 200 * i, 250 + 200 * i) for i, e in enumerate(FIVE)]
    assert [ln.latex for ln in recognition.parse_entries(entries)] == FIVE


def test_single_character_marks_are_dropped():
    """Mathpix reads notebook margin marks as math entries like `B` or `1`; they are not lines."""
    entries = [_entry("math", "B", 560, 600, x0=10, x1=50), _entry("math", "$1$", 700, 740, x0=10, x1=50)]
    entries += [_entry("math", f"${e}$", 100 + 200 * i, 250 + 200 * i) for i, e in enumerate(FIVE)]
    assert [ln.latex for ln in recognition.parse_entries(entries)] == FIVE


def test_lines_are_ordered_by_min_y_after_splitting():
    entries = [
        _entry("math", r"$\begin{gathered}c+3 \\ d+4\end{gathered}$", 500, 900),
        _entry("math", "$a+1$", 100, 250),
        _entry("math", "$b+2$", 300, 450),
    ]
    assert [ln.latex for ln in recognition.parse_entries(entries)] == ["a+1", "b+2", "c+3", "d+4"]


def test_fragments_of_one_handwritten_line_are_merged():
    """word_data can split one line into side-by-side boxes; they are rejoined left to right."""
    entries = [
        _entry("math", r"$\sin (3 x)$", 690, 802, x0=551, x1=995),
        _entry("math", r"$\frac{d}{d x}$", 642, 838, x0=432, x1=648),
    ]
    lines = recognition.parse_entries(entries)
    assert [ln.latex for ln in lines] == [r"\frac{d}{d x} \sin (3 x)"]
    assert lines[0].bbox == [432, 642, 995, 838]


@pytest.mark.live
def test_notebook_page_live(monkeypatch, tmp_path):
    """Opt-in (`pytest --live`): the real Mathpix API reads five lines off the notebook photo."""
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    monkeypatch.setattr(settings, "STORAGE_DIR", tmp_path)
    if not (settings.MATHPIX_APP_ID and settings.MATHPIX_APP_KEY):
        pytest.skip("no Mathpix credentials configured")
    page = asyncio.run(recognition.recognize_page(NOTEBOOK_IMAGE.read_bytes()))
    _assert_five_lines_in_order(page)


# --- Formula mode (single problem), the CTC-2 ∫2x dx fixture ---

def test_formula_mode_recognizes_integral_mocked(monkeypatch):
    """mode=formula: Mathpix mocked with the saved ∫2x dx response (no API key needed)."""
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    monkeypatch.setattr(settings, "MATHPIX_APP_ID", "x")
    monkeypatch.setattr(settings, "MATHPIX_APP_KEY", "y")

    async def fake_post(self, url, **kwargs):
        return httpx.Response(200, json=SAVED_FORMULA)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)

    res = asyncio.run(recognition.recognize(FORMULA_IMAGE.read_bytes()))
    assert res.latex == r"\int 2 x d x"
    assert res.confidence > 0.9
    # The recognized LaTeX parses as an integral, so /solve can take it from here.
    from app.models import Topic
    from app.services.symbolic import parse
    assert parse(res.latex).topic == Topic.integral


@pytest.mark.live
def test_formula_mode_live(monkeypatch):
    """Opt-in (`pytest --live`): hit real Mathpix with the ∫2x dx fixture photo."""
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    if not (settings.MATHPIX_APP_ID and settings.MATHPIX_APP_KEY):
        pytest.skip("no Mathpix credentials configured")
    res = asyncio.run(recognition.recognize(FORMULA_IMAGE.read_bytes()))
    assert "int" in res.latex and "x" in res.latex
    assert res.confidence > 0.5
