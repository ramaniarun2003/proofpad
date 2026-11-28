"""Identify: split a capture into the problem and the student's own working.

A photo often holds more than the problem — `\\frac{d}{dx}\\sin(3x) = 3\\cos(3x)`, or an integral
with its antiderivative on the next line. Recognition returns that as one string; this module
splits it so the Review screen can show the problem and each step separately.

1. Segments: one per line, then each line split on its top-level claim separators (not inside
   braces or `\\left…\\right`): `=`, `\\Rightarrow`, `\\implies`, `\\therefore`, `\\to`,
   `\\rightarrow`, `\\longrightarrow`, and a bare "so" / "is" inside `\\text{}`. A line that starts
   with a separator continues the previous chain.
2. The problem is the first segment containing an operator we solve (d/dx, ', ∫, Σ, lim, ∂).
3. Every segment after it — same line or later lines — is a student step, in order.

A series is different: the problem is only the `\\sum …` part (bounds added when missing), and the
student's step is a claim, normalized to the token `CONVERGES` or `DIVERGES`.

With no operator anywhere, the whole capture is the problem and there are no steps; if it also
contains `=` signs the result is flagged `split_uncertain` so the app can say so instead of guessing.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field

from .latex_norm import add_sum_bounds

log = logging.getLogger("proofpad.identify")

CONVERGES, DIVERGES = "CONVERGES", "DIVERGES"

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


# Separator commands → the symbol the app names in "I split this at the …".
_SEPARATOR_COMMANDS = {
    "Rightarrow": "⇒", "implies": "⇒", "Longrightarrow": "⇒",
    "therefore": "∴",
    "to": "→", "rightarrow": "→", "longrightarrow": "→",
}
_SEPARATOR_WORDS = ("so", "is")
# `\lim x \rightarrow 0 …` (Mathpix drops the subscript): that arrow belongs to the limit.
_OPEN_LIMIT = re.compile(r"(?:\\lim|\\operatorname\s*\{\s*lim\s*\})\s*_?\s*\{?\s*[a-zA-Z]\s*\}?\s*$")
_TEXT = re.compile(r"\\text\s*\{([^{}]*)\}")
_WORD_WRAPPER = re.compile(r"\\(?:text|textbf|textit|mathrm|mathit|mathbf|operatorname)\s*\{([^{}]*)\}")
_CLAIM_WORD = re.compile(r"^(con|di)v(?:erges?|ergent|ergence)?$")
_NEGATIONS = {"not", "doesn't", "doesnt", "isn't", "isnt", "never"}


@dataclass
class Segment:
    latex: str
    line: int                       # index of the source line
    bbox: list[int] | None = None   # that line's [x0, y0, x1, y1], when known
    sep: str = ""                   # what cut it from the piece before: "=", "⇒", "∴", "→", "so", "is"; "" at a line start


@dataclass
class Identified:
    problem_latex: str
    steps: list[str] = field(default_factory=list)
    segments: list[Segment] = field(default_factory=list)
    split_uncertain: bool = False
    split_at: str = ""              # separator before the first step ("=", "⇒", …, "line"); "" when not split


def has_operator(latex: str) -> bool:
    return bool(_OPERATOR.search(latex))


def split_top_level(latex: str) -> list[tuple[str, str]]:
    """
    Split on claim separators at brace depth 0 and outside `\left…\right`. Returns
    (piece, separator before it); the first piece's separator is "". Empty pieces are kept so a
    caller can tell a leading separator (continuation line) from a normal one.
    """
    parts: list[tuple[str, str]] = []
    cur: list[str] = []
    sep, depth, lr, i = "", 0, 0, 0
    n = len(latex)

    def cut(next_sep: str) -> None:
        nonlocal cur, sep
        parts.append(("".join(cur), sep))
        cur, sep = [], next_sep

    while i < n:
        ch = latex[i]
        if ch == "\\":
            m = re.match(r"\\(left|right)(?![a-zA-Z])", latex[i:])
            if m:
                lr = lr + 1 if m.group(1) == "left" else max(0, lr - 1)
                cur.append(m.group(0))
                i += len(m.group(0))
                continue
            top = depth == 0 and lr == 0
            m = _TEXT.match(latex, i) if top else None
            if m:
                # A bare "so" / "is" among the words is a separator; the other words stay as text.
                chunk: list[str] = []
                for word in m.group(1).split():
                    if word.lower().strip(",.:;") in _SEPARATOR_WORDS:
                        if chunk:
                            cur.append(r"\text{" + " ".join(chunk) + "}")
                        chunk = []
                        cut(word.lower().strip(",.:;"))
                    else:
                        chunk.append(word)
                if chunk:
                    cur.append(r"\text{" + " ".join(chunk) + "}")
                i = m.end()
                continue
            m = re.match(r"\\([a-zA-Z]+)", latex[i:])   # a command: \neq, \leq, \frac …
            if m:
                symbol = _SEPARATOR_COMMANDS.get(m.group(1)) if top else None
                if symbol == "→" and _OPEN_LIMIT.search("".join(cur)):
                    symbol = None
                if symbol:
                    cut(symbol)
                else:
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
            cut("=")
            i += 1
            continue
        cur.append(ch)
        i += 1
    parts.append(("".join(cur), sep))
    return parts


def split_top_level_equals(latex: str) -> list[str]:
    """The pieces of [split_top_level], without their separators."""
    return [piece for piece, _ in split_top_level(latex)]


def normalize_claim(text: str) -> str | None:
    """
    A student's convergence claim → `CONVERGES` / `DIVERGES`, or None when `text` is not one.
    Accepts the tokens themselves, `\text{ converges }`, "conv.", "div", "divergent",
    "it converges", "does not converge" …; case and whitespace do not matter.
    """
    plain = re.sub(r"\\[a-zA-Z]+", " ", _WORD_WRAPPER.sub(r" \1 ", text)).lower()
    words = [w.strip(".,;:!") for w in re.findall(r"[a-z']+\.?", plain)]
    found = [m.group(1) for m in (_CLAIM_WORD.match(w) for w in words) if m]
    if not found:
        return None
    converges = found[0] == "con"
    if _NEGATIONS & set(words):
        converges = not converges
    return CONVERGES if converges else DIVERGES


def _series_split(segments: list[Segment], problem_at: int) -> tuple[str, list[str], str] | None:
    """
    For a `\sum` problem: (problem, [claim], split_at), or None when the student wrote no claim
    (the caller then keeps the ordinary split). The problem is only the `\sum …` part; any other
    `\text{…}` in it is dropped and logged.
    """
    head = segments[problem_at]
    problem, claim, split_at = head.latex, None, ""
    for m in _TEXT.finditer(head.latex):
        found = normalize_claim(m.group(0))
        if found and claim is None:
            claim, split_at = found, m.group(1).strip().lower()   # "∑ … converges", no separator written
        else:
            log.info("identify: dropped text from series problem: %s", m.group(0))
    problem = _clean(_TEXT.sub(" ", problem))
    if claim is None:
        for seg in segments[problem_at + 1:]:
            claim = normalize_claim(seg.latex)
            if claim:
                split_at = seg.sep or "line"
                break
    if claim is None:
        return None
    ignored = [seg.latex for seg in segments[problem_at + 1:] if normalize_claim(seg.latex) is None]
    if ignored:
        log.info("identify: series claim found; ignored other segments: %s", ignored)
    return add_sum_bounds(problem), [claim], split_at


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
        for piece, sep in split_top_level(text):
            piece = _clean(piece)
            if piece:   # a leading "=" leaves an empty first piece: the line continues the chain
                segments.append(Segment(latex=piece, line=line_no, bbox=bbox, sep=sep))

    full = " \\\\ ".join(text for text, _ in rows)
    problem_at = next((i for i, seg in enumerate(segments) if has_operator(seg.latex)), None)
    if problem_at is None:
        # Nothing we know how to solve: keep today's behaviour (everything is the problem).
        return Identified(problem_latex=full, steps=[], segments=segments, split_uncertain="=" in full
                          and len(segments) > 1)
    if re.search(r"\\sum(?![a-zA-Z])", segments[problem_at].latex):
        series = _series_split(segments, problem_at)
        if series is not None:
            problem, steps, split_at = series
            return Identified(problem_latex=problem, steps=steps, segments=segments, split_at=split_at)
    steps = segments[problem_at + 1:]
    return Identified(
        problem_latex=segments[problem_at].latex,
        steps=[seg.latex for seg in steps],
        segments=segments,
        split_at=(steps[0].sep or "line") if steps else "",
    )
