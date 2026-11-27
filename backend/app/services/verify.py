"""Independent verification.

The step generator and this module must not share code paths: the truth is
always computed directly with SymPy (diff / integrate / is_convergent).
Generated solutions compare each step with the direct result; user-edited
solutions compare each transition and then compare the final result.
"""
from __future__ import annotations

import random
import re
from dataclasses import dataclass, field
from itertools import takewhile

import sympy as sp

from ..models import Topic
from .steps.base import Solution, StepRec
from .symbolic import Parsed, parse

N_SAMPLES = 7
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


def _limit_truth(p: Parsed):
    """Independent limit via sympy.limit; 'does not exist' when the sides disagree."""
    inner, var, point, direction = p.inner, p.var, p.point, p.direction
    try:
        if direction == "+-":
            left = sp.limit(inner, var, point, "-")
            right = sp.limit(inner, var, point, "+")
            if left != right:
                return "does not exist"
        val = sp.limit(inner, var, point, direction)
    except Exception:
        return "does not exist"
    return "does not exist" if val == sp.zoo else val


def _values_equal(a, b) -> bool:
    if a == b:
        return True
    if a in (sp.oo, -sp.oo, sp.zoo) or b in (sp.oo, -sp.oo, sp.zoo):
        return a == b
    try:
        return sp.simplify(a - b) == 0
    except Exception:
        return False


def _lhopital_numeric_ok(after, var, point, truth) -> bool:
    """Sample the derivative-quotient near the point and check it approaches truth."""
    inner = after.args[0] if isinstance(after, sp.Limit) else after
    if point == sp.oo:
        pts = [sp.Integer(10) ** k for k in (1, 2, 3)]
    elif point == -sp.oo:
        pts = [-sp.Integer(10) ** k for k in (1, 2, 3)]
    else:
        pts = [point + sp.Rational(1, 10 ** k) for k in (1, 2, 3)]
    last = None
    for pt in pts:
        try:
            last = complex(inner.subs(var, pt).evalf())
        except Exception:
            return True  # cannot sample here — don't fail the step on that basis
    if last is None:
        return True
    if truth in (sp.oo, -sp.oo):
        return abs(last) > 1.0
    try:
        return abs(last - complex(sp.N(truth))) < 1e-2
    except Exception:
        return True


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
    if topic == Topic.limit:
        return _limit_truth(p)
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

    if topic == Topic.limit:
        if truth == "does not exist":
            ok = sol.verdict == "does not exist"
            return VerifyResult(ok, None if ok else max(len(sol.steps) - 1, 0),
                                ["independent limit (sympy.limit): does not exist"])
        for i, st in enumerate(sol.steps):
            after_val = st.after.doit() if isinstance(st.after, sp.Limit) else st.after
            if not _values_equal(after_val, truth):
                return VerifyResult(False, i, [f"step {i} ({st.rule}) does not match the limit {sp.latex(truth)}"])
            if "hopital" in st.rule.lower() and not _lhopital_numeric_ok(st.after, p.var, p.point, truth):
                return VerifyResult(False, i, [f"step {i}: L'Hopital quotient does not approach {sp.latex(truth)} numerically"])
        if sol.answer is not None and not _values_equal(sol.answer, truth):
            return VerifyResult(False, len(sol.steps) - 1, ["final limit differs from the direct result"])
        return VerifyResult(True, None, [f"independent limit (sympy.limit): {sp.latex(truth)}"])

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


