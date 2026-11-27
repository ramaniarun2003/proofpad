"""Identify split: a captured string → the problem and the student's own steps."""
import asyncio

import httpx
import pytest

from app.config import settings
from app.services import recognition
from app.services.identify import has_operator, identify, split_top_level_equals


@pytest.mark.parametrize("capture, problem, steps", [
    # one line, problem = answer
    (r"\frac{d}{dx}(\sin(3x)) = 3\cos(3x)", r"\frac{d}{dx}(\sin(3x))", [r"3\cos(3x)"]),
    (r"\int 2x\,dx = x^2 + C", r"\int 2x\,dx", [r"x^2 + C"]),
    (r"\lim_{x \to 0} \frac{\sin x}{x} = 1", r"\lim_{x \to 0} \frac{\sin x}{x}", ["1"]),
    # two lines; the second starts with "=" and continues the chain
    ([r"\int x^2 dx", r"= \frac{x^3}{3} + C"], r"\int x^2 dx", [r"\frac{x^3}{3} + C"]),
    # a chain with three = signs
    (r"\frac{d}{dx}(x^2 \cdot x) = 2x \cdot x + x^2 = 2x^2 + x^2 = 3x^2",
     r"\frac{d}{dx}(x^2 \cdot x)", [r"2x \cdot x + x^2", r"2x^2 + x^2", r"3x^2"]),
    # an = inside \left( \right) is not a split point
    (r"\frac{d}{dx}\left(x^2 \text{ where } x = 3t\right) = 2x",
     r"\frac{d}{dx}\left(x^2 \text{ where } x = 3t\right)", ["2x"]),
    # a bare problem with no working
    (r"\int 2x dx", r"\int 2x dx", []),
])
def test_identify_problem_and_steps(capture, problem, steps):
    got = identify(capture)
    assert got.problem_latex == problem
    assert got.steps == steps
    assert not got.split_uncertain


def test_equals_in_braces_and_limits_of_a_sum_are_not_split_points():
    got = identify(r"\sum_{n=1}^{\infty} \frac{1}{2^{n}} = 1")
    assert got.problem_latex == r"\sum_{n=1}^{\infty} \frac{1}{2^{n}}"
    assert got.steps == ["1"]
    assert split_top_level_equals(r"a_{i=1} = \left(b = c\right) = d") == ["a_{i=1} ", r" \left(b = c\right) ", " d"]


def test_mathpix_spacing_and_slash_derivative():
    got = identify(r"\frac{d}{d x} \sin (3 x)=3 \cos (3 x)")
    assert (got.problem_latex, got.steps) == (r"\frac{d}{d x} \sin (3 x)", [r"3 \cos (3 x)"])
    got = identify(r"d / d x \sin (3 x)=3 \cos (3 x)")
    assert (got.problem_latex, got.steps) == (r"d / d x \sin (3 x)", [r"3 \cos (3 x)"])


def test_aligned_block_is_split_into_lines_first():
    got = identify(r"\begin{aligned} \int x^{2} d x & =\frac{x^{3}}{3}+C \\ & =\frac{1}{3} x^{3}+C \end{aligned}")
    assert got.problem_latex == r"\int x^{2} d x"
    assert got.steps == [r"\frac{x^{3}}{3}+C", r"\frac{1}{3} x^{3}+C"]
    assert [s.line for s in got.segments] == [0, 0, 1]


def test_segments_carry_line_index_and_bbox():
    got = identify([(r"\int x^2 dx", [10, 20, 300, 80]), (r"= \frac{x^3}{3} + C", [10, 100, 300, 160])])
    assert [(s.latex, s.line, s.bbox) for s in got.segments] == [
        (r"\int x^2 dx", 0, [10, 20, 300, 80]),
        (r"\frac{x^3}{3} + C", 1, [10, 100, 300, 160]),
    ]


def test_text_before_the_problem_is_not_a_step():
    got = identify(r"y = \frac{d}{dx} x^2 = 2x")
    assert got.problem_latex == r"\frac{d}{dx} x^2"
    assert got.steps == ["2x"]


def test_no_operator_keeps_everything_as_the_problem():
    got = identify(r"x^2 + 3x")
    assert (got.problem_latex, got.steps, got.split_uncertain) == (r"x^2 + 3x", [], False)


