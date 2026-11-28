"""/verify on the student's own chain: per-step status, rule label, mistake hints, verdict."""
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.models import Topic
from app.services.symbolic import parse
from app.services.verify import verify_user_steps


def check(problem, steps):
    p = parse(problem)
    topic = p.topic if p.topic != Topic.unknown else Topic.derivative
    return verify_user_steps(p, topic, steps)


def test_correct_two_step_derivative_chain():
    res = check(r"\frac{d}{dx}(x^3+x)", [r"\frac{d}{dx} x^3 + 1", r"3x^2+1"])
    assert res.verdict == "verified" and res.mismatch_at is None
    assert [s.status for s in res.steps] == ["ok", "ok"]
    assert res.steps[1].rule == "power rule"             # the next state of our own chain
    assert res.user_final_equals_expected
    assert res.expected_final == "3 x^{2} + 1"


def test_chain_rule_mistake_is_flagged_at_step_0_with_the_hint():
    """The mockup's mistake: d/dx sin(3x) = cos(3x) — the outside differentiated, the 3 forgotten."""
    res = check(r"\frac{d}{d x} \sin (3 x)", [r"\cos (3 x)"])
    assert res.verdict == "mismatch" and res.mismatch_at == 0
    assert res.steps[0].status == "wrong"
    assert res.steps[0].hint.startswith("chain rule missing")
    assert "3" in res.steps[0].hint
    assert not res.user_final_equals_expected


def test_antiderivative_without_constant_is_incomplete():
    res = check(r"\int 2x\,dx", [r"x^2"])
    assert res.verdict == "incomplete" and res.mismatch_at is None
    assert res.steps[0].status == "ok"
    assert "missing constant of integration" in res.steps[0].hint
    assert res.expected_final == "x^{2} + C"
    assert not res.user_final_equals_expected
    assert check(r"\int 2x\,dx", [r"x^2 + C"]).verdict == "verified"


def test_skipping_our_intermediate_steps_is_still_verified():
    res = check(r"\frac{d}{dx}(x^2 \sin x)", [r"2x \sin x + x^2 \cos x"])
    assert res.verdict == "verified"
    assert res.steps[0].rule == "product rule (skipped 2 steps)"
    assert "product rule" in res.steps[0].hint          # which rules the line folded together


def test_wrong_sign():
    res = check(r"\frac{d}{dx}\cos(x)", [r"\sin x"])
    assert res.verdict == "mismatch" and res.mismatch_at == 0
    assert res.steps[0].hint.startswith("sign flipped")


def test_unparseable_step():
    res = check(r"\frac{d}{dx} x^3", [r"3x^{2", r"3x^2"])
    assert res.verdict == "mismatch" and res.mismatch_at == 0
    assert [s.status for s in res.steps] == ["unparseable", "ok"]
    assert res.steps[0].hint.startswith("I could not read this step")


@pytest.mark.parametrize("problem, step, hint", [
    (r"\frac{d}{dx} x^3", r"3x^3", "wrong power"),
    (r"\int x^2 dx", r"\frac{x^2}{3} + C", "wrong power"),
    (r"\frac{d}{dx} x^3", r"x^2", "off by a constant factor"),
    (r"\frac{d}{dx}(3x+1)^5", r"5(3x+1)^4", "chain rule missing"),
    (r"\frac{d}{dx} e^{2x}", r"e^{2x}", "chain rule missing"),
    (r"\int \sin x\,dx", r"\cos x + C", "sign flipped"),
])
def test_common_mistake_hints(problem, step, hint):
    res = check(problem, [step])
    assert res.verdict == "mismatch"
    assert res.steps[0].hint.startswith(hint), res.steps[0].hint


def test_wrong_step_with_no_known_mistake_has_no_hint():
    res = check(r"\frac{d}{dx} x^3", [r"\sin x"])
    assert res.verdict == "mismatch" and res.steps[0].hint == ""


def test_equivalent_but_off_our_chain_is_unrecognized_but_correct():
    res = check(r"\frac{d}{dx}(\sin x \cos x)", [r"1 - 2\sin^2(x)"])   # equal to cos(2x), not a state of ours
    assert res.verdict == "verified"
    assert res.steps[0].rule == "unrecognized but correct"