def verify_latex_steps(p: Parsed, topic: Topic,
                       step_latex: list[tuple[str, str, str]]) -> VerifyResult:
    """Used by /verify when the client sends (possibly user-edited) LaTeX steps."""
    if not step_latex:
        return VerifyResult(False, 0, ["no steps were provided"])

    def parse_step(text: str, index: int) -> sp.Basic | VerifyResult:
        try:
            # Use the project parser (the same one that powers /solve) so multivariable
            # integrals and partial derivatives round-trip. Neither that parser nor SymPy's
            # binds ``\int f dx + \int g dx`` as separate terms, so split a sum/difference of
            # integrals and parse each term on its own before adding them back.
            if text.count(r"\int") > 1 and re.search(r"[+-]\s*(?:\d+(?:\.\d+)?\s*)?\\int", text):
                terms = re.split(r"(?=[+-]\s*(?:\d+(?:\.\d+)?\s*)?\\int)", text)
                return sp.Add(*[parse(term.lstrip("+ ")).expr for term in terms if term.strip()])
            return parse(text).expr
        except Exception as exc:
            return VerifyResult(False, index, [f"could not parse step {index}: {exc}"])

    if topic == Topic.limit:
        # A student's limit chain: every step must still have the limit's value, whether it is
        # a rewritten \\lim expression or the number itself.
        truth = _limit_truth(p)
        if truth == "does not exist":
            return VerifyResult(False, 0, ["independent limit (sympy.limit): does not exist"])
        for i, (rule, _before, after) in enumerate(step_latex):
            parsed_after = parse_step(after, i)
            if isinstance(parsed_after, VerifyResult):
                return parsed_after
            value = parsed_after.doit() if isinstance(parsed_after, sp.Limit) else parsed_after
            if not _values_equal(value, truth):
                return VerifyResult(False, i, [f"step {i} ({rule}) does not match the limit {sp.latex(truth)}"])
        return VerifyResult(True, None, [f"independent limit (sympy.limit): {sp.latex(truth)}"])

    recs: list[StepRec] = []
    modulo_c = topic == Topic.integral and p.limits is None
    expected_before = p.expr
    for i, (rule, before, after) in enumerate(step_latex):
        parsed_before = parse_step(before, i)
        if isinstance(parsed_before, VerifyResult):
            return parsed_before
        parsed_after = parse_step(after, i)
        if isinstance(parsed_after, VerifyResult):
            return parsed_after

        # Compare each transition modulo an additive constant for indefinite integrals.
        # verify()'s equivalent() now takes the antiderivative vars to differentiate by
        # (Rick's multivariable change) instead of a (symbol, modulo_constant) pair.
        mod = modulo_c or (topic == Topic.integral and rule != "fundamental theorem of calculus")
        anti_vars = (p.var,) if mod else ()
        if not equivalent(parsed_before, expected_before, anti_vars):
            return VerifyResult(False, i, [f"step {i} ({rule}) starts from an unexpected expression"])
        if not equivalent(parsed_before, parsed_after, anti_vars):
            return VerifyResult(False, i, [f"step {i} ({rule}) changes the value incorrectly"])

        recs.append(StepRec(rule=rule, before=parsed_before, after=parsed_after))
        expected_before = parsed_after

    sol = Solution(steps=recs, answer=recs[-1].after)
    return verify(p, topic, sol)


# --- Checking the student's own chain (Identify → Check) ---------------------------------------
#
# The student's steps come from the Identify split of a capture, not from our generator. Each
# step is judged on equivalence to the independently computed result; the rule name is only a
# label found by looking the step up on our own chain. Many ways to solve one problem is fine.

_OPERATORS = (sp.Derivative, sp.Integral, sp.Limit, sp.Sum)


@dataclass
class UserStepResult:
    latex: str
    status: str            # "ok" | "wrong" | "unparseable"
    rule: str = ""         # our rule name, "skipped n steps…", or "unrecognized but correct"
    hint: str = ""         # common-mistake hint for a wrong step, or what is missing from the last one


@dataclass
class UserChainResult:
    verdict: str                       # "verified" | "mismatch" | "incomplete"
    mismatch_at: int | None
    steps: list[UserStepResult] = field(default_factory=list)
    expected_final: str = ""
    user_final_equals_expected: bool = False
    details: list[str] = field(default_factory=list)


def _parse_user_step(text: str) -> sp.Basic:
    """Parse one student step with the project parser (same handling as edited steps)."""
    text = text.strip().lstrip("=").strip()
    if not text:
        raise ValueError("the step is empty")
    if text.count("{") != text.count("}") or text.count("(") != text.count(")"):
        raise ValueError("brackets do not match")   # the LaTeX parser would silently guess
    if text.count(r"\int") > 1 and re.search(r"[+-]\s*(?:\d+(?:\.\d+)?\s*)?\\int", text):
        terms = re.split(r"(?=[+-]\s*(?:\d+(?:\.\d+)?\s*)?\\int)", text)
        return sp.Add(*[parse(term.lstrip("+ ")).expr for term in terms if term.strip()])
    return parse(text).expr


