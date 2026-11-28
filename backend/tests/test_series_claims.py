"""Series: the student's step is a claim ("converges" / "diverges"), checked against our tests."""
import json
import logging
import pathlib

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.services import recognition
from app.services.identify import identify, normalize_claim, split_top_level
from app.services.latex_norm import add_sum_bounds
from app.services.symbolic import parse

FIXTURES = pathlib.Path(__file__).parent / "fixtures"
# Exactly what Mathpix returned for handwritten problem 8 (Pixel 7, 2026-11-27).
MATHPIX_PROBLEM_8 = r"\sum \frac{1}{n^{2}} \Rightarrow \text { converges }"


def _verify(problem, steps):
    r = TestClient(app).post("/verify", json={"problem_latex": problem, "user_steps": steps})
    assert r.status_code == 200, r.text
    return r.json()


def _same_problem(a, b):
    return parse(a).expr == parse(b).expr


def _mathpix(monkeypatch, tmp_path, response):
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    monkeypatch.setattr(settings, "MATHPIX_APP_ID", "x")
    monkeypatch.setattr(settings, "MATHPIX_APP_KEY", "y")
    monkeypatch.setattr(settings, "STORAGE_DIR", tmp_path)

    async def fake_post(self, url, json=None, headers=None, **kw):
        return httpx.Response(200, json=response, request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx.AsyncClient, "post", fake_post)


def _recognize(monkeypatch, tmp_path, response, image=b"\xff\xd8fake"):
    _mathpix(monkeypatch, tmp_path, response)
    r = TestClient(app).post("/recognize", files={"image": ("a.jpg", image, "image/jpeg")})
    assert r.status_code == 200, r.text
    return r.json()


# --- the three cases from the bug report -------------------------------------------------------

def test_problem_8_exact_mathpix_string_is_verified():
    got = identify(MATHPIX_PROBLEM_8)
    assert got.problem_latex == r"\sum_{n=1}^{\infty} \frac{1}{n^{2}}"
    assert _same_problem(got.problem_latex, r"\sum_{n=1}^{\infty}\frac{1}{n^2}")
    assert got.steps == ["CONVERGES"]
    assert got.split_at == "⇒"
    res = _verify(got.problem_latex, got.steps)
    assert (res["verdict"], res["verified"], res["mismatch_at"]) == ("verified", True, None)
    assert res["steps"] == [{"latex": r"\text{converges}", "status": "ok", "rule": "p-series", "hint": ""}]
    # Our tests come back as the explanation.
    assert [s["rule"] for s in res["solution_steps"]] == ["divergence test", "p-series"]
    assert res["solution_steps"][-1]["after_latex"] == "converges"


def test_harmonic_series_claimed_convergent_is_check_step_1():
    got = identify(r"\sum \frac{1}{n} = \text{converges}")
    assert _same_problem(got.problem_latex, r"\sum_{n=1}^{\infty}\frac{1}{n}")
    assert (got.steps, got.split_at) == (["CONVERGES"], "=")
    res = _verify(got.problem_latex, got.steps)
    assert (res["verdict"], res["verified"], res["mismatch_at"]) == ("mismatch", False, 0)   # app: CHECK STEP 1
    assert res["steps"][0]["status"] == "wrong"
    assert res["steps"][0]["hint"] == "our p-series test says it diverges (this is the harmonic series, p = 1)"
    assert res["solution_steps"][-1]["after_latex"] == "diverges"


def test_geometric_series_claimed_divergent_is_verified():
    got = identify(r"\sum 2^n \Rightarrow \text{diverges}")
    assert got.problem_latex == r"\sum_{n=1}^{\infty} 2^n"
    assert got.steps == ["DIVERGES"]
    res = _verify(got.problem_latex, got.steps)
    assert (res["verdict"], res["verified"]) == ("verified", True)
    assert res["steps"][0]["rule"] == "divergence test"


def test_wrong_claim_names_the_test_that_decided():
    res = _verify(r"\sum_{n=0}^{\infty} \left(\frac{1}{2}\right)^n", ["DIVERGES"])
    assert res["mismatch_at"] == 0
    assert res["steps"][0]["hint"] == "our geometric series test says it converges"
    res = _verify(r"\sum_{n=1}^{\infty} \frac{n}{n+1}", ["converges"])
    assert res["steps"][0]["hint"] == "our divergence test says it diverges"


# --- separators --------------------------------------------------------------------------------

@pytest.mark.parametrize("sep, label", [
    ("=", "="), (r"\Rightarrow", "⇒"), (r"\implies", "⇒"), (r"\therefore", "∴"),
    (r"\to", "→"), (r"\rightarrow", "→"), (r"\longrightarrow", "→"),
    (r"\text{so}", "so"), (r"\text{ is }", "is"), (r"\text{ So }", "so"),
])
def test_every_claim_separator_splits_problem_from_step(sep, label):
    got = identify(rf"\frac{{d}}{{dx}} x^2 {sep} 2x")
    assert (got.problem_latex, got.steps, got.split_at) == (r"\frac{d}{dx} x^2", ["2x"], label)
    got = identify(rf"\sum \frac{{1}}{{n^2}} {sep} \text{{converges}}")
    assert (got.steps, got.split_at) == (["CONVERGES"], label)


