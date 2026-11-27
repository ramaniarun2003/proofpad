"""Limit steps: apply rules in a fixed order, stop at the first that resolves
the (sub)limit, then recurse on the rewritten limit until a value remains.

Every step's ``before``/``after`` are *equal limits* (or a limit and its value):
``steps[i].after == steps[i+1].before``, and every ``after`` evaluated with
``.doit()`` equals the true limit — so the verifier can check each step
independently against ``sympy.limit``.

Rule order (as taught): direct substitution, factor & cancel, rationalize,
standard limits, L'Hopital (at most 3 applications), then limits at infinity by
dividing by the highest power. A two-sided limit whose one-sided limits
disagree is reported as "does not exist".
"""
from __future__ import annotations

import sympy as sp

from .base import Solution, StepRec

MAX_STEPS = 20
MAX_LHOPITAL = 3
DNE = "does not exist"

_INF = (sp.oo, -sp.oo, sp.zoo)


def _lim(expr: sp.Basic, var: sp.Symbol, point, direction: str) -> sp.Limit:
    return sp.Limit(expr, var, point, direction)


def _direct_value(f: sp.Basic, var: sp.Symbol, point) -> sp.Basic | None:
    """The substituted value, if it is a finite determinate number; else None."""
    try:
        val = sp.simplify(f.subs(var, point))
    except Exception:
        return None
    if val.has(sp.nan, sp.zoo) or val in _INF:
        return None
    if val.is_number and val.is_finite:
        return val
    return None


def _side(f, var, point, direction):
    try:
        return sp.limit(f, var, point, direction)
    except Exception:
        return None


def _is_poly(e, var) -> bool:
    try:
        return bool(e.is_polynomial(var))
    except Exception:
        return False


def _factor_cancel(f, var, point):
    """0/0 with polynomial numerator & denominator → cancel the common factor."""
    num, den = sp.fraction(sp.together(f))
    if den == 1 or not (_is_poly(num, var) and _is_poly(den, var)):
        return None
    if sp.simplify(num.subs(var, point)) != 0 or sp.simplify(den.subs(var, point)) != 0:
        return None
    cancelled = sp.cancel(f)
    return cancelled if cancelled != f else None


def _has_sqrt(e) -> bool:
    return any(
        isinstance(n, sp.Pow) and n.exp.is_Rational and n.exp.q == 2
        for n in sp.preorder_traversal(e)
    )


def _rationalize(f, var, point):
    """0/0 involving a square root → multiply top and bottom by the conjugate.

    Expand whichever side carries the surd so the root is actually cleared by the
    difference of squares (plain ``simplify`` would just cancel the conjugate back).
    """
    num, den = sp.fraction(sp.together(f))

    def conjugate(e):
        if isinstance(e, sp.Add) and any(_has_sqrt(t) for t in e.args):
            # Flip the sign of the surd term(s); (a-√b)(a+√b) clears the root.
            return sp.Add(*[(-t if _has_sqrt(t) else t) for t in e.args])
        return None

    cn = conjugate(num)
    if cn is not None:
        new = sp.cancel(sp.expand(num * cn) / (den * cn))
        if new != f:
            return new
    cd = conjugate(den)
    if cd is not None:
        new = sp.cancel((num * cd) / sp.expand(den * cd))
        if new != f:
            return new
    return None


def _standard(f, var, point):
    """The four memorized limits, as x → 0."""
    if point != 0:
        return None
    forms = [
        (sp.sin(var) / var, sp.Integer(1), "memorized limit: sin x / x → 1 as x → 0"),
        ((1 - sp.cos(var)) / var, sp.Integer(0), "memorized limit: (1 − cos x) / x → 0 as x → 0"),
        ((sp.exp(var) - 1) / var, sp.Integer(1), "memorized limit: (eˣ − 1) / x → 1 as x → 0"),
        (sp.log(1 + var) / var, sp.Integer(1), "memorized limit: ln(1 + x) / x → 1 as x → 0"),
    ]
    for form, val, note in forms:
        try:
            if sp.simplify(f - form) == 0:
                return val, note
        except Exception:
            pass
    return None


def _lhopital(f, var, point):
    """If f = p/q is 0/0 or ∞/∞, return p'/q'."""
    num, den = sp.fraction(sp.together(f))
    if den == 1 or not den.has(var):
        return None
    ln, ld = _side(num, var, point, "+-"), _side(den, var, point, "+-")
    if ln is None or ld is None:
        return None
    zero_zero = (ln == 0 and ld == 0)
    inf_inf = (ln in _INF and ld in _INF)
    if not (zero_zero or inf_inf):
        return None
    dq = sp.diff(den, var)
    if dq == 0:
        return None
    return sp.cancel(sp.diff(num, var) / dq)


