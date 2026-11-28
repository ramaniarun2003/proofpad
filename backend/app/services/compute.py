"""The CPU-bound work behind /solve, /verify and /recognize, as plain-data functions.

Everything here takes and returns JSON-able values (strings, lists, dicts) so it can run in a
sandbox worker process (services/sandbox.py) and be killed when it runs past its deadline. SymPy
has no timeouts of its own: one unlucky expression can spin for minutes, and some big-integer
steps hold the GIL, so a thread could not be interrupted and would freeze the whole server.

Raise ParseError / ValueError for input the student can fix (the API answers 422 with the text).
"""
from __future__ import annotations

from ..models import Topic
from . import steps as step_service
from .symbolic import ParseError, parse
from .verify import verify, verify_latex_steps, verify_series_claim, verify_user_steps


def solve(latex: str, variable: str, topic: str) -> dict:
    parsed = parse(latex, variable)
    solved_topic, sol = step_service.solve(parsed, Topic(topic))
    vr = verify(parsed, solved_topic, sol)
    return {
        "topic": solved_topic.value,
        "steps": [s.to_model(i, sol.format_latex).model_dump() for i, s in enumerate(sol.steps)],
        "answer_latex": sol.answer_latex,
        "verified": vr.verified,
        "mismatch_at": vr.mismatch_at,
        "details": vr.details,
    }


def verify_student(problem: str, variable: str, topic: str, user_steps: list[str]) -> dict:
    """The student's own chain: per-step status, rule label and mistake hint."""
    parsed = parse(problem, variable)
    chosen = Topic(topic)
    if parsed.topic != Topic.unknown:
        chosen = parsed.topic          # the operator written in the problem decides, not the chip
    if chosen == Topic.unknown:
        chosen = Topic.derivative      # same default as /solve for a bare expression
    solution_steps: list[dict] = []
    if chosen == Topic.series:
        # The step is a claim; our convergence tests come back as the explanation.
        if parsed.index is None or parsed.limits is None:
            raise ValueError("series problems must be written as a \\sum with limits")
        res, sol = verify_series_claim(parsed, user_steps)
        solution_steps = [s.to_model(i, sol.format_latex).model_dump() for i, s in enumerate(sol.steps)]
    else:
        res = verify_user_steps(parsed, chosen, user_steps)
    return {
        "verified": res.verdict == "verified",
        "mismatch_at": res.mismatch_at,
        "details": res.details,
        "verdict": res.verdict,
        "steps": [{"latex": s.latex, "status": s.status, "rule": s.rule, "hint": s.hint} for s in res.steps],
        "expected_final": res.expected_final,
        "user_final_equals_expected": res.user_final_equals_expected,
        "solution_steps": solution_steps,
    }


def verify_edited(problem: str, variable: str, topic: str, steps: list[list[str]]) -> dict:
    """An edited generated chain (older clients): [rule, before, after] per step."""
    parsed = parse(problem, variable)
    chosen = parsed.topic if Topic(topic) == Topic.unknown else Topic(topic)
    if chosen == Topic.series:
        raise ValueError("edited series steps cannot be re-verified; re-run /solve")
    vr = verify_latex_steps(parsed, chosen, [tuple(s) for s in steps])
    return {"verified": vr.verified, "mismatch_at": vr.mismatch_at, "details": vr.details,
            "verdict": "verified" if vr.verified else "mismatch"}


def topics(problems: list[str]) -> list[dict]:
    """For each recognized problem: its topic, or the parse error."""
    out = []
    for latex in problems:
        try:
            out.append({"topic": parse(latex).topic.value, "parse_error": None})
        except ParseError as exc:
            out.append({"topic": Topic.unknown.value, "parse_error": str(exc)})
    return out