def test_no_operator_but_equals_signs_is_flagged():
    got = identify(r"2x + 1 = 7")
    assert got.problem_latex == r"2x + 1 = 7"
    assert got.steps == []
    assert got.split_uncertain


@pytest.mark.parametrize("latex", [
    r"\frac{d}{dx} x", r"\frac{d y}{d x}", "d/dx x", r"d / d x \sin x", "f'(x)", r"y^{\prime}",
    r"\int x dx", r"\iint xy", r"\iiint xyz", r"\sum \frac{1}{n}", r"\lim_{x \to 0} x", r"\frac{\partial}{\partial x} xy",
])
def test_has_operator(latex):
    assert has_operator(latex)


@pytest.mark.parametrize("latex", [r"3\cos(3x)", r"x^2 + C", r"\frac{x^3}{3}", r"\sin(x) \cdot d", "1"])
def test_plain_expressions_have_no_operator(latex):
    assert not has_operator(latex)


# --- /recognize returns the split in formula mode ---

def _mathpix(monkeypatch, response):
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    monkeypatch.setattr(settings, "MATHPIX_APP_ID", "x")
    monkeypatch.setattr(settings, "MATHPIX_APP_KEY", "y")
    sent = []

    async def fake_post(self, url, **kwargs):
        sent.append(kwargs["json"])
        return httpx.Response(200, json=response)

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)
    return sent


def _line(text, y0, y1):
    return {"type": "math", "text": f"${text}$", "cnt": [[40, y0], [600, y0], [600, y1], [40, y1]], "confidence": 0.97}


def test_recognize_formula_mode_returns_problem_and_steps(monkeypatch, tmp_path):
    from fastapi.testclient import TestClient

    from app.main import app

    monkeypatch.setattr(settings, "STORAGE_DIR", tmp_path)
    full = r"\frac{d}{d x} \sin (3 x)=3 \cos (3 x)"
    sent = _mathpix(monkeypatch, {"latex_styled": full, "text": f"${full}$", "confidence": 0.97,
                                  "line_data": [_line(full, 100, 180)]})
    r = TestClient(app).post("/recognize", files={"image": ("a.jpg", b"\xff\xd8fake", "image/jpeg")})
    assert r.status_code == 200, r.text
    body = r.json()
    assert sent[0]["include_line_data"] is True          # line_data on formula mode too
    assert body["latex"] == full                         # the full string is kept
    assert body["problem_latex"] == r"\frac{d}{d x} \sin (3 x)"
    assert body["steps"] == [r"3 \cos (3 x)"]
    assert body["topic_guess"] == "derivative"
    assert [(s["latex"], s["line"], s["bbox"]) for s in body["segments"]] == [
        (r"\frac{d}{d x} \sin (3 x)", 0, [40, 100, 600, 180]),
        (r"3 \cos (3 x)", 0, [40, 100, 600, 180]),
    ]
    assert body["split_uncertain"] is False


def test_recognize_two_lines_from_line_data(monkeypatch):
    sent = _mathpix(monkeypatch, {
        "latex_styled": r"\begin{aligned} & \int x^{2} d x \\ & =\frac{x^{3}}{3}+C \end{aligned}",
        "confidence": 0.9,
        "line_data": [_line(r"\int x^{2} d x", 100, 180), _line(r"=\frac{x^{3}}{3}+C", 220, 300)],
    })
    res = asyncio.run(recognition.recognize(b"img"))
    from app.services.identify import identify as ident
    split = ident([(ln.latex, ln.bbox) for ln in res.lines])
    assert sent and split.problem_latex == r"\int x^{2} d x"
    assert split.steps == [r"\frac{x^{3}}{3}+C"]
    assert [s.bbox for s in split.segments] == [[40, 100, 600, 180], [40, 220, 600, 300]]


def test_recognize_without_line_data_falls_back_to_the_full_string(monkeypatch, tmp_path):
    from fastapi.testclient import TestClient

    from app.main import app

    monkeypatch.setattr(settings, "STORAGE_DIR", tmp_path)
    _mathpix(monkeypatch, {"latex_styled": r"\int 2 x d x=x^{2}+C", "confidence": 0.99})
    body = TestClient(app).post("/recognize", files={"image": ("a.jpg", b"\xff\xd8fake", "image/jpeg")}).json()
    assert body["problem_latex"] == r"\int 2 x d x"
    assert body["steps"] == [r"x^{2}+C"]


