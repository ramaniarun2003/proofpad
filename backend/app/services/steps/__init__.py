"""Dispatch a parsed problem to the right step generator."""
from __future__ import annotations

from ...models import Topic
from ..symbolic import Parsed
from . import derivative, integral, series
from .base import Solution, StepRec

__all__ = ["solve", "Solution", "StepRec"]


def solve(p: Parsed, topic: Topic) -> tuple[Topic, Solution]:
    topic = p.topic if topic in (Topic.unknown, None) else topic
    if topic == Topic.derivative:
        return topic, derivative.generate(p.inner, p.var)
    if topic == Topic.integral:
        return topic, integral.generate(p.inner, p.var, p.limits)
    if topic == Topic.series:
        if p.index is None or p.limits is None:
            raise ValueError("series problems must be written as a \\sum with limits")
        return topic, series.generate(p.inner, p.index, p.limits)
    # Bare expression with no operator: default to differentiation, since that
    # is the most common thing written without an operator in notes.
    return Topic.derivative, derivative.generate(p.inner, p.var)
