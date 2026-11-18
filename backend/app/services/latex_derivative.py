"""Parse partial derivative fractions using SymPy's first-order operator syntax."""
from __future__ import annotations

import re

import sympy as sp
from sympy.parsing.latex import parse_latex

_FRACTION = re.compile(r"\\frac\s*\{\s*\\partial(?![A-Za-z])")
_PARTIAL = re.compile(r"\\partial(?![A-Za-z])")
_ORDER = re.compile(r"\^\s*(?:\{\s*([0-9]+)\s*\}|([0-9]))")
_VARIABLE = re.compile(r"(?:\\[A-Za-z]+|[A-Za-z])(?:\s*_\s*(?:\{[^{}]+\}|[A-Za-z0-9]))?")
_ALIAS_DIGITS = str.maketrans("0123456789", "abcdefghij")


def _group_end(text: str, start: int) -> int:
    depth = 1
    for i in range(start + 1, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    raise ValueError("unclosed brace in derivative fraction")


def _read_order(text: str) -> tuple[int, str]:
    if not text.startswith("^"):
        return 1, text
    order = _ORDER.match(text)
    if order is None or (value := int(order[1] or order[2])) == 0:
        raise ValueError("derivative order must be a positive integer")
    return value, text[order.end():].lstrip()


def _read_variables(text: str) -> list[tuple[sp.Symbol, int]]:
    variables = []
    while text:
        differential = _PARTIAL.match(text)
        if differential is None:
            raise ValueError("expected a differential in the derivative denominator")
        text = text[differential.end():].lstrip()
        variable = _VARIABLE.match(text)
        if variable is None:
            raise ValueError("expected a differentiation variable")
        symbol = parse_latex(variable[0], strict=True)
        if not isinstance(symbol, sp.Symbol):
            raise ValueError("differentiation variable must be a symbol")
        count, text = _read_order(text[variable.end():].lstrip())
        variables.append((symbol, count))
    return variables


def parse_derivative_latex(text: str, *, strict: bool = False) -> sp.Basic:
    if not _PARTIAL.search(text):
        return parse_latex(text, strict=strict)

    # Give each fraction a first-order operator with a fresh variable so
    # SymPy can bind the operand using its own precedence rules.
    prefix = r"\proofpadderivative"
    while prefix in text:
        prefix += "z"
    aliases: dict[sp.Symbol, list[tuple[sp.Symbol, int]]] = {}
    for match in reversed(list(_FRACTION.finditer(text))):
        numerator_end = _group_end(text, text.index("{", match.start()))
        denominator = text[numerator_end + 1:].lstrip()
        if not denominator.startswith("{"):
            continue
        denominator_start = len(text) - len(denominator)
        denominator_end = _group_end(text, denominator_start)
        denominator = text[denominator_start + 1:denominator_end].strip()
        variables = _read_variables(denominator)
        order, operand = _read_order(text[match.end():numerator_end].strip())
        if sum(count for _, count in variables) != order:
            raise ValueError("derivative order does not match the denominator")

        alias = prefix + str(len(aliases)).translate(_ALIAS_DIGITS)
        # In Leibniz notation the innermost differential is on the right.
        aliases[sp.Symbol(alias[1:])] = variables[::-1]
        replacement = r"\frac{d}{d " + alias + "}"
        if operand:
            replacement = "(" + replacement + "{" + operand + "})"
        text = text[:match.start()] + replacement + text[denominator_end + 1:]

    if _PARTIAL.search(text):
        raise ValueError(r"write partial derivatives using \frac{\partial}{\partial x}")
    expr = parse_latex(text, strict=True)

    def restore(node: sp.Derivative) -> sp.Derivative:
        variables = []
        for variable, count in node.variable_count:
            variables.extend(aliases.get(variable, [(variable, count)]))
        return sp.Derivative(node.expr, *variables)

    return expr.replace(lambda node: isinstance(node, sp.Derivative), restore)
