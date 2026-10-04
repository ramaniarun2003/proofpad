"""Solver + verifier tests. Add a case here for every problem in the evaluation set."""
import pytest
import sympy as sp

from app.models import Topic
from app.services.latex_norm import normalize
from app.services.steps import solve
from app.services.symbolic import parse
from app.services.verify import verify

x = sp.Symbol("x")


def run(latex, topic=Topic.unknown):
    p = parse(latex)
    t, sol = solve(p, topic)
    v = verify(p, t, sol)
    return t, sol, v


@pytest.mark.parametrize("latex", [
    r"\left( x \right)",
    r"\dfrac{1}{2}",
    r"\int x \, dx",
    r"\mathrm{d}x",
    r"\ln(x)",
])
def test_normalize_produces_parseable_latex(latex):
    parse(normalize(latex))


@pytest.mark.parametrize("latex, rules", [
    (r"\frac{d}{dx} x^{2} \sin(x)", {"product rule", "power rule"}),
    (r"\frac{d}{dx} \frac{e^{3x}}{x^2+1}", {"quotient rule", "chain rule with exp"}),
    (r"\frac{d}{dx} \ln(\cos(x^2))", {"chain rule with log", "chain rule with cos"}),
    (r"\frac{d}{dx} 3\tan(2x)", {"constant multiple", "chain rule with tan"}),
    (r"\frac{d}{dx} x^x", {"logarithmic differentiation"}),
])
def test_derivatives_verified_with_expected_rules(latex, rules):
    t, sol, v = run(latex)
    assert t == Topic.derivative
    assert v.verified and v.mismatch_at is None, v.details
    assert rules <= {s.rule for s in sol.steps}
    assert sp.simplify(sol.answer - sp.diff(parse(latex).inner, x)) == 0


@pytest.mark.parametrize("latex, rule", [
    (r"\int x e^{x} \, dx", "integration by parts"),
    (r"\int_{0}^{1} 2x\cos(x^2)\,dx", "u-substitution"),
    (r"\int \frac{1}{x^2-1} dx", "partial fractions"),
    (r"\int \sin(x)\cos(x) dx", "u-substitution"),
    (r"\int x^2 \ln(x) dx", "integration by parts"),
])
def test_integrals_verified_with_expected_rule(latex, rule):
    t, sol, v = run(latex)
    assert t == Topic.integral
    assert v.verified and v.mismatch_at is None, v.details
    assert rule in {s.rule for s in sol.steps}


def test_definite_integral_value():
    _, sol, _ = run(r"\int_{0}^{1} 2x\cos(x^2)\,dx")
    assert sp.simplify(sol.answer - sp.sin(1)) == 0


@pytest.mark.parametrize("latex, verdict, rule", [
    (r"\sum_{n=1}^{\infty} \frac{1}{n^2}", "converges", "p-series"),
    (r"\sum_{n=1}^{\infty} \frac{1}{\sqrt{n}}", "diverges", "p-series"),
    (r"\sum_{n=0}^{\infty} \left(\frac{1}{2}\right)^n", "converges", "geometric series"),
    (r"\sum_{n=1}^{\infty} \frac{2^n}{n!}", "converges", "ratio test"),
    (r"\sum_{n=1}^{\infty} \frac{n!}{3^n}", "diverges", "divergence test"),
    (r"\sum_{n=1}^{\infty} \frac{(-1)^n}{n}", "converges", "alternating series test"),
])
def test_series_verdicts(latex, verdict, rule):
    t, sol, v = run(latex)
    assert t == Topic.series
    assert sol.verdict == verdict
    assert rule in {s.rule for s in sol.steps}
    assert v.verified, v.details


def test_chain_invariant_every_step_equals_truth():
    """Each intermediate expression must equal the derivative — the verifier's premise."""
    p = parse(r"\frac{d}{dx} \frac{e^{3x}}{x^2+1}")
    _, sol = solve(p, Topic.unknown)
    truth = sp.diff(p.inner, x)
    for st in sol.steps:
        assert sp.simplify(st.after.doit() - truth) == 0, st.rule


def test_verifier_catches_a_wrong_step():
    from app.services.steps.base import Solution, StepRec
    p = parse(r"\frac{d}{dx} x^{3}")
    wrong = Solution(steps=[StepRec("power rule", sp.Derivative(x**3, x), 3 * x)], answer=3 * x)
    v = verify(p, Topic.derivative, wrong)
    assert not v.verified and v.mismatch_at == 0


def test_parse_error_on_garbage():
    from app.services.symbolic import ParseError
    with pytest.raises(ParseError):
        parse(r"\frac{d}{dx} \int")
