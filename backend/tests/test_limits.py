"""Limit generator (Topic.LIMIT): every rule, the chain invariant, and the
independent sympy.limit verification, plus the API round-trip."""
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.models import Topic
import sympy as sp

from app.services import steps as step_service
from app.services.symbolic import parse
from app.services.verify import verify

# (latex, expected rule substring somewhere in the chain, expected answer_latex substring)
PROBLEMS = [
    (r"\lim_{x \to 2} (x^2 + 1)", "direct substitution", "5"),
    (r"\lim_{x \to 2} \frac{x^2 - 4}{x - 2}", "factor and cancel", "4"),
    (r"\lim_{x \to 3} \frac{x^2 - 9}{x - 3}", "factor and cancel", "6"),
    (r"\lim_{x \to 0} \frac{\sqrt{x + 1} - 1}{x}", "rationalize", r"\frac{1}{2}"),
    (r"\lim_{x \to 4} \frac{\sqrt{x} - 2}{x - 4}", "rationalize", r"\frac{1}{4}"),
    (r"\lim_{x \to 0} \frac{\sin x}{x}", "standard limit", "1"),
    (r"\lim_{x \to 0} \frac{1 - \cos x}{x}", "standard limit", "0"),
    (r"\lim_{x \to 0} \frac{e^x - 1}{x}", "standard limit", "1"),
    (r"\lim_{x \to 0} \frac{\ln(1 + x)}{x}", "standard limit", "1"),  # required case
    (r"\lim_{x \to 0} \frac{x - \sin x}{x^3}", "L'Hopital", r"\frac{1}{6}"),   # needs all 3 rounds
    (r"\lim_{x \to 0} \frac{e^x - 1 - x}{x^2}", "L'Hopital", r"\frac{1}{2}"),
    (r"\lim_{x \to -\infty} \frac{3x^2 + 1}{x^2 - 5}", "divide by the highest power", "3"),
    (r"\lim_{x \to \infty} \frac{x}{e^x}", "L'Hopital", "0"),
    (r"\lim_{x \to \infty} \frac{3x^2 + 1}{x^2 - 5}", "divide by the highest power", "3"),  # required case
    (r"\lim_{x \to \infty} \frac{2x^3 - x}{x^3 + 1}", "divide by the highest power", "2"),
]


@pytest.mark.parametrize("latex, rule, answer", PROBLEMS)
def test_limit_rule_verified_and_correct(latex, rule, answer):
    p = parse(latex)
    assert p.topic == Topic.limit
    topic, sol = step_service.solve(p, Topic.unknown)
    assert topic == Topic.limit
    rules = [s.rule for s in sol.steps]
    assert any(rule.lower() in r.lower() for r in rules), f"{rule} not in {rules}"
    assert answer in sol.answer_latex, f"{answer!r} not in {sol.answer_latex!r}"
    # Independent verification (sympy.limit).
    vr = verify(p, topic, sol)
    assert vr.verified, vr.details


@pytest.mark.parametrize("latex, _rule, _answer", PROBLEMS)
def test_limit_chain_invariant(latex, _rule, _answer):
    _topic, sol = step_service.solve(parse(latex), Topic.unknown)
    for i in range(len(sol.steps) - 1):
        assert sol.steps[i].after == sol.steps[i + 1].before, f"chain breaks at step {i} of {latex}"


def test_limit_does_not_exist_reports_both_sides():
    latex = r"\lim_{x \to 0} \frac{1}{x}"
    p = parse(latex)
    topic, sol = step_service.solve(p, Topic.unknown)
    assert sol.verdict == "does not exist"
    assert sol.answer_latex == "does not exist"
    note = sol.steps[0].note.lower()
    assert "left-hand limit" in note and "right-hand limit" in note
    assert verify(p, topic, sol).verified


def test_limit_verifier_catches_a_wrong_answer():
    """Tamper the final step's value; the independent check must flag it."""
    import sympy as sp
    p = parse(r"\lim_{x \to 0} \frac{\sin x}{x}")
    topic, sol = step_service.solve(p, Topic.unknown)
    sol.steps[-1].after = sp.Integer(2)   # true limit is 1
    sol.answer = sp.Integer(2)
    vr = verify(p, topic, sol)
    assert not vr.verified and vr.mismatch_at == len(sol.steps) - 1


def test_limit_normalizes_rightarrow_arrow():
    """Mathpix emits \\rightarrow; latex_norm maps it to \\to so it parses."""
    p = parse(r"\lim_{x \rightarrow 0} \frac{\sin x}{x}")
    assert p.topic == Topic.limit
    _topic, sol = step_service.solve(p, Topic.unknown)
    assert sol.answer_latex == "1"


def test_limit_one_sided_direction_parsed():
    p = parse(r"\lim_{x \to 0^+} \frac{1}{x}")
    assert p.topic == Topic.limit and p.direction == "+"


def test_limit_solves_over_api():
    with TestClient(app) as c:
        r = c.post("/solve", json={"session_id": "", "latex": r"\lim_{x \to 0} \frac{\ln(1+x)}{x}", "topic": "limit"})
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["topic"] == "limit"
        assert body["answer_latex"] == "1"
        assert body["verified"] is True
        assert [s["rule"] for s in body["steps"]] == ["standard limit"]   # ln(1+x)/x is a memorized limit


# --- Points the parser must accept: finite, \infty, -\infty, 0^+, 0^- ---

@pytest.mark.parametrize("latex, point, direction", [
    (r"\lim_{x \to 2} x", sp.Integer(2), "+-"),
    (r"\lim_{x \to \infty} \frac{1}{x}", sp.oo, None),
    (r"\lim_{x \to -\infty} \frac{1}{x}", -sp.oo, None),
    (r"\lim_{x \to 0^+} \frac{1}{x}", sp.Integer(0), "+"),
    (r"\lim_{x \to 0^-} \frac{1}{x}", sp.Integer(0), "-"),
    (r"\lim _{x \rightarrow 0^{+}} \frac{1}{x}", sp.Integer(0), "+"),   # as Mathpix writes it
])
def test_limit_points_parse(latex, point, direction):
    p = parse(latex)
    assert p.topic == Topic.limit
    assert p.point == point
    if direction is not None:
        assert p.direction == direction


@pytest.mark.parametrize("latex, answer", [
    (r"\lim_{x \to 0^+} \frac{1}{x}", r"\infty"),
    (r"\lim_{x \to 0^-} \frac{1}{x}", r"-\infty"),
    (r"\lim_{x \to -\infty} \frac{1}{x}", "0"),
])
def test_one_sided_and_negative_infinity_limits(latex, answer):
    p = parse(latex)
    topic, sol = step_service.solve(p, Topic.unknown)
    assert sol.answer_latex == answer
    assert verify(p, topic, sol).verified


def test_lhopital_stops_after_three_rounds_and_rechecks_the_form():
    p = parse(r"\lim_{x \to 0} \frac{x - \sin x}{x^3}")
    _, sol = step_service.solve(p, Topic.unknown)
    rules = [s.rule for s in sol.steps]
    assert rules.count("L'Hopital's rule") == 3
    assert rules[-1] == "direct substitution"       # the form is no longer 0/0, so substitution finishes it
    for a, b in zip(sol.steps, sol.steps[1:]):
        assert a.after == b.before                   # chain invariant