def test_separator_words_inside_a_longer_text():
    got = identify(r"\sum \frac{1}{k^{2}} \text { is convergent }")
    assert (got.problem_latex, got.steps, got.split_at) == (r"\sum_{k=1}^{\infty} \frac{1}{k^{2}}", ["CONVERGES"], "is")
    got = identify(r"\int 2x\,dx \text{ so } x^2 + C")
    assert (got.problem_latex, got.steps) == (r"\int 2x\,dx", ["x^2 + C"])


def test_arrows_inside_a_limit_are_not_split_points():
    # subscript form, and Mathpix's handwritten form with the subscript dropped
    got = identify(r"\lim_{x \to 0} \frac{\sin x}{x} = 1")
    assert (got.problem_latex, got.steps) == (r"\lim_{x \to 0} \frac{\sin x}{x}", ["1"])
    got = identify(r"\lim x \rightarrow 0 \frac{\ln (1+x)}{x}=1")
    assert (got.problem_latex, got.steps) == (r"\lim x \rightarrow 0 \frac{\ln (1+x)}{x}", ["1"])
    assert [p for p, _ in split_top_level(r"\left(a \to b\right) \to c")] == [r"\left(a \to b\right) ", " c"]


def test_split_at_reports_what_was_found():
    assert identify(r"\int 2x dx").split_at == ""                                    # no split: say nothing
    assert identify([r"\int x^2 dx", r"\frac{x^3}{3} + C"]).split_at == "line"       # next line, no sign
    assert identify([r"\int x^2 dx", r"= \frac{x^3}{3} + C"]).split_at == "="
    assert identify(r"\sum \frac{1}{n^2} \text{ converges}").split_at == "converges"  # no separator written


# --- claims ------------------------------------------------------------------------------------

@pytest.mark.parametrize("text, claim", [
    (r"\text { converges }", "CONVERGES"), (r"\text{conv.}", "CONVERGES"), (r"\text{div}", "DIVERGES"),
    ("diverges", "DIVERGES"), ("divergent", "DIVERGES"), ("convergent", "CONVERGES"),
    ("  Converges ", "CONVERGES"), (r"\text{ DIVERGENT }", "DIVERGES"), ("CONVERGES", "CONVERGES"),
    ("DIVERGES", "DIVERGES"), (r"\text{it converges}", "CONVERGES"), (r"\text{does not converge}", "DIVERGES"),
    ("1", None), (r"\frac{\pi^2}{6}", None), ("", None), (r"\text{by the p-test}", None),
])
def test_normalize_claim(text, claim):
    assert normalize_claim(text) == claim


def test_other_text_is_stripped_from_the_series_and_logged(caplog):
    with caplog.at_level(logging.INFO, logger="proofpad.identify"):
        got = identify(r"\sum \frac{1}{n^2} \text{ by the p-test } \Rightarrow \text{Conv.}")
    assert (got.problem_latex, got.steps) == (r"\sum_{n=1}^{\infty} \frac{1}{n^2}", ["CONVERGES"])
    assert r"\text{by the p-test}" in caplog.text


def test_a_series_with_a_value_instead_of_a_claim_keeps_the_ordinary_split():
    got = identify(r"\sum_{n=1}^{\infty} \frac{1}{2^{n}} = 1")
    assert (got.problem_latex, got.steps) == (r"\sum_{n=1}^{\infty} \frac{1}{2^{n}}", ["1"])
    res = _verify(got.problem_latex, got.steps)
    assert res["verdict"] == "mismatch"
    assert res["steps"][0] == {"latex": "1", "status": "unparseable", "rule": "",
                               "hint": "for a series, say whether it converges or diverges"}


# --- bounds ------------------------------------------------------------------------------------

@pytest.mark.parametrize("latex, expected", [
    (r"\sum \frac{1}{n^{2}}", r"\sum_{n=1}^{\infty} \frac{1}{n^{2}}"),
    (r"\sum\frac{1}{k}", r"\sum_{k=1}^{\infty} \frac{1}{k}"),
    (r"\sum 2^{-i}", r"\sum_{i=1}^{\infty} 2^{-i}"),
    (r"\sum \frac{k}{n^2}", r"\sum_{n=1}^{\infty} \frac{k}{n^2}"),      # n wins over k
    (r"\sum \frac{1}{2}", r"\sum_{n=1}^{\infty} \frac{1}{2}"),           # no n, k or i: default n
    (r"\sum \sin(x)", r"\sum_{n=1}^{\infty} \sin(x)"),                   # the n and i of \sin are not variables
    (r"\sum_{k} k^{-2}", r"\sum_{k=1}^{\infty} k^{-2}"),
    (r"\sum_{n=2} \frac{1}{n}", r"\sum_{n=2}^{\infty} \frac{1}{n}"),
    (r"\sum_{n=0}^{\infty} \frac{1}{2^n}", r"\sum_{n=0}^{\infty} \frac{1}{2^n}"),   # already bounded: untouched
    (r"\sum^{10}_{i=1} i", r"\sum_{i=1}^{10} i"),
    (r"\frac{d}{dx} x^2", r"\frac{d}{dx} x^2"),
])
def test_add_sum_bounds(latex, expected):
    assert add_sum_bounds(latex) == expected
    assert add_sum_bounds(expected) == expected


