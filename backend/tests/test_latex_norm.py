"""latex_norm at the token level: implicit multiplication, and what must be left alone."""
import pytest
import sympy as sp

from app.services.latex_norm import declared_functions, implicit_multiplication
from app.services.symbolic import ParseError, parse


@pytest.mark.parametrize("latex, expected", [
    # a variable before a parenthesis
    (r"x(2\sin x + x\cos x)", r"x\cdot (2\sin x + x\cos x)"),
    (r"x\left(x+1\right)", r"x\cdot \left(x+1\right)"),
    (r"3x(x+1)", r"3x\cdot (x+1)"),
    (r"n(n+1)", r"n\cdot (n+1)"),
    (r"t(t-1) + k(k+1)", r"t\cdot (t-1) + k\cdot (k+1)"),
    (r"x^2(x+1)", r"x^2\cdot (x+1)"),
    (r"x^{n}(x+1)", r"x^{n}\cdot (x+1)"),
    (r"x_{1}(x+1)", r"x_{1}\cdot (x+1)"),
    (r"e^{x}(x-1)", r"e^{x}\cdot (x-1)"),
    # a closing parenthesis before a letter or another parenthesis
    (r"(x+1)(x-1)", r"(x+1)\cdot (x-1)"),
    (r"(2\sin x + x\cos x)x", r"(2\sin x + x\cos x)\cdot x"),
    (r"\ln(x)(x+1)", r"\ln(x)\cdot (x+1)"),
    # a digit before a parenthesis
    (r"2(x+1)", r"2\cdot (x+1)"),
    (r"2.5(x)", r"2.5\cdot (x)"),
    # nested
    (r"x(y(z+1))", r"x\cdot (y\cdot (z+1))"),
])
def test_products_are_written_out(latex, expected):
    assert implicit_multiplication(latex) == expected


@pytest.mark.parametrize("latex", [
    r"\sin(x)", r"\cos(2x)", r"\tan(x)", r"\ln(x)", r"\log(x)", r"\exp(x)", r"\sqrt{x}",
    r"\sin^2(x)", r"\sin^{2}(x)", r"\log_2(x)", r"\log_{2}(x)",       # the command's own argument
    r"\frac{d}{dx}(x^2)", r"\frac{d}{d x}\left(x^{2} \sin x\right)",   # an operator applied to the parenthesis
    r"d/dx(x^2)", r"d / d x(x^{2})", r"\partial / \partial x\left(x^{2} y\right)",
    r"\frac{1}{x}(x+1)", r"\sqrt{x}(x+1)",                             # the parser already multiplies after a group
    r"\int (x+1) dx", r"\int_{0}^{1}(x+1)dx", r"\int (x+1) d\theta",   # a differential is not a factor
    r"\sum_{n=1}^{\infty}(n+1)", r"\lim_{x \to 0}(x+1)",
    r"y'(x)", r"f'(x)", r"f^{\prime}(x)",                              # a primed name is a function
    r"\operatorname{arcsinh}(x)",
])
def test_these_are_left_alone(latex):
    assert implicit_multiplication(latex) == latex


def test_f_g_h_are_functions_only_when_the_text_declares_them():
    assert declared_functions(r"f(x) g(x)") == {"f", "g"}
    assert declared_functions(r"f'(2x)") == {"f"}
    assert declared_functions(r"x(x+1) + f(x+1)") == frozenset()
    assert implicit_multiplication(r"f(x)") == r"f(x)"                          # declared by f(x) itself
    assert implicit_multiplication(r"f(x+1)") == r"f\cdot (x+1)"                # f is just a letter here
    assert implicit_multiplication(r"f'(x) + f(x+1)") == r"f'(x) + f(x+1)"      # declared by the prime
    assert implicit_multiplication(r"h(2x)", functions={"h"}) == r"h(2x)"       # declared by the problem
    assert implicit_multiplication(r"f(x)g(x)") == r"f(x)\cdot g(x)"
    # x is never a function, even next to one
    assert implicit_multiplication(r"x(f(x))") == r"x\cdot (f(x))"


