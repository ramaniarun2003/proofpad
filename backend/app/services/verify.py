"""Independent verification.

The step generator and this module must not share code paths: the truth is
always computed directly with SymPy (diff / integrate / is_convergent), and
every step in the chain is compared against it. `mismatch_at` is the index
of the first step whose result is NOT equivalent to the truth.
"""
from __future__ import annotations

import random
from dataclasses import dataclass
from itertools import takewhile

import sympy as sp

from ..models import Topic
from .steps.base import Solution, StepRec
from .symbolic import Parsed, ParseError, parse

N_SAMPLES = 6
TOL = 1e-6


@dataclass
class VerifyResult:
    verified: bool
    mismatch_at: int | None
    details: list[str]


def equivalent(a: sp.Basic, b: sp.Basic, antiderivative_vars: tuple[sp.Symbol, ...] = ()) -> bool:
    """Symbolic check first; numeric sampling as a fallback when simplify() cannot decide."""
    a = a.doit()
    b = b.doit()
    if a == b and not a.has(sp.nan):
        return True
    diff = a - b
    if antiderivative_vars:
        diff = sp.diff(diff, *antiderivative_vars)
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
        return p.derivative_expression.doit()
    if topic == Topic.integral:
        iterated = p.iterated_integral
        if iterated is not None:
            return sp.integrate(iterated.function, *iterated.limits)
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

    iterated = p.iterated_integral if topic == Topic.integral else None
    final_vars = ()
    if iterated is not None:
        # Only the outer indefinite integrations can be undone by taking
        # derivatives of the final result.
        outer_indefinite = takewhile(lambda limit: len(limit) == 1, reversed(iterated.limits))
        final_vars = tuple(limit[0] for limit in outer_indefinite)
    elif topic == Topic.integral and p.limits is None:
        final_vars = (p.var,)

    for i, st in enumerate(sol.steps):
        target = truth
        step_vars = final_vars
        # The single-variable generator shows antiderivatives before applying bounds.
        if iterated is None and topic == Topic.integral and p.limits and st.rule != "fundamental theorem of calculus":
            target = sp.integrate(p.inner, p.var)
            step_vars = (p.var,)
        if not equivalent(st.after, target, step_vars):
            details.append(f"step {i} ({st.rule}) is not equivalent to the direct result")
            return VerifyResult(False, i, details)
    if sol.answer is not None and not equivalent(sol.answer, truth, final_vars):
        return VerifyResult(False, len(sol.steps) - 1, ["final answer differs from the direct result"])
    details.append(f"direct result: {sp.latex(truth)}")
    return VerifyResult(True, None, details)


def verify_latex_steps(p: Parsed, topic: Topic, step_latex: list[tuple[str, str]]) -> VerifyResult:
    """Used by /verify when the client sends (possibly user-edited) LaTeX steps."""
    recs: list[StepRec] = []
    for rule, after in step_latex:
        try:
            expr = parse(after).expr
        except ParseError as exc:
            return VerifyResult(False, len(recs), [f"could not parse step {len(recs)}: {exc}"])
        recs.append(StepRec(rule=rule, before=expr, after=expr))
    sol = Solution(steps=recs, answer=recs[-1].after if recs else None)
    return verify(p, topic, sol)
