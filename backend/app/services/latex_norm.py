"""Normalize recognizer LaTeX into something SymPy's parser accepts.

Mathpix (and humans) emit typesetting noise that the parser rejects or
misreads. Add cases here as the evaluation set surfaces them, and pin each
with a test in tests/test_latex_norm.py.
"""
from __future__ import annotations

import re

_REPLACEMENTS: list[tuple[str, str]] = [
    (r"\\left", ""),
    (r"\\right", ""),
    (r"\\,|\\;|\\:|\\!|\\quad|\\qquad|~", " "),
    (r"\\dfrac|\\tfrac", r"\\frac"),
    (r"\\displaystyle", ""),
    (r"\\mathrm\{d\}", "d"),
    (r"\\operatorname\{d\}", "d"),
    (r"\\cdot", " * "),
    (r"\\times", " * "),
    (r"\\ln", r"\\log"),          # SymPy's parser reads \log as natural log
    (r"\\text\{([^}]*)\}", r"\1"),
    (r"\\begin\{aligned\}|\\end\{aligned\}|\\begin\{array\}\{[^}]*\}|\\end\{array\}", ""),
    (r"&|\\\\", " "),
    (r"\$", ""),
]

_MATHPIX_BAD = re.compile(r"\\(label|tag|nonumber)\{[^}]*\}")


def normalize(latex: str) -> str:
    s = latex.strip()
    s = _MATHPIX_BAD.sub("", s)
    for pat, rep in _REPLACEMENTS:
        s = re.sub(pat, rep, s)
    # "e^{x}" → "\exp(x)" is done on the SymPy side (Symbol e → E); nothing here.
    # Collapse whitespace and stray equals-signs at the ends.
    s = re.sub(r"\s+", " ", s).strip()
    s = s.strip("= ")
    return s