@pytest.mark.parametrize("latex, expected", [
    (r"x(2\sin x + x\cos x)", "x*(x*cos(x) + 2*sin(x))"),
    (r"x(x\cos x + 2\sin x)", "x*(x*cos(x) + 2*sin(x))"),
    (r"(2\sin x + x\cos x)x", "x*(x*cos(x) + 2*sin(x))"),
    (r"2(x+1)", "2*(x + 1)"),
    (r"(x+1)(x-1)", "(x - 1)*(x + 1)"),
    (r"\sin(x)", "sin(x)"),
    (r"e^{x}(x-1)", "(x - 1)*exp(x)"),
    (r"\frac{d}{dx}(x^2)", "Derivative(x**2, x)"),
    (r"3x(x+1)", "3*(x*(x + 1))"),
    (r"n(n+1)", "n*(n + 1)"),
    (r"x_1(x+1)", "x_{1}*(x + 1)"),
    # the product stays inside the operator that precedes it
    (r"\frac{d}{dx} x(x+1)", "Derivative(x*(x + 1), x)"),
    (r"d / d x x(x+1)", "Derivative(x*(x + 1), x)"),
    (r"\int x(x+1) dx", "Integral(x*(x + 1), x)"),
    (r"\sum_{n=1}^{\infty} \frac{1}{n(n+1)}", "Sum(1/(n*(n + 1)), (n, 1, oo))"),
    # two parentheses after an operator read as before this change: one product under it
    (r"\frac{d}{dx}(x^2)(x+1)", "Derivative(x**2*(x + 1), x)"),
])
def test_parse_reads_products_not_calls(latex, expected):
    expr = parse(latex).expr
    assert str(expr) == expected
    assert not expr.atoms(sp.core.function.AppliedUndef)


def test_declared_functions_survive_parsing():
    p = parse(r"f(x)")
    assert p.functions == {"f"} and str(p.expr) == "f(x)"
    assert str(parse(r"h(2x)", functions={"h"}).expr) == "h(2*x)"
    assert str(parse(r"f(x+1)").expr) == "f*(x + 1)"


def test_a_variable_parsed_as_a_function_is_an_error(monkeypatch):
    """If the rewrite ever misses a case, the parser must say so instead of returning x(…)."""
    from app.services import latex_norm
    monkeypatch.setattr(latex_norm, "implicit_multiplication", lambda s, functions=None, times="": s)  # rewrite off
    with pytest.raises(ParseError, match=r"parsed x\(\.\.\.\) as a function"):
        parse(r"x(2\sin x + x\cos x)")


@pytest.mark.parametrize("latex, expected", [
    # a function's bare argument ends before a parenthesis: sin(x)·(x+1), not sin(x(x+1))
    (r"\sin x (x+1)", "(x + 1)*sin(x)"),
    (r"\sin x(x+1)", "(x + 1)*sin(x)"),
    (r"\sin 2 x\left(x+1\right)", "(x + 1)*sin(2*x)"),
    (r"\cos \theta (x+1)", "(x + 1)*cos(theta)"),
    (r"\sin^{2} x (x+1)", "(x + 1)*sin(x)**2"),
    (r"\sin x^2 (x+1)", "(x + 1)*sin(x**2)"),
    (r"\ln x (x+1)", "(x + 1)*log(x)"),
    (r"\log_2 x (x+1)", "(x + 1)*log(x, 2)"),
    (r"e^{x} \sin x (2x)", "2*x*exp(x)*sin(x)"),
    (r"\cos x \sin x (x+1)", "((x + 1)*sin(x))*cos(x)"),
    (r"\frac{d}{dx} \sin x (x+1)", "Derivative((x + 1)*sin(x), x)"),
    (r"\int \sin x (x+1) dx", "Integral((x + 1)*sin(x), x)"),
    # unchanged: the parenthesis is the argument, or there is no parenthesis
    (r"\sin(x+1)", "sin(x + 1)"),
    (r"\sin (3 x)", "sin(3*x)"),
    (r"\sin(x)(x+1)", "(x + 1)*sin(x)"),
    (r"\sin x \cos x", "sin(x)*cos(x)"),
    (r"\sin x + (x+1)", "(x + 1) + sin(x)"),
])
def test_a_bare_function_argument_ends_at_a_parenthesis(latex, expected):
    assert str(parse(latex).expr) == expected
