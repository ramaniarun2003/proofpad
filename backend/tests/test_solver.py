"""Solver + verifier tests. Add a case here for every problem in the evaluation set."""
import pytest
import sympy as sp

from app.models import Topic
from app.services.latex_norm import normalize
from app.services.steps import solve
from app.services.symbolic import ParseError, parse
from app.services.verify import verify, verify_latex_steps

x, y = sp.symbols("x y")


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


@pytest.mark.parametrize("latex, variables, expected", [
    (r"\frac{\partial}{\partial y}(x^2y + \sin(y))", (y,), x**2 + sp.cos(y)),
    (r"\frac{\partial x^2y}{\partial x}", (x,), 2 * x * y),
    (r"\frac{\partial}{\partial y}x^2", (y,), 0),
    (r"\frac{\partial^2}{\partial x^2}(x^3y)", (x, x), 6 * x * y),
    (r"\frac{\partial^3 x^3y^3}{\partial x^2\partial y}", (y, x, x), 18 * x * y**2),
    (r"\frac{\partial}{\partial x}\frac{\partial}{\partial y}(x^2y^3)", (y, x), 6 * x * y**2),
    (r"\frac{\partial^2}{\partial x\partial y}\frac{x}{1+y}", (y, x), -1 / (1 + y)**2),
    (r"\frac{\partial^2}{\partial y\partial x}(x^2\cos(xy))",
     (x, y), -x**2 * (x * y * sp.cos(x * y) + 3 * sp.sin(x * y))),
])
def test_partial_derivatives_keep_the_order_and_round_trip(latex, variables, expected):
    p = parse(latex)
    topic, sol = solve(p, Topic.unknown)

    assert topic == Topic.derivative
    assert p.expr.variables == variables
    assert sp.simplify(sol.answer - expected) == 0
    assert sol.steps[0].before == p.expr
    assert all(a.after == b.before for a, b in zip(sol.steps, sol.steps[1:]))
    result = verify(p, topic, sol)
    assert result.verified, result.details
    result = verify_latex_steps(p, topic, [(step.rule, sol.format_latex(step.after)) for step in sol.steps])
    assert result.verified, result.details


def test_partial_derivative_with_indexed_variable():
    topic, sol, result = run(r"\frac{\partial}{\partial x_1}(x_1^2x_2)")
    assert topic == Topic.derivative
    assert sol.answer_latex == r"2 x_{1} x_{2}"
    assert result.verified, result.details


def test_sum_of_partial_derivatives_is_evaluated():
    topic, sol, result = run(r"\frac{\partial x^2}{\partial x}+\frac{\partial y^2}{\partial y}")
    assert topic == Topic.derivative
    assert sol.answer == 2 * x + 2 * y
    assert result.verified, result.details


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


@pytest.mark.parametrize("latex, order, expected", [
    (r"\int_0^1\int_0^x xy\,dy\,dx", ("y", "x"), sp.Rational(1, 8)),
    (r"\int_0^1\int_0^x\int_0^y xyz\,dz\,dy\,dx", ("z", "y", "x"), sp.Rational(1, 48)),
    (r"\iint xy\,dx\,dy", ("x", "y"), x**2 * sp.Symbol("y")**2 / 4),
    (r"\iiint 1\,\mathrm{d}z\,\mathrm{d}y\,\mathrm{d}x", ("z", "y", "x"), x * sp.Symbol("y") * sp.Symbol("z")),
    (r"\int \int_0^1 xy\,dy\,dx", ("y", "x"), x**2 / 4),
    (r"\int_0^\pi\left[\int_0^1 r\sin(\theta)\,dr\right]\,d\theta", ("r", "theta"), 1),
    (r"\int_0^1\int_0^x\frac{1}{1+y^2}\,dy\,dx", ("y", "x"), sp.pi / 4 - sp.log(2) / 2),
])
def test_iterated_integrals_evaluate_in_differential_order(latex, order, expected):
    p = parse(latex)
    topic, sol = solve(p, Topic.unknown)
    assert topic == Topic.integral
    assert tuple(var.name for var in p.expr.variables) == order
    assert sol.answer == expected
    assert len(sol.steps) == len(order)
    assert sol.steps[0].before == p.expr
    for i, step in enumerate(sol.steps[:-1], start=1):
        assert step.after == sol.steps[i].before
        assert step.after.limits == p.expr.limits[i:]
    assert sol.steps[-1].after == expected
    result = verify(p, topic, sol)
    assert result.verified, result.details
    result = verify_latex_steps(p, topic, [(step.rule, sp.latex(step.after)) for step in sol.steps])
    assert result.verified, result.details


@pytest.mark.parametrize("latex", [
    r"\int_0^1\int_0^x xy\,dy",
    r"\int_0^1\int_0^1 x\,dx\,dy\,dz",
    r"\iint_D xy\,dx\,dy",
])
def test_iterated_integrals_reject_missing_or_ambiguous_notation(latex):
    with pytest.raises(ParseError):
        parse(latex)


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
    with pytest.raises(ParseError):
        parse(r"\frac{d}{dx} \int")