def test_later_steps_are_still_judged_after_a_wrong_one():
    res = check(r"\frac{d}{dx}(x^3+x)", [r"3x^2", r"3x^2+1"])
    assert res.verdict == "mismatch" and res.mismatch_at == 0
    assert [s.status for s in res.steps] == ["wrong", "ok"]


def test_unfinished_last_line_is_incomplete():
    res = check(r"\frac{d}{dx}(x^3+x)", [r"\frac{d}{dx} x^3 + 1"])
    assert res.verdict == "incomplete"
    assert "still has an operator" in res.steps[-1].hint


def test_definite_integral_antiderivative_then_value():
    assert check(r"\int_0^1 x^2 dx", [r"\frac{x^3}{3}"]).verdict == "incomplete"
    res = check(r"\int_0^1 x^2 dx", [r"\frac{x^3}{3}", r"\frac{1}{3}"])
    assert res.verdict == "verified"
    assert res.steps[1].rule == "fundamental theorem of calculus"


def test_limit_chain():
    assert check(r"\lim_{x \to 0} \frac{\sin x}{x}", ["1"]).verdict == "verified"
    assert check(r"\lim_{x \to 0} \frac{\sin x}{x}", ["0"]).verdict == "mismatch"


# --- API shape ---

def _post(body):
    return TestClient(app).post("/verify", json=body)


def test_verify_api_student_chain_response_shape():
    r = _post({"session_id": "", "problem_latex": r"\frac{d}{d x} \sin (3 x)", "user_steps": [r"3 \cos (3 x)"]})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["verdict"] == "verified" and body["verified"] is True and body["mismatch_at"] is None
    assert body["steps"] == [{"latex": r"3 \cos (3 x)", "status": "ok",
                              "rule": "chain rule with sin (skipped 2 steps)",
                              "hint": "correct; this line does several rules at once: "
                                      "chain rule with sin, constant multiple, identity"}]
    assert body["expected_final"] == r"3 \cos{\left(3 x \right)}"
    assert body["user_final_equals_expected"] is True


def test_verify_api_mismatch_and_incomplete():
    r = _post({"problem_latex": r"\frac{d}{d x} \sin (3 x)", "user_steps": [r"\cos (3 x)"]}).json()
    assert (r["verdict"], r["verified"], r["mismatch_at"]) == ("mismatch", False, 0)
    assert r["steps"][0]["hint"].startswith("chain rule missing")
    r = _post({"problem_latex": r"\int 2 x d x", "user_steps": [r"x^{2}"]}).json()
    assert (r["verdict"], r["verified"], r["mismatch_at"]) == ("incomplete", False, None)


def test_verify_api_slash_derivative_and_blank_steps():
    r = _post({"problem_latex": r"d / d x \sin (3 x)", "user_steps": ["", r"3 \cos (3 x)", "  "]}).json()
    assert r["verdict"] == "verified" and len(r["steps"]) == 1


def test_verify_api_rejects_unparseable_problem_and_asks_for_a_series_claim():
    # A series step must be a claim; an expression is flagged, not rejected.
    r = _post({"problem_latex": r"\sum_{n=1}^{\infty} \frac{1}{n^2}", "user_steps": ["1"]})
    assert r.status_code == 200
    assert (r.json()["verdict"], r.json()["steps"][0]["status"]) == ("mismatch", "unparseable")
    assert _post({"problem_latex": r"\frac{d}{dx}(", "user_steps": ["1"]}).status_code == 422


def test_old_edited_chain_shape_still_works():
    steps = [{"index": 0, "rule": "power rule", "before_latex": r"\frac{d}{dx}(x^3+x)", "after_latex": "3x^2+1", "note": ""}]
    body = _post({"session_id": "", "latex": r"\frac{d}{dx}(x^3+x)", "steps": steps}).json()
    assert body["verified"] is True and body["verdict"] == "verified" and body["steps"] == []


# --- Implicit multiplication: x(…) is a product, not a function call --------------------------

