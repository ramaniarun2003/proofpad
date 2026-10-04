"""Rule-based integral steps (starter: linearity, power/table rules,
u-substitution, integration by parts, CAS fallback).

Same chain invariant as derivative.py: every `after` equals the original
integral up to an additive constant. Definite integrals are finished with
the fundamental theorem of calculus as a final step.
"""
from __future__ import annotations

import sympy as sp

from .base import Solution, StepRec

MAX_STEPS = 40
_TABLE_FUNCS = (sp.sin, sp.cos, sp.exp, sp.log, sp.tan, sp.sinh, sp.cosh)


def _first_integral_node(expr: sp.Basic) -> sp.Integral | None:
    for node in sp.preorder_traversal(expr):
        if isinstance(node, sp.Integral):
            return node
    return None


def _I(f: sp.Basic, x: sp.Symbol) -> sp.Basic:
    return sp.Integral(f, x)


def _try_usub(f: sp.Basic, x: sp.Symbol):
    """Find g(u(x)) * u'(x) * c. Returns (u, integrand_in_U, U) or None."""
    U = sp.Dummy("u")
    candidates = []
    for node in sp.preorder_traversal(f):
        if isinstance(node, sp.Function) and node.args and node.args[0].has(x):
            if node.args[0] != x:
                candidates.append(node.args[0])   # inner argument, e.g. x^2 in cos(x^2)
            candidates.append(node)               # the function itself, e.g. sin(x) in sin(x)cos(x)
        elif isinstance(node, sp.Pow) and node.base != x and node.base.has(x) and not node.exp.has(x):
            candidates.append(node.base)
    for u in candidates:
        du = sp.diff(u, x)
        if du == 0:
            continue
        ratio = sp.simplify(f / du)
        g = ratio.subs(u, U)
        if not g.has(x) and g.has(U):
            return u, g, U
    return None


def _try_by_parts(f: sp.Basic, x: sp.Symbol):
    """LIATE heuristic for a two-factor product: returns (u, dv) or None."""
    if not isinstance(f, sp.Mul):
        return None
    factors = list(f.args)
    if len(factors) != 2:
        return None

    def rank(t):  # lower = better choice for u
        if t.has(sp.log):
            return 0
        if t.has(sp.asin, sp.acos, sp.atan):
            return 1
        if t.is_polynomial(x):
            return 2
        if t.has(sp.sin, sp.cos):
            return 3
        if t.has(sp.exp):
            return 4
        return 5

    a, b = sorted(factors, key=rank)
    if rank(a) == rank(b) or rank(a) == 5:
        return None
    return a, b


def _apply_rule(f: sp.Basic, x: sp.Symbol, depth: int) -> tuple[str, sp.Basic, str]:
    if not f.has(x):
        return "constant rule", f * x, f"∫ c d{x} = c{x}"
    if isinstance(f, sp.Add):
        return "linearity (sum)", sp.Add(*[_I(t, x) for t in f.args]), "integrate each term"
    const, rest = f.as_independent(x)
    if const != 1:
        return "linearity (constant multiple)", const * _I(rest, x), f"pull out {sp.latex(const)}"
    if f == x:
        return "power rule", x**2 / 2, "∫ x dx = x²/2"
    if isinstance(f, sp.Pow) and f.base == x and not f.exp.has(x):
        n = f.exp
        if n == -1:
            return "log rule", sp.log(sp.Abs(x)), "∫ 1/x dx = ln|x|"
        return "power rule", x**(n + 1) / (n + 1), f"∫ x^n dx = x^(n+1)/(n+1), n = {sp.latex(n)}"
    if isinstance(f, _TABLE_FUNCS) and f.args[0] == x:
        return f"table: ∫ {f.func.__name__}", sp.integrate(f, x), "standard antiderivative"
    if isinstance(f, sp.exp) and f.args[0] == x:
        return "table: ∫ e^x", f, "∫ e^x dx = e^x"

    sub = _try_usub(f, x)
    if sub is not None:
        u, g, U = sub
        inner = sp.integrate(g, U)
        return ("u-substitution",
                inner.subs(U, u),
                f"let u = {sp.latex(u)}, du = {sp.latex(sp.diff(u, x))} d{x}; integrand becomes {sp.latex(g).replace(sp.latex(U), 'u')}")

    parts = _try_by_parts(f, x) if depth < 3 else None
    if parts is not None:
        u, dv = parts
        v = sp.integrate(dv, x)
        du = sp.diff(u, x)
        return ("integration by parts",
                u * v - _I(v * du, x),
                f"u = {sp.latex(u)}, dv = {sp.latex(dv)} d{x}; ∫u dv = uv − ∫v du")

    if f.is_rational_function(x):
        pf = sp.apart(f, x)
        if pf != f and isinstance(pf, sp.Add):
            return "partial fractions", _I(pf, x), "decompose the rational integrand"

    return "computer algebra system", sp.integrate(f, x), "no hand rule matched; SymPy computed this integral"


def generate(inner: sp.Basic, x: sp.Symbol, limits: tuple | None = None) -> Solution:
    sol = Solution()
    expr: sp.Basic = sp.Integral(inner, x)
    depth = 0
    for _ in range(MAX_STEPS):
        node = _first_integral_node(expr)
        if node is None:
            break
        rule, replacement, note = _apply_rule(node.function, x, depth)
        if rule == "integration by parts":
            depth += 1
        new_expr = expr.xreplace({node: replacement})
        sol.steps.append(StepRec(rule=rule, before=expr, after=new_expr, note=note))
        expr = new_expr
    final = sp.simplify(expr)
    if final != expr:
        sol.steps.append(StepRec(rule="simplify", before=expr, after=final, note="simplify the antiderivative"))
    if limits is not None:
        a, b = limits
        value = sp.simplify(final.subs(x, b) - final.subs(x, a))
        sol.steps.append(StepRec(rule="fundamental theorem of calculus",
                                 before=final, after=value,
                                 note=f"F({sp.latex(b)}) − F({sp.latex(a)})"))
        sol.answer = value
    else:
        sol.answer = final
    return sol