def _divide_highest_power(f, var):
    """Rational function at infinity: divide numerator & denominator by x^k."""
    num, den = sp.fraction(sp.together(f))
    if den == 1 or not (_is_poly(num, var) and _is_poly(den, var)):
        return None
    k = max(sp.degree(num, var), sp.degree(den, var))
    if k <= 0:
        return None
    divided = sp.expand(num / var**k) / sp.expand(den / var**k)
    return divided, k


def generate(inner: sp.Basic, var: sp.Symbol, point, direction: str = "+-") -> Solution:
    sol = Solution()
    L0 = _lim(inner, var, point, direction)

    # Does-not-exist: a two-sided limit whose one-sided limits disagree.
    if direction == "+-":
        left, right = _side(inner, var, point, "-"), _side(inner, var, point, "+")
        if left is not None and right is not None and left != right:
            note = (f"left-hand limit = {sp.latex(left)}, right-hand limit = {sp.latex(right)}; "
                    "they disagree, so the two-sided limit does not exist")
            sol.steps.append(StepRec("one-sided limits", L0, L0, note=note, verdict=DNE))
            sol.verdict = DNE
            return sol

    expr = inner
    lhopital_used = 0
    for _ in range(MAX_STEPS):
        cur = _lim(expr, var, point, direction)

        # 1. Direct substitution (finite point only).
        if point not in _INF:
            val = _direct_value(expr, var, point)
            if val is not None:
                sol.steps.append(StepRec(
                    "direct substitution", cur, val,
                    note=f"substitute {sp.latex(var)} = {sp.latex(point)}; the expression is defined and equals {sp.latex(val)}"))
                sol.answer = val
                return sol

        # 2. Factor and cancel (0/0, polynomial over polynomial).
        fc = _factor_cancel(expr, var, point)
        if fc is not None:
            sol.steps.append(StepRec(
                "factor and cancel", cur, _lim(fc, var, point, direction),
                note=f"both parts are 0 at {sp.latex(var)} = {sp.latex(point)}; factor and cancel the common term"))
            expr = fc
            continue

        # 3. Rationalize (0/0 with a root).
        rt = _rationalize(expr, var, point)
        if rt is not None:
            sol.steps.append(StepRec(
                "rationalize", cur, _lim(rt, var, point, direction),
                note="multiply numerator and denominator by the conjugate to clear the root"))
            expr = rt
            continue

        # 4. Standard limits (sin x / x, (1 - cos x)/x, (e^x - 1)/x, ln(1+x)/x).
        std = _standard(expr, var, point)
        if std is not None:
            val, note = std
            sol.steps.append(StepRec("standard limit", cur, val, note=note))
            sol.answer = val
            return sol

        # 5. Limits at infinity: a rational function is handled by dividing by the
        #    highest power (preferred over L'Hopital at ∞ so the steps read the way
        #    they're taught); non-rational ∞/∞ forms fall through to L'Hopital below.
        if point in (sp.oo, -sp.oo):
            dv = _divide_highest_power(expr, var)
            if dv is not None:
                divided, k = dv
                sol.steps.append(StepRec(
                    "divide by the highest power", cur, _lim(divided, var, point, direction),
                    note=f"divide top and bottom by {sp.latex(var**k)}"))
                val = sp.limit(expr, var, point, direction)
                sol.steps.append(StepRec(
                    "evaluate at infinity", _lim(divided, var, point, direction), val,
                    note=f"every 1/{sp.latex(var)}ⁿ term goes to 0, leaving {sp.latex(val)}"))
                sol.answer = val
                return sol

        # 6. L'Hopital's rule (0/0 or ∞/∞), at most 3 applications.
        if lhopital_used < MAX_LHOPITAL:
            lh = _lhopital(expr, var, point)
            if lh is not None:
                lhopital_used += 1
                sol.steps.append(StepRec(
                    "L'Hopital's rule", cur, _lim(lh, var, point, direction),
                    note="0/0 or ∞/∞: differentiate the top and bottom, then take the limit again"))
                expr = lh
                continue

        break  # no rule applied

    # Fallback: let SymPy finish, and report the outcome honestly.
    final = sp.Limit(expr, var, point, direction).doit()
    if final == sp.zoo:
        sol.steps.append(StepRec("computer algebra system", _lim(expr, var, point, direction),
                                 _lim(expr, var, point, direction),
                                 note="the limit is undefined (no finite value)", verdict=DNE))
        sol.verdict = DNE
        return sol
    sol.steps.append(StepRec(
        "computer algebra system", _lim(expr, var, point, direction), final,
        note="no elementary rule applied; SymPy evaluated the limit"))
    sol.answer = final
    return sol