def test_parse_accepts_a_sum_without_bounds():
    p = parse(r"\sum \frac{1}{n^{2}}")
    assert (p.topic.value, str(p.index), str(p.limits)) == ("series", "n", "(1, oo)")
    r = TestClient(app).post("/solve", json={"session_id": "", "latex": r"\sum \frac{1}{k^2}"})
    assert r.status_code == 200 and r.json()["answer_latex"] == "converges"


# --- /recognize --------------------------------------------------------------------------------

def test_recognize_returns_the_claim_and_never_errors_on_this_string(monkeypatch, tmp_path):
    body = _recognize(monkeypatch, tmp_path, {"latex_styled": MATHPIX_PROBLEM_8, "confidence": 0.96})
    assert body["problem_latex"] == r"\sum_{n=1}^{\infty} \frac{1}{n^{2}}"
    assert body["steps"] == ["CONVERGES"]
    assert (body["topic_guess"], body["split_at"], body["parse_error"]) == ("series", "⇒", None)


@pytest.mark.parametrize("latex", [
    r"\sum \frac{1}{n^{2} \Rightarrow \text { converges }",   # a brace lost in the read
    r"\frac{d}{dx}( = 2x",
    r"\int \frac{",
])
def test_recognize_parse_failure_is_200_with_parse_error(monkeypatch, tmp_path, latex):
    body = _recognize(monkeypatch, tmp_path, {"latex_styled": latex, "confidence": 0.9})
    assert body["latex"] == latex
    assert body["problem_latex"]
    assert body["steps"] == [] and body["split_at"] == ""
    assert body["parse_error"]
    assert body["topic_guess"] == "unknown"


def test_handwritten_series_capture_from_today(monkeypatch, tmp_path):
    """
    Today's notebook capture (Pixel 7, 2026-11-27 17:57) with its saved Mathpix response. Problem 8,
    `Σ 1/n² ⇒ converges`, is line 2 of 4; line 1 was misread ("lime"), so the series is the first
    line with an operator and becomes the problem. The other lines are not steps of a series.
    """
    saved = json.loads((FIXTURES / "handwritten_series_claim_mathpix.json").read_text())
    image = (FIXTURES / "handwritten_series_claim.jpg").read_bytes()
    rows = [ln.latex for ln in recognition.parse_line_data(saved)]
    assert rows[1] == MATHPIX_PROBLEM_8

    body = _recognize(monkeypatch, tmp_path, saved, image)
    assert body["problem_latex"] == r"\sum_{n=1}^{\infty} \frac{1}{n^{2}}"
    assert body["steps"] == ["CONVERGES"]
    assert (body["topic_guess"], body["split_at"], body["parse_error"]) == ("series", "⇒", None)
    res = _verify(body["problem_latex"], body["steps"])
    assert (res["verdict"], res["verified"]) == ("verified", True)


def test_handwritten_problem_8_alone_from_today(monkeypatch, tmp_path):
    r"""
    The same line cropped on its own (Pixel 7, 2026-11-27 18:09). Here Mathpix's line_data puts the
    word outside the math span — `$\sum \frac{1}{n^{2}} \Rightarrow$ converges` — and it must not be
    lost on the way to Identify.
    """
    saved = json.loads((FIXTURES / "handwritten_series_line_mathpix.json").read_text())
    assert saved["latex_styled"] == MATHPIX_PROBLEM_8
    assert saved["line_data"][0]["text"] == r"$\sum \frac{1}{n^{2}} \Rightarrow$ converges"
    assert [ln.latex for ln in recognition.parse_line_data(saved)] == [r"\sum \frac{1}{n^{2}} \Rightarrow \text{converges}"]

    body = _recognize(monkeypatch, tmp_path, saved, (FIXTURES / "handwritten_series_line.jpg").read_bytes())
    assert body["latex"] == MATHPIX_PROBLEM_8
    assert body["problem_latex"] == r"\sum_{n=1}^{\infty} \frac{1}{n^{2}}"
    assert body["steps"] == ["CONVERGES"]
    assert (body["topic_guess"], body["split_at"], body["parse_error"]) == ("series", "⇒", None)
    assert _verify(body["problem_latex"], body["steps"])["verdict"] == "verified"


def test_prose_after_a_math_span_is_kept_only_when_it_is_a_claim():
    entry = {"type": "math", "cnt": [[0, 0], [100, 0], [100, 40], [0, 40]], "confidence": 0.9}
    lines = recognition.parse_entries([{**entry, "text": r"$\sum \frac{1}{n}$ diverges"}])
    assert [ln.latex for ln in lines] == [r"\sum \frac{1}{n} \text{diverges}"]
    lines = recognition.parse_entries([{**entry, "text": r"$\frac{d}{dx} x^2 = 2x$ by the power rule"}])
    assert [ln.latex for ln in lines] == [r"\frac{d}{dx} x^2 = 2x"]
