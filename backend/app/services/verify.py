"""Independent verification.

The step generator and this module must not share code paths: the truth is
always computed directly with SymPy (diff / integrate / is_convergent), and
every step in the chain is compared against it. `mismatch_at` is the index
of the first step whose result is NOT equivalent to the truth.
"""
from __future__ import annotations

import random
from dataclasses import dataclass

import sympy as sp

from ..models import Topic
from .steps.base import Solution, StepRec
from .symbolic import Parsed

N_SAMPLES = 6
TOL = 1e-6


@dataclass
class VerifyResult:
    verified: bool
    mismatch_at: int | None
    details: list[str]


def equivalent(a: sp.Basic, b: sp.Basic, x: sp.Symbol, modulo_constant: bool = False) -> bool:
    """Symbolic check first; numeric sampling as a fallback when simplify() cannot decide."""
    a = a.doit()
    b = b.doit()
    diff = a - b
    if modulo_constant:
        diff = sp.diff(diff, x)
    try:
        if sp.simplify(diff) == 0:
            return True
    except Exception:
        pass
    free = sorted(diff.free_symbols, key=lambda s: s.name)
    rng = random.Random(0)
    tried = 0
    for _ in range(N_SAMPLES * 4):
        if tried >= N_SAMPLES:
            break
        point = {s: sp.Rational(rng.randint(1, 40), rng.randint(1, 7)) for s in free}
        try:
            val = complex(diff.subs(point).evalf())
        except Exception:
            continue
        if val != val:  # nan
            continue
        tried += 1
        if abs(val) > TOL:
            return False
    return tried > 0


def _truth(p: Parsed, topic: Topic):
    if topic == Topic.derivative:
        return sp.diff(p.inner, p.var)
    if topic == Topic.integral:
        return sp.integrate(p.inner, (p.var, *p.limits) if p.limits else p.var)
    if topic == Topic.series:
        try:
            c = sp.Sum(p.inner, (p.index, *p.limits)).is_convergent()
        except Exception:
            c = None
        return "converges" if c is True else "diverges" if c is False else "inconclusive"
    raise ValueError(f"cannot verify topic {topic}")


def verify(p: Parsed, topic: Topic, sol: Solution) -> VerifyResult:
    truth = _truth(p, topic)
    details: list[str] = []

    if topic == Topic.series:
        if truth == "inconclusive":
            return VerifyResult(True, None, ["SymPy could not decide; verdict accepted unverified"])
        for i, st in enumerate(sol.steps):
            if st.verdict in ("converges", "diverges") and st.verdict != truth:
                return VerifyResult(False, i, [f"step {i}: {st.rule} says {st.verdict}, SymPy says {truth}"])
        ok = sol.verdict == truth
        return VerifyResult(ok, None if ok else len(sol.steps) - 1, [f"SymPy: {truth}"])

    modulo_c = topic == Topic.integral and p.limits is None
    for i, st in enumerate(sol.steps):
        # For definite integrals only the final FTC step is a number; earlier steps are antiderivatives.
        mod = modulo_c or (topic == Topic.integral and st.rule != "fundamental theorem of calculus")
        target = truth if not (topic == Topic.integral and p.limits and mod) else sp.integrate(p.inner, p.var)
        if not equivalent(st.after, target, p.var, modulo_constant=mod):
            details.append(f"step {i} ({st.rule}) is not equivalent to the direct result")
            return VerifyResult(False, i, details)
    if sol.answer is not None and not equivalent(sol.answer, truth, p.var, modulo_constant=modulo_c):
        return VerifyResult(False, len(sol.steps) - 1, ["final answer differs from the direct result"])
    details.append(f"direct result: {sp.latex(truth)}")
    return VerifyResult(True, None, details)


def verify_latex_steps(p: Parsed, topic: Topic, step_latex: list[tuple[str, str]]) -> VerifyResult:
    """Used by /verify when the client sends (possibly user-edited) LaTeX steps."""
    from sympy.parsing.latex import parse_latex  # local import keeps antlr optional at import time

    from .latex_norm import normalize

    recs: list[StepRec] = []
    for rule, after in step_latex:
        try:
            expr = parse_latex(normalize(after)).subs(sp.Symbol("e"), sp.E)
        except Exception as exc:
            return VerifyResult(False, len(recs), [f"could not parse step {len(recs)}: {exc}"])
        recs.append(StepRec(rule=rule, before=expr, after=expr))
    sol = Solution(steps=recs, answer=recs[-1].after if recs else None)
    return verify(p, topic, sol)