TODAY = r"d / d x x^{2} \sin x"          # the three-line derivative from the Pixel, 2026-11-27
STEP_1 = r"2x\sin x + x^2\cos x"


@pytest.mark.parametrize("factored", [
    r"x(2\sin x + x\cos x)",      # as the student wrote it
    r"x(x\cos x + 2\sin x)",      # our own order
    r"(2\sin x + x\cos x)x",
    r"x\left(2 \sin x+x \cos x\right)",   # Mathpix spacing
])
def test_factored_last_line_of_todays_chain_is_verified(factored):
    res = check(TODAY, [STEP_1, factored])
    assert (res.verdict, res.mismatch_at) == ("verified", None)
    assert [s.status for s in res.steps] == ["ok", "ok"]
    assert res.steps[0].rule == "product rule (skipped 2 steps)"
    assert res.steps[1].rule in ("simplify", "unrecognized but correct")
    assert res.user_final_equals_expected


def test_todays_chain_through_the_api():
    body = _post({"problem_latex": TODAY, "user_steps": [STEP_1, r"x(2\sin x + x\cos x)"]}).json()
    assert (body["verdict"], body["verified"], body["mismatch_at"]) == ("verified", True, None)
    assert [s["rule"] for s in body["steps"]] == ["product rule (skipped 2 steps)", "simplify"]


def test_a_wrong_factoring_is_still_flagged():
    res = check(TODAY, [STEP_1, r"x(2\sin x - x\cos x)"])
    assert (res.verdict, res.mismatch_at) == ("mismatch", 1)


# --- Equivalence stages -----------------------------------------------------------------------

@pytest.mark.parametrize("a, b, stage", [
    ("x*(x + 1)", "x*(x + 1)", "identical form"),
    ("x*(2*sin(x) + x*cos(x))", "2*x*sin(x) + x**2*cos(x)", "simplify"),
    ("sin(x)**2 + cos(x)**2", "1", "simplify"),
])
def test_equivalent_logs_the_stage_that_decided(caplog, a, b, stage):
    import logging

    import sympy as sp

    from app.services.verify import equivalent
    with caplog.at_level(logging.INFO, logger="proofpad.verify"):
        assert equivalent(sp.sympify(a), sp.sympify(b), label="step 2")
    assert f"equivalence [step 2]: equal by {stage}" in caplog.text


def test_equivalent_falls_through_to_later_stages(monkeypatch, caplog):
    import logging

    import sympy as sp

    from app.services import verify as v
    x = sp.Symbol("x")
    a, b = (x + 1) ** 2, x ** 2 + 2 * x + 1
    monkeypatch.setattr(sp, "simplify", lambda e, **kw: e)            # stage 1 cannot decide
    with caplog.at_level(logging.INFO, logger="proofpad.verify"):
        assert v.equivalent(a, b, label="t")
    assert "equal by expand" in caplog.text
    caplog.clear()
    monkeypatch.setattr(sp, "expand", lambda e, **kw: e)              # nor stages 2 and 3
    monkeypatch.setattr(sp, "trigsimp", lambda e, **kw: e)
    with caplog.at_level(logging.INFO, logger="proofpad.verify"):
        assert v.equivalent(a, b, label="t")
        assert not v.equivalent(a, b + x, label="t")
    assert "equal by numeric sampling" in caplog.text
    assert "DIFFERENT by numeric sampling" in caplog.text


def test_numeric_sampling_skips_singular_and_complex_points():
    import sympy as sp

    from app.services.verify import _numeric_agree
    x = sp.Symbol("x")
    assert _numeric_agree(sp.log(x - 1000)) is None                 # never real at the sample points
    assert _numeric_agree(1 / (x - sp.Rational(1, 3)) * 0 + x - x) is True
    assert _numeric_agree(sp.Integer(1)) is False


def test_a_step_with_a_function_times_a_parenthesis_is_checked_as_a_product():
    # d/dx (x² + x) sin x, written with the sine first
    res = check(r"\frac{d}{dx} \sin x (x^2+x)", [r"\cos x (x^2+x) + \sin x (2x+1)"])
    assert res.verdict == "verified"
