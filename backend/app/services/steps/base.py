"""Shared step record used by every generator.

A StepRec holds SymPy expressions; the API layer converts it to the
JSON `Step` model (LaTeX strings). Every generator produces a *linear chain*:
`steps[i].after == steps[i+1].before`, and every `after` is mathematically
equal to the original problem (up to an integration constant for
antiderivatives). That invariant is what lets the verifier check each step
independently instead of only the final answer.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import sympy as sp

from ...models import Step


@dataclass
class StepRec:
    rule: str
    before: sp.Basic
    after: sp.Basic
    note: str = ""
    # For series, the chain carries a verdict string instead of an expression.
    verdict: str | None = None

    def to_model(self, index: int) -> Step:
        return Step(
            index=index,
            rule=self.rule,
            before_latex=sp.latex(self.before),
            after_latex=self.verdict if self.verdict is not None else sp.latex(self.after),
            note=self.note,
        )


@dataclass
class Solution:
    steps: list[StepRec] = field(default_factory=list)
    answer: sp.Basic | None = None
    verdict: str | None = None       # series: "converges" | "diverges" | "inconclusive"

    @property
    def answer_latex(self) -> str:
        if self.verdict is not None:
            return self.verdict
        return sp.latex(self.answer) if self.answer is not None else ""