# --- the student's chain verifies ---

def _verify(latex, steps):
    from fastapi.testclient import TestClient

    from app.main import app

    chain = [{"index": i, "rule": f"user step {i + 1}", "before_latex": latex if i == 0 else steps[i - 1],
              "after_latex": s, "note": ""} for i, s in enumerate(steps)]
    r = TestClient(app).post("/verify", json={"session_id": "identify-test", "latex": latex, "steps": chain})
    assert r.status_code == 200, r.text
    return r.json()


@pytest.mark.parametrize("capture", [
    r"\frac{d}{d x} \sin (3 x)=3 \cos (3 x)",
    r"d / d x \sin (3 x)=3 \cos (3 x)",
    r"\int 2x\,dx = x^2 + C",
    r"\lim_{x \to 0} \frac{\sin x}{x} = 1",
    r"\frac{d}{dx}(x^2 \cdot x) = 2x \cdot x + x^2 = 2x^2 + x^2 = 3x^2",
])
def test_split_chain_is_verified(capture):
    got = identify(capture)
    res = _verify(got.problem_latex, got.steps)
    assert res["verified"] is True and res["mismatch_at"] is None, res


@pytest.mark.parametrize("capture, bad_at", [
    (r"\frac{d}{d x} \sin (3 x)=3 \sin (3 x)", 0),
    (r"\lim_{x \to 0} \frac{\sin x}{x} = 0", 0),
    (r"\frac{d}{dx}(x^3) = 3x^2 = 3x", 1),
])
def test_wrong_student_step_is_flagged(capture, bad_at):
    got = identify(capture)
    res = _verify(got.problem_latex, got.steps)
    assert res["verified"] is False and res["mismatch_at"] == bad_at


# --- Regression guard for the demo: today's real handwritten captures (Pixel 7, 2026-11-27) ---
#
# The photos are the app's own uploads and the JSON is exactly what Mathpix returned for them.

import json
import pathlib

FIXTURES = pathlib.Path(__file__).parent / "fixtures"


def _recognize_fixture(monkeypatch, tmp_path, name):
    saved = json.loads((FIXTURES / f"{name}_mathpix.json").read_text())
    monkeypatch.setattr(settings, "STORAGE_DIR", tmp_path)
    _mathpix(monkeypatch, saved)
    from fastapi.testclient import TestClient

    from app.main import app

    image = (FIXTURES / f"{name}.jpg").read_bytes()
    r = TestClient(app).post("/recognize", files={"image": (f"{name}.jpg", image, "image/jpeg")})
    assert r.status_code == 200, r.text
    return r.json()


def test_handwritten_derivative_chain_splits_into_problem_and_step(monkeypatch, tmp_path):
    """Handwritten `d/dx (sin(3x)) = 3cos(3x)` on ruled paper."""
    body = _recognize_fixture(monkeypatch, tmp_path, "handwritten_chain_derivative")
    assert body["latex"] == r"\frac{d}{d x}(\sin (3 x))=3 \cos (3 x)"
    assert body["problem_latex"] == r"\frac{d}{d x}(\sin (3 x))"
    assert body["steps"] == [r"3 \cos (3 x)"]
    assert body["topic_guess"] == "derivative"
    assert body["split_uncertain"] is False
    assert [s["line"] for s in body["segments"]] == [0, 0]
    assert all(s["bbox"] == [94, 184, 1429, 419] for s in body["segments"])
    # …and the student's step checks out against the problem.
    assert _verify(body["problem_latex"], body["steps"])["verified"] is True
    # The raw response is kept for formula captures too.
    saved = sorted(f.name.split("_", 3)[3] for f in (tmp_path / "debug").iterdir())
    assert saved == ["formula_mathpix.json", "formula_upload.jpg"]


def test_handwritten_integral_without_working_has_no_steps(monkeypatch, tmp_path):
    """Handwritten `∫ x² dx` alone: the whole capture is the problem."""
    body = _recognize_fixture(monkeypatch, tmp_path, "handwritten_chain_integral")
    assert body["problem_latex"] == r"\int x^{2} d x"
    assert body["steps"] == []
    assert body["topic_guess"] == "integral"
    assert body["split_uncertain"] is False
