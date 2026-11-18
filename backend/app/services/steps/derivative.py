"""Rule-based derivative steps.

Work from the innermost derivative outward. Apply one named rule to the
next differentiation variable and keep the remaining derivatives in place.
Each step records the entire expression before and after the rewrite.

Because each step rewrites one node with an equal expression, every
intermediate expression equals the true derivative — the verifier relies on
that. Add rules by extending `_apply_rule`; keep the fallback last.
"""
from __future__ import annotations

import sympy as sp
from sympy.printing.latex import LatexPrinter

from .base import Solution, StepRec

MAX_STEPS = 60


class _PartialLatexPrinter(LatexPrinter):
    def _print_Derivative(self, expr):
        denominator = []
        order = 0
        for variable, count in reversed(expr.variable_count):
            exponent = "" if count == 1 else f"^{{{count}}}"
            denominator.append(r"\partial " + self._print(variable) + exponent)
            order += count
        exponent = "" if order == 1 else f"^{{{order}}}"
        operand = self._print(expr.expr)
        return rf"\frac{{\partial{exponent} \left({operand}\right)}}{{{''.join(denominator)}}}"

    def _print(self, expr, **kwargs):
        latex = super()._print(expr, **kwargs)
        # Keep any following product outside the scope of a function call.
        return "{" + latex + "}" if isinstance(expr, sp.Function) else latex


def _partial_latex(expr: sp.Basic) -> str:
    needs_mul_symbol = any(
        node.is_Mul and any(factor.is_Add or isinstance(factor, sp.Derivative) for factor in node.args)
        for node in sp.preorder_traversal(expr)
    )
    return _PartialLatexPrinter({
        "mul_symbol": "dot" if needs_mul_symbol else None,
    }).doprint(expr)


def _D(f: sp.Basic, x: sp.Symbol) -> sp.Basic:
    """Unevaluated derivative, unless it is trivially constant."""
    return sp.Derivative(f, x)


def _apply_rule(f: sp.Basic, x: sp.Symbol) -> tuple[str, sp.Basic, str]:
    """Return (rule name, replacement for Derivative(f, x), human note)."""
    if not f.has(x):
        return "constant rule", sp.Integer(0), f"{sp.latex(f)} does not depend on {x}, so its derivative is 0"
    if f == x:
        return "identity", sp.Integer(1), f"d/d{x} of {x} is 1"

    if isinstance(f, sp.Add):
        return ("sum rule", sp.Add(*[_D(t, x) for t in f.args]),
                "differentiate each term separately")

    # Quotient rule before product rule, so u/v is not treated as u * v^-1.
    numer, denom = f.as_numer_denom()
    if denom != 1 and denom.has(x):
        u, v = numer, denom
        return ("quotient rule",
                (_D(u, x) * v - u * _D(v, x)) / v**2,
                f"(u/v)' = (u'v - uv')/v^2 with u = {sp.latex(u)}, v = {sp.latex(v)}")

    if isinstance(f, sp.Mul):
        const, rest = f.as_independent(x)
        if const != 1:
            return ("constant multiple", const * _D(rest, x),
                    f"pull the constant {sp.latex(const)} out")
        u, v = f.args[0], sp.Mul(*f.args[1:])
        return ("product rule", _D(u, x) * v + u * _D(v, x),
                f"(uv)' = u'v + uv' with u = {sp.latex(u)}, v = {sp.latex(v)}")

    if isinstance(f, sp.Pow):
        base, expo = f.args
        if not expo.has(x):
            if base == x:
                return ("power rule", expo * x**(expo - 1),
                        f"d/d{x} {x}^n = n {x}^(n-1) with n = {sp.latex(expo)}")
            return ("power rule + chain rule", expo * base**(expo - 1) * _D(base, x),
                    f"outer power with n = {sp.latex(expo)}, inner g = {sp.latex(base)}")
        if not base.has(x):
            if base == sp.E:
                return ("exponential rule + chain rule", sp.exp(expo) * _D(expo, x),
                        f"d/du e^u = e^u, inner u = {sp.latex(expo)}")
            return ("exponential rule + chain rule", f * sp.log(base) * _D(expo, x),
                    f"d/du a^u = a^u ln(a), a = {sp.latex(base)}, inner u = {sp.latex(expo)}")
        # x-dependent base and exponent: logarithmic differentiation
        return ("logarithmic differentiation",
                f * (_D(expo, x) * sp.log(base) + expo * _D(base, x) / base),
                "write f = e^(g ln h); then f' = f (g' ln h + g h'/h)")

    if isinstance(f, sp.Function) and len(f.args) == 1:
        inner = f.args[0]
        u = sp.Dummy("u")
        outer = sp.diff(f.func(u), u)            # table lookup for the outer function
        if inner == x:
            return (f"derivative of {f.func.__name__}", outer.subs(u, x),
                    f"table: d/d{x} {sp.latex(f)} = {sp.latex(outer.subs(u, x))}")
        return (f"chain rule with {f.func.__name__}",
                outer.subs(u, inner) * _D(inner, x),
                f"outer {f.func.__name__}'(u) times inner u' with u = {sp.latex(inner)}")

    # Anything else: let the CAS do it, but say so.
    return "computer algebra system", sp.diff(f, x), "no hand rule matched; SymPy computed this node"


def generate(expr: sp.Basic, *, partial: bool = False) -> Solution:
    sol = Solution(format_latex=_partial_latex if partial else sp.latex)
    unresolved: set[sp.Derivative] = set()
    for _ in range(MAX_STEPS):
        node = next(
            (part for part in sp.postorder_traversal(expr)
             if isinstance(part, sp.Derivative) and part not in unresolved),
            None,
        )
        if node is None:
            break
        (x, count), *remaining = node.variable_count
        rule, replacement, note = _apply_rule(node.expr, x)
        if count > 1:
            remaining.insert(0, (x, count - 1))
        if remaining and replacement != 0:
            replacement = sp.Derivative(replacement, *remaining)
        if replacement == node:
            unresolved.add(node)
            continue
        new_expr = expr.xreplace({node: replacement})
        sol.steps.append(StepRec(
            rule=rule, before=expr, after=new_expr,
            note=f"Differentiate with respect to {x}. {note}" if partial else note,
        ))
        expr = new_expr
    final = sp.simplify(expr)
    if final != expr:
        sol.steps.append(StepRec(rule="simplify", before=expr, after=final, note="collect and simplify terms"))
    sol.answer = final
    return sol
