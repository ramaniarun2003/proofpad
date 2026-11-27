"""Identify: split a capture into the problem and the student's own working.

A photo often holds more than the problem — `\\frac{d}{dx}\\sin(3x) = 3\\cos(3x)`, or an integral
with its antiderivative on the next line. Recognition returns that as one string; this module
splits it so the Review screen can show the problem and each step separately.

1. Segments: one per line, then each line split on its top-level `=` (not inside braces or
   `\\left…\\right`). A line that starts with `=` continues the previous chain.
2. The problem is the first segment containing an operator we solve (d/dx, ', ∫, Σ, lim, ∂).
3. Every segment after it — same line or later lines — is a student step, in order.

With no operator anywhere, the whole capture is the problem and there are no steps; if it also
contains `=` signs the result is flagged `split_uncertain` so the app can say so instead of guessing.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

# Operators the solver handles. `\partial` also covers \frac{\partial}{\partial x}.
_OPERATOR = re.compile(
    r"\\frac\s*\{\s*d[^{}]*\}\s*\{\s*d\s*[a-zA-Z]\s*\}"   # \frac{d}{dx}, \frac{dy}{dx}
    r"|(?<![a-zA-Z\\])d\s*/\s*d\s*[a-zA-Z](?![a-zA-Z])"    # d/dx
    r"|\\(?:i{0,2}int|oint|sum|lim|partial)(?![a-zA-Z])"     # \int \iint \iiint \sum \lim \partial
    r"|[A-Za-z)}]\s*'|\^\s*\{?\s*\\prime|\\prime"          # f'(x), y', f^{\prime}
)

_ROW_ENV = re.compile(
    r"\\begin\{(aligned|align\*?|array|gathered|gather\*?|split|eqnarray)\}(\{[^{}]*\})?|"
    r"\\end\{(aligned|align\*?|array|gathered|gather\*?|split|eqnarray)\}"
)


@dataclass
class Segment:
    latex: str
    line: int                       # index of the source line
    bbox: list[int] | None = None   # that line's [x0, y0, x1, y1], when known


@dataclass
class Identified:
    problem_latex: str
    steps: list[str] = field(default_factory=list)
    segments: list[Segment] = field(default_factory=list)
    split_uncertain: bool = False


def has_operator(latex: str) -> bool:
    return bool(_OPERATOR.search(latex))


def split_top_level_equals(latex: str) -> list[str]:
    """
    Split on `=` at brace depth 0 and outside `\\left…\\right`. Empty pieces are kept so a caller
    can tell a leading `=` (continuation line) from a normal one.
    """
    parts, cur, depth, lr, i = [], [], 0, 0, 0
    n = len(latex)
    while i < n:
        ch = latex[i]
        if ch == "\\":
            m = re.match(r"\\(left|right)(?![a-zA-Z])", latex[i:])
            if m:
                lr = lr + 1 if m.group(1) == "left" else max(0, lr - 1)
                cur.append(m.group(0))
                i += len(m.group(0))
                continue
            m = re.match(r"\\[a-zA-Z]+", latex[i:])   # a command: \neq, \leq, \frac …
            if m:
                cur.append(m.group(0))
                i += len(m.group(0))
                continue
            cur.append(latex[i:i + 2])                # an escaped character: \{ \} \, \=
            i += 2
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth = max(0, depth - 1)
        if ch == "=" and depth == 0 and lr == 0:
            parts.append("".join(cur))
            cur = []
            i += 1
            continue
        cur.append(ch)
        i += 1
    parts.append("".join(cur))
    return parts


def _clean(latex: str) -> str:
    latex = re.sub(r"(?<!\\)&", " ", latex)
    return re.sub(r"\s+", " ", latex).strip()


def split_lines(latex: str) -> list[str]:
    """Rows of a recognized string: strips aligned/array/gathered wrappers, splits on `\\\\` / newlines."""
    body = _ROW_ENV.sub(" ", latex.replace("$", " "))
    rows, cur, depth, i = [], [], 0, 0
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body):
            if body[i + 1] == "\\" and depth == 0:
                rows.append("".join(cur))
                cur = []
                i += 2
                continue
            cur.append(body[i:i + 2])
            i += 2
            continue
        if ch == "\n" and depth == 0:
            rows.append("".join(cur))
            cur = []
            i += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth = max(0, depth - 1)
        cur.append(ch)
        i += 1
    rows.append("".join(cur))
    return [r for r in (_clean(r) for r in rows) if r]


def identify(lines: list[tuple[str, list[int] | None]] | list[str] | str) -> Identified:
    """
    `lines` is the capture top to bottom: (latex, bbox) per line, bare strings, or one string
    (split into rows first).
    """
    if isinstance(lines, str):
        lines = split_lines(lines)
    rows: list[tuple[str, list[int] | None]] = [
        (ln, None) if isinstance(ln, str) else (ln[0], ln[1]) for ln in lines
    ]
    rows = [(_clean(text), bbox) for text, bbox in rows if _clean(text)]

    segments: list[Segment] = []
    for line_no, (text, bbox) in enumerate(rows):
        for piece in split_top_level_equals(text):
            piece = _clean(piece)
            if piece:   # a leading "=" leaves an empty first piece: the line continues the chain
                segments.append(Segment(latex=piece, line=line_no, bbox=bbox))

    full = " \\\\ ".join(text for text, _ in rows)
    problem_at = next((i for i, seg in enumerate(segments) if has_operator(seg.latex)), None)
    if problem_at is None:
        # Nothing we know how to solve: keep today's behaviour (everything is the problem).
        return Identified(problem_latex=full, steps=[], segments=segments, split_uncertain="=" in full
                          and len(segments) > 1)
    return Identified(
        problem_latex=segments[problem_at].latex,
        steps=[seg.latex for seg in segments[problem_at + 1:]],
        segments=segments,
    )
