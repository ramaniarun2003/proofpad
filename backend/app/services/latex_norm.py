"""Normalize recognizer LaTeX into something SymPy's parser accepts.

Mathpix (and humans) emit typesetting noise that the parser rejects or
misreads. Add cases here as the evaluation set surfaces them, and pin each
with a test in tests/test_latex_norm.py.
"""
from __future__ import annotations

import re

_REPLACEMENTS: list[tuple[str, str]] = [
    # Limit arrows first — before \right is stripped below (else \rightarrow → "arrow").
    (r"\\longrightarrow|\\rightarrow", r"\\to"),
    (r"\\operatorname\{lim\}", r"\\lim"),
    # Handwritten limits come back without the subscript (`\\lim x \\to 0 \\frac{…}{…}`): rebuild it.
    (r"\\lim\s+([a-zA-Z])\s*\\to\s*([+-]?\s*(?:\\infty|\\pi|\d+(?:\.\d+)?|[a-zA-Z])(?:\s*\^\s*(?:\{\s*[+-]\s*\}|[+-]))?)",
     r"\\lim_{\1 \\to \2} "),
    (r"\\left", ""),
    (r"\\right", ""),
    (r"\\,|\\;|\\:|\\!|\\quad|\\qquad|~", " "),
    (r"\\dfrac|\\tfrac", r"\\frac"),
    # "d/dx" written with a slash (Mathpix: `d / d x \\sin (3 x)`) → the \\frac form the parser reads.
    (r"(?<![a-zA-Z\\\\])d\s*/\s*d\s*([a-zA-Z])(?![a-zA-Z])", r"\\frac{d}{d\1} "),
    # The same for a partial written with a slash: `\\partial / \\partial x (…)`.
    (r"\\partial\s*/\s*\\partial\s*([a-zA-Z])(?![a-zA-Z])", r"\\frac{\\partial}{\\partial \1} "),
    (r"\\displaystyle", ""),
    (r"\\mathrm\{d\}", "d"),
    (r"\\operatorname\{d\}", "d"),
    (r"\\operatorname\{a(sin|cos|tan|csc|sec|cot)\}", r"\\arc\1"),
    (r"\\cdot", " * "),
    (r"\\times", " * "),
    (r"\\ln", r"\\log"),          # SymPy's parser reads \log as natural log
    (r"\\text\{([^}]*)\}", r"\1"),
    (r"\\begin\{aligned\}|\\end\{aligned\}|\\begin\{array\}\{[^}]*\}|\\end\{array\}", ""),
    (r"&|\\\\", " "),
    (r"\$", ""),
]

_MATHPIX_BAD = re.compile(r"\\(label|tag|nonumber)\{[^}]*\}")


_SUM = re.compile(r"\\sum(?![a-zA-Z])\s*(?:\\limits\s*)?")
_SCRIPT = re.compile(r"\s*([_^])\s*(\{(?:[^{}]|\{[^{}]*\})*\}|\\[a-zA-Z]+|[^\s\\{}])")


def add_sum_bounds(latex: str) -> str:
    r"""
    Give every `\sum` written without bounds the usual ones: `\sum \frac{1}{n^2}` →
    `\sum_{n=1}^{\infty} \frac{1}{n^2}`. The index is the first of n, k, i that appears in the
    string as a variable (default n). A lower bound with no start (`\sum_{k}`) starts at 1, and a
    missing upper bound is ∞; bounds that are already there are left alone.
    """
    letters = set(re.findall(r"[a-zA-Z]", re.sub(r"\\[a-zA-Z]+", " ", latex)))
    index = next((v for v in "nki" if v in letters), "n")
    out, pos = [], 0
    for m in _SUM.finditer(latex):
        out.append(latex[pos:m.start()])
        pos = m.end()
        lower = upper = None
        for _ in range(2):
            script = _SCRIPT.match(latex, pos)
            if not script or (lower if script.group(1) == "_" else upper) is not None:
                break
            body = script.group(2)
            body = body[1:-1].strip() if body.startswith("{") else body
            if script.group(1) == "_":
                lower = body
            else:
                upper = body
            pos = script.end()
        had_both = lower is not None and upper is not None
        if not lower:
            lower = f"{index}=1"
        elif "=" not in lower:
            lower = f"{lower}=1"
        out.append(rf"\sum_{{{lower}}}^{{{upper or chr(92) + 'infty'}}}")
        if not had_both:   # the bounds were added: keep exactly one space before the term
            out.append(" ")
            pos += len(latex[pos:]) - len(latex[pos:].lstrip())
    out.append(latex[pos:])
    return "".join(out)


def normalize(latex: str) -> str:
    s = latex.strip()
    s = _MATHPIX_BAD.sub("", s)
    s = add_sum_bounds(s)
    for pat, rep in _REPLACEMENTS:
        s = re.sub(pat, rep, s)
    # "e^{x}" → "\exp(x)" is done on the SymPy side (Symbol e → E); nothing here.
    # Collapse whitespace and stray equals-signs at the ends.
    s = re.sub(r"\s+", " ", s).strip()
    s = s.strip("= ")
    # A sentence-ending mark after the expression ("= 3\\cos(3x).") is not part of the math; the
    # parser is strict about anything left over at the end, so drop it here.
    s = re.sub(r"(?:\s*[.,;])+$", "", s)
    return s
