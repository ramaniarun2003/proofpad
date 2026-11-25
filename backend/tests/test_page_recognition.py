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
