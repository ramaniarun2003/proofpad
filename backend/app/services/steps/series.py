"""Series convergence steps: apply tests in a fixed order, stop at the first
conclusive one. Each step records the test and its outcome; the chain's
`verdict` is "converges" | "diverges" | "inconclusive".
"""
from __future__ import annotations

import sympy as sp

from .base import Solution, StepRec

CONV, DIV, INC = "converges", "diverges", "inconclusive"


def _step(series, rule: str, outcome: str, note: str) -> StepRec:
    return StepRec(rule=rule, before=series, after=series, note=note, verdict=outcome)


def _limit(expr: sp.Basic, n: sp.Symbol):
    """SymPy's limit() raises on some oscillating terms; treat that as unknown."""
    try:
        val = sp.limit(expr, n, sp.oo)
    except (NotImplementedError, ValueError, TypeError):
        return None
    return val if val.is_number else None


def _split_alternating(a_n: sp.Basic, n: sp.Symbol):
    """Return b_n if a_n = (-1)^n b_n or (-1)^(n+1) b_n with b_n sign-free, else None."""
    for sign_factor in ((-1) ** n, (-1) ** (n + 1)):
        b = sp.simplify(a_n / sign_factor)
        alternating_left = any(isinstance(t, sp.Pow) and t.base == -1 for t in sp.preorder_traversal(b))
        if not alternating_left and b != a_n:
            return b
    return None


def generate(a_n: sp.Basic, n: sp.Symbol, limits: tuple) -> Solution:
    sol = Solution()
    lo, hi = limits
    series = sp.Sum(a_n, (n, lo, hi))
    if hi != sp.oo:
        sol.steps.append(_step(series, "finite sum", CONV, "a finite sum always converges"))
        sol.verdict = CONV
        return sol

    # 0. Alternating series: (-1)^n b_n
    b_n = _split_alternating(a_n, n)
    if b_n is not None:
        Lb = _limit(b_n, n)
        if Lb is not None and Lb != 0:
            sol.steps.append(_step(series, "divergence test", DIV, f"|a_n| → {sp.latex(sp.Abs(Lb))} ≠ 0"))
            sol.verdict = DIV
            return sol
        if Lb == 0:
            d = sp.simplify(b_n.subs(n, n + 1) - b_n)
            if d.is_nonpositive or _limit(d, n) == 0:
                sol.steps.append(_step(series, "alternating series test", CONV,
                                       f"b_n = {sp.latex(b_n)} decreases to 0"))
                sol.verdict = CONV
                return sol

    # 1. Divergence (nth-term) test
    L = _limit(a_n, n)
    if L is not None and L != 0:
        sol.steps.append(_step(series, "divergence test", DIV, f"lim a_n = {sp.latex(L)} ≠ 0"))
        sol.verdict = DIV
        return sol
    sol.steps.append(_step(series, "divergence test", INC,
                           "lim a_n = 0, so the test is inconclusive" if L == 0 else "limit could not be determined"))

    # 2. p-series / geometric recognition
    p = sp.Wild("p", exclude=[n])
    m = sp.simplify(a_n).match(1 / n**p)
    if m is not None and m[p].is_number:
        ok = bool(m[p] > 1)
        sol.steps.append(_step(series, "p-series", CONV if ok else DIV, f"p = {sp.latex(m[p])}, converges iff p > 1"))
        sol.verdict = CONV if ok else DIV
        return sol
    c, r = sp.Wild("c", exclude=[n]), sp.Wild("r", exclude=[n])
    m = a_n.match(c * r**n)
    if m is not None and m[r].is_number:
        ok = bool(abs(m[r]) < 1)
        sol.steps.append(_step(series, "geometric series", CONV if ok else DIV, f"ratio r = {sp.latex(m[r])}, converges iff |r| < 1"))
        sol.verdict = CONV if ok else DIV
        return sol

    # 3. Ratio test
    ratio = _limit(sp.Abs(a_n.subs(n, n + 1) / a_n), n)
    if ratio is not None and ratio != 1:
        ok = bool(ratio < 1)
        sol.steps.append(_step(series, "ratio test", CONV if ok else DIV, f"lim |a_(n+1)/a_n| = {sp.latex(ratio)}"))
        sol.verdict = CONV if ok else DIV
        return sol
    sol.steps.append(_step(series, "ratio test", INC, "limit is 1 or undetermined — inconclusive"))

    # 4. Root test
    root = _limit(sp.Abs(a_n) ** (1 / n), n)
    if root is not None and root != 1:
        ok = bool(root < 1)
        sol.steps.append(_step(series, "root test", CONV if ok else DIV, f"lim |a_n|^(1/n) = {sp.latex(root)}"))
        sol.verdict = CONV if ok else DIV
        return sol

    # 5. Fallback: SymPy's own convergence machinery
    try:
        conv = series.is_convergent()
    except Exception:
        conv = None
    outcome = CONV if conv is True else DIV if conv is False else INC
    sol.steps.append(_step(series, "computer algebra system", outcome, "no hand test was conclusive; SymPy's is_convergent()"))
    sol.verdict = outcome
    return sol