def _rebuild(e: sp.Basic) -> sp.Basic:
    """Re-evaluate arithmetic the LaTeX parser left unevaluated, without running any operator."""
    if not isinstance(e, sp.Basic) or e.is_Atom:
        return e
    try:
        return e.func(*[_rebuild(a) for a in e.args])
    except Exception:
        return e


def _same_form(a: sp.Basic, b: sp.Basic, const_wrt: sp.Symbol | None) -> bool:
    """
    Is `a` the same written state as `b` (not merely equivalent)? Compared after expanding, with
    operators left unevaluated; for antiderivatives a difference that is constant is ignored.
    """
    try:
        diff = sp.expand(_rebuild(a)) - sp.expand(_rebuild(b))
    except Exception:
        return False
    if diff == 0:
        return True
    if const_wrt is not None and not diff.has(*_OPERATORS) and not diff.has(const_wrt):
        return True
    return False


def _constant_symbols(expr: sp.Basic, p: Parsed) -> set[sp.Symbol]:
    """Symbols in a student's step that the problem does not have — i.e. their `+ C`."""
    return set(expr.free_symbols) - set(p.expr.free_symbols) - {p.var}


def _outer_only_derivatives(p: Parsed) -> list[tuple[sp.Basic, sp.Basic]]:
    """
    What a student gets by forgetting the chain rule: f'(u) without the factor u'. Returns
    (wrong derivative, u') for every inside `u` the whole expression depends on only through u.
    """
    f, var = p.inner, p.var
    insides: list[sp.Basic] = []
    for node in sp.preorder_traversal(f):
        if isinstance(node, sp.Function) and node.args and node.args[0] != var and node.args[0].has(var):
            insides.append(node.args[0])
        if isinstance(node, sp.Pow) and node.base != var and node.base.has(var) and not node.base.is_Atom:
            insides.append(node.base)
        if isinstance(node, sp.Pow) and node.base == sp.E and node.exp != var and node.exp.has(var):
            insides.append(node.exp)
    out = []
    for u in insides:
        t = sp.Dummy("t")
        g = f.subs(u, t)
        if g.has(var):
            continue
        du = sp.diff(u, var)
        if du in (0, 1):
            continue
        out.append((sp.diff(g, t).subs(t, u), du))
    return out


def _mistake_hint(a: sp.Basic, expected: sp.Basic, p: Parsed, topic: Topic,
                  anti_vars: tuple[sp.Symbol, ...]) -> str:
    """Name a common mistake that turns `expected` into the student's `a`, if one fits."""
    var = p.var
    try:
        if equivalent(a, -expected, anti_vars):
            return "sign flipped: your step is the negative of what it should be"
    except Exception:
        pass
    if topic == Topic.derivative:
        for wrong, du in _outer_only_derivatives(p):
            try:
                if equivalent(a, wrong):
                    return ("chain rule missing: you differentiated the outside only; "
                            f"multiply by the derivative of the inside, {sp.latex(du)}")
            except Exception:
                continue
    try:
        consts = {c: 0 for c in _constant_symbols(a, p)}
        ratio = sp.simplify(a.doit().subs(consts) / expected.doit())
    except Exception:
        return ""
    if ratio in (sp.nan, sp.zoo, 0) or ratio.has(sp.nan, sp.zoo):
        return ""
    for shifted in (ratio / var, ratio * var):
        shifted = sp.simplify(shifted)
        if shifted != 0 and not shifted.free_symbols:
            return ("wrong power: the exponent is off by one "
                    "(it goes down by 1 when you differentiate, up by 1 when you integrate)")
    if not ratio.free_symbols and ratio != 1:
        return f"off by a constant factor: your step is {sp.latex(ratio)} times what it should be"
    return ""


