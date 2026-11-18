"""LaTeX → SymPy expression tree, plus topic classification."""
from __future__ import annotations

import re
from dataclasses import dataclass

import sympy as sp
from sympy.parsing.latex import parse_latex

from ..models import Topic
from .latex_norm import normalize

_INTEGRAL_SIGN = re.compile(r"\\(?:iiint|iint|int)(?![A-Za-z])")
_LATEX_TOKEN = re.compile(r"d\s*(\\[A-Za-z]+|[A-Za-z])|\\[A-Za-z]+|[^\s)}\]]")
_EXPANDED_INTEGRALS = {r"\iint": r"\int \int", r"\iiint": r"\int \int \int"}


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

    @property
    def iterated_integral(self) -> sp.Integral | None:
        if isinstance(self.expr, sp.Integral) and len(self.expr.limits) > 1:
            return self.expr
        return None


def _fix_constants(expr: sp.Basic) -> sp.Basic:
    """Convert the LaTeX parser's symbols for e and pi to numeric constants."""
    for symbol, constant in ((sp.Symbol("e"), sp.E), (sp.Symbol("pi"), sp.pi)):
        if symbol in expr.free_symbols:
            expr = expr.subs(symbol, constant)
    # The parser emits log(a, E) for \ln; collapse it to the one-argument form.
    expr = expr.replace(
        lambda t: isinstance(t, sp.log) and len(t.args) == 2 and t.args[1] == sp.E,
        lambda t: sp.log(t.args[0]),
    )
    return expr


def _expand_integral_sign(match: re.Match[str]) -> str:
    sign = match[0]
    if sign in _EXPANDED_INTEGRALS and match.string[match.end():].lstrip().startswith(
        ("_", "^", r"\limits")
    ):
        raise ParseError(r"write a separate \int with bounds for each variable")
    return _EXPANDED_INTEGRALS.get(sign, sign)


def _trailing_differentials(text: str) -> list[str]:
    # The parser can silently assume dx for a missing differential. Read the
    # explicit trailing differentials, including those inside a closing group.
    names: list[str] = []
    for token in _LATEX_TOKEN.finditer(text):
        if token[1] is None:
            names.clear()
        else:
            names.append(token[1].removeprefix("\\"))
    return names


def parse(latex: str, variable: str = "x") -> Parsed:
    text = normalize(latex)
    if not text:
        raise ParseError("empty expression")
    text = _INTEGRAL_SIGN.sub(_expand_integral_sign, text)
    has_multiple_signs = len(_INTEGRAL_SIGN.findall(text)) > 1
    try:
        expr = parse_latex(text, strict=has_multiple_signs)
    except Exception as exc:  # antlr raises a variety of exception types
        raise ParseError(f"could not parse '{text}': {exc}") from exc
    if isinstance(expr, sp.Integral) and len(expr.limits) > 1:
        if _trailing_differentials(text) != [v.name for v in expr.variables]:
            raise ParseError("write one differential per integral sign, in integration order")
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
    if has_multiple_signs and any(len(node.limits) > 1 for node in expr.atoms(sp.Integral)):
        raise ParseError("write the iterated integral as a standalone expression")
    # A bare expression: treat as "differentiate this" only if the caller says so.
    return Parsed(expr=expr, inner=expr, topic=Topic.unknown, var=var)


def guess_topic(latex: str) -> Topic:
    try:
        return parse(latex).topic
    except ParseError:
        return Topic.unknown


def to_latex(expr: sp.Basic) -> str:
    return sp.latex(expr)
