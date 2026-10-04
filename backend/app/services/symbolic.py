"""LaTeX → SymPy expression tree, plus topic classification."""
from __future__ import annotations

from dataclasses import dataclass

import sympy as sp
from sympy.parsing.latex import parse_latex

from ..models import Topic
from .latex_norm import normalize


class ParseError(ValueError):
    """Raised when LaTeX cannot be turned into a SymPy expression."""


@dataclass
class Parsed:
    expr: sp.Basic          # the whole expression, e.g. Derivative(x**2*sin(x), x)
    inner: sp.Basic         # the operand, e.g. x**2*sin(x)
    topic: Topic
    var: sp.Symbol
    limits: tuple | None = None   # (lower, upper) for definite integrals / sums
    index: sp.Symbol | None = None  # summation index for series


def _fix_constants(expr: sp.Basic) -> sp.Basic:
    """Recognizer output uses the symbol 'e'; SymPy wants the constant E."""
    e = sp.Symbol("e")
    if e in expr.free_symbols:
        expr = expr.subs(e, sp.E)
    # The parser emits log(a, E) for \ln; collapse it to the one-argument form.
    expr = expr.replace(
        lambda t: isinstance(t, sp.log) and len(t.args) == 2 and t.args[1] == sp.E,
        lambda t: sp.log(t.args[0]),
    )
    return expr


def parse(latex: str, variable: str = "x") -> Parsed:
    text = normalize(latex)
    if not text:
        raise ParseError("empty expression")
    try:
        expr = parse_latex(text)
    except Exception as exc:  # antlr raises a variety of exception types
        raise ParseError(f"could not parse '{text}': {exc}") from exc
    expr = _fix_constants(expr)
    var = sp.Symbol(variable)

    if isinstance(expr, sp.Derivative):
        v = expr.variables[0] if expr.variables else var
        return Parsed(expr=expr, inner=expr.expr, topic=Topic.derivative, var=v)
    if isinstance(expr, sp.Integral):
        lim = expr.limits[0]
        v = lim[0]
        bounds = (lim[1], lim[2]) if len(lim) == 3 else None
        return Parsed(expr=expr, inner=expr.function, topic=Topic.integral, var=v, limits=bounds)
    if isinstance(expr, sp.Sum):
        lim = expr.limits[0]
        return Parsed(expr=expr, inner=expr.function, topic=Topic.series, var=var,
                      limits=(lim[1], lim[2]), index=lim[0])
    # A bare expression: treat as "differentiate this" only if the caller says so.
    return Parsed(expr=expr, inner=expr, topic=Topic.unknown, var=var)


def guess_topic(latex: str) -> Topic:
    try:
        return parse(latex).topic
    except ParseError:
        return Topic.unknown


def to_latex(expr: sp.Basic) -> str:
    return sp.latex(expr)