def verify_user_steps(p: Parsed, topic: Topic, user_steps: list[str]) -> UserChainResult:
    """
    Check a student's chain for the problem `p`.

    The expected result comes from the independent SymPy call (diff / integrate / limit). Every
    step must be equivalent to it — `simplify(a - b) == 0`, then numeric sampling at 7 points —
    which is the same as each step following from the previous correct one. The verdict is
    "mismatch" at the first wrong or unparseable step, "incomplete" when every step is right but
    the last one is not yet the final answer, else "verified".
    """
    from . import steps as step_service  # the generator is only used to *label* correct steps

    if topic == Topic.series:
        raise ValueError("series steps cannot be checked step by step; re-run /solve")
    truth = _truth(p, topic)
    indefinite = topic == Topic.integral and p.limits is None and p.iterated_integral is None
    definite = topic == Topic.integral and p.limits is not None and p.iterated_integral is None
    anti_vars: tuple[sp.Symbol, ...] = (p.var,) if indefinite else ()
    dne = isinstance(truth, str)
    expected_final = truth if dne else sp.latex(truth) + (" + C" if indefinite else "")
    antiderivative = sp.integrate(p.inner, p.var) if definite else None

    # Our own chain, used only to name the rule behind a correct step.
    try:
        _, sol = step_service.solve(p, topic)
        chain = [s for s in sol.steps if s.verdict is None]
    except Exception:
        chain = []
    pos = -1   # index of the last state of our chain the student has reached

    results: list[UserStepResult] = []
    mismatch_at: int | None = None
    last_expr: sp.Basic | None = None
    last_is_antiderivative = False

    for i, text in enumerate(user_steps):
        try:
            expr = _parse_user_step(text)
        except Exception as exc:
            results.append(UserStepResult(text, "unparseable", hint=f"I could not read this step: {str(exc)[:120]}"))
            mismatch_at = i if mismatch_at is None else mismatch_at
            continue

        if dne:
            results.append(UserStepResult(text, "wrong", hint="the one-sided limits differ, so this limit does not exist"))
            mismatch_at = i if mismatch_at is None else mismatch_at
            continue

        ok = False
        is_anti = False
        try:
            if topic == Topic.limit:
                ok = _values_equal(expr.doit() if expr.has(sp.Limit) else expr, truth)
            else:
                ok = equivalent(expr, truth, anti_vars)
                if not ok and definite:   # the antiderivative, before the bounds are applied
                    ok = is_anti = equivalent(expr, antiderivative, (p.var,))
        except Exception:
            ok = False

        if not ok:
            hint = ""
            try:
                hint = _mistake_hint(expr, truth, p, topic, anti_vars)
            except Exception:
                hint = ""
            results.append(UserStepResult(text, "wrong", hint=hint))
            mismatch_at = i if mismatch_at is None else mismatch_at
            continue

        # Label the step: where on our chain does this written form sit?
        rule = "unrecognized but correct"
        note = ""
        for j in range(pos + 1, len(chain)):
            if _same_form(expr, chain[j].after, p.var if (indefinite or definite) else None):
                skipped = j - pos - 1
                if skipped == 0:
                    rule = chain[j].rule
                else:
                    # Short label for the row; which rules were folded in goes in the note.
                    names = ", ".join(s.rule for s in chain[pos + 1:j + 1][:5])
                    rule = f"skipped {skipped} step{'s' if skipped != 1 else ''}"
                    note = f"correct; this line does several rules at once: {names}"
                pos = j
                break
        results.append(UserStepResult(text, "ok", rule=rule, hint=note))
        last_expr, last_is_antiderivative = expr, is_anti

    if mismatch_at is not None:
        bad = results[mismatch_at]
        detail = f"step {mismatch_at}: {bad.hint or ('could not be read' if bad.status == 'unparseable' else 'is not equivalent to the previous line')}"
        return UserChainResult("mismatch", mismatch_at, results, expected_final, False, [detail])

    if not results:
        return UserChainResult("incomplete", None, results, expected_final, False, ["no steps were provided"])

    # Every step is right. Is the last one the final answer?
    missing = ""
    if last_expr is not None:
        if last_expr.has(*_OPERATORS):
            missing = "not finished: this line still has an operator to carry out"
        elif last_is_antiderivative:
            missing = "not finished: apply the bounds to this antiderivative"
        elif indefinite and not _constant_symbols(last_expr, p):
            missing = "missing constant of integration: add + C"
    if missing:
        results[-1].hint = missing
        return UserChainResult("incomplete", None, results, expected_final, False,
                               [f"direct result: {expected_final}", missing])
    return UserChainResult("verified", None, results, expected_final, True, [f"direct result: {expected_final}"])
