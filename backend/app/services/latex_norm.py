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


# --- Implicit multiplication -------------------------------------------------------------------
#
# SymPy's LaTeX parser reads a letter followed by "(" as a function call: `x(2\sin x + x\cos x)`
# becomes Function('x')(…), not x·(…). The rewrite below makes the product explicit first.

_FUNCTION_LETTERS = "fgh"
# An operator applied to the parenthesis that follows: d/dx(…), ∂/∂x(…). (\frac{d}{dx}(…) ends
# in "}", which is never a multiplication point.)
_SLASH_OPERATOR = re.compile(r"(?<![a-zA-Z\\])d\s*/\s*d\s*[a-zA-Z](?![a-zA-Z])|\\partial\s*/\s*\\partial\s*[a-zA-Z](?![a-zA-Z])")
_TOKEN = re.compile(r"\\left\s*\(|\\right\s*\)|\\[a-zA-Z]+|\\.|[a-zA-Z]|\d+(?:\.\d+)?|\s+|.", re.S)
_DECLARED = r"(?<![a-zA-Z\\])([%s])\s*(?:'|\^\s*\{?\s*\\prime|(?:\\left\s*)?\(\s*[a-zA-Z]\s*(?:\\right\s*)?\))"


def declared_functions(latex: str) -> frozenset[str]:
    """
    The letters among f, g, h that the text uses as functions: written with a prime (`f'`) or
    applied to a single variable (`g(x)`). Elsewhere a letter before "(" is a variable.
    """
    return frozenset(re.findall(_DECLARED % _FUNCTION_LETTERS, latex))


def implicit_multiplication(latex: str, functions: frozenset[str] | set[str] | None = None,
                            times=r"\cdot ") -> str:
    r"""
    Write out the products the parser would misread, as `\cdot`:

    - a variable letter before `(` or `\left(`:   `x(x+1)`      → `x \cdot (x+1)`
    - `)` before a letter or `(`:                 `(x+1)(x-1)`  → `(x+1) \cdot (x-1)`
    - a digit before `(`:                         `2(x+1)`      → `2 \cdot (x+1)`

    Left alone: a command's own argument (`\sin(x)`, `\sin^2(x)`, `\log_2(x)`), a declared function
    (`f(x)`, see [declared_functions]; pass `functions` to add the problem's), a primed name
    (`y'(x)`), an operator applied to the parenthesis (`d/dx(x^2)`, `\frac{d}{dx}(x^2)`), and a
    differential after a parenthesis (`\int (x+1) dx`).
    """
    funcs = declared_functions(latex) | set(functions or ())
    protected = [m.span() for m in _SLASH_OPERATOR.finditer(latex)]

    # Token kinds: var (a variable letter), func (declared function letter), num, open, close,
    # cmd, op (a slash operator), sub / sup markers, "{" / "}", other. Whitespace is skipped.
    tokens: list[tuple[str, str]] = []
    pos = 0
    while pos < len(latex):
        span = next((sp_ for sp_ in protected if sp_[0] == pos), None)
        if span:
            tokens.append(("op", latex[span[0]:span[1]]))
            pos = span[1]
            continue
        m = _TOKEN.match(latex, pos)
        text = m.group(0)
        pos = m.end()
        if text.isspace():
            kind = "space"
        elif text.startswith("\\left"):
            kind = "open"
        elif text.startswith("\\right") and text.endswith(")"):
            kind = "close"
        elif text.startswith("\\"):
            kind = "cmd"
        elif text.isalpha():
            kind = "func" if text in funcs else "var"
        elif text[0].isdigit():
            kind = "num"
        elif text == "(":
            kind = "open"
        elif text == ")":
            kind = "close"
        else:
            kind = text if text in "{}_^'" else "other"
        tokens.append((kind, text))

    out: list[str] = []
    prev = "start"            # what the last complete term was: var, num, close, script, or anything else
    script_base = ""          # kind of the token a pending _ / ^ is attached to
    groups: list[str] = []    # for each open "{": "script:<base kind>" or "group"
    pending_script = ""       # set right after _ or ^: the base kind
    significant = [i for i, (k, _) in enumerate(tokens) if k != "space"]
    following = {i: (tokens[significant[n + 1]] if n + 1 < len(significant) else ("end", ""))
                 for n, i in enumerate(significant)}

    for i, (kind, text) in enumerate(tokens):
        if kind == "space":
            out.append(text)
            continue
        if kind == "open" and prev in ("var", "num", "close", "script"):
            out.append(times if isinstance(times, str) else times(prev))
        elif kind in ("var", "func") and prev == "close":
            # `(x+1) dx` after an integrand is a differential, not a product.
            nxt = following[i][0]
            if not (text == "d" and nxt in ("var", "cmd")):
                out.append(times if isinstance(times, str) else times(prev))
        out.append(text)

        if kind in ("_", "^"):
            pending_script = prev if prev in ("var", "num", "close", "script") else "cmd"
            # a script on a command (\sin^2, \log_2, \sum_{…}) keeps the command's argument
            if prev_raw(tokens, i) == "cmd":
                pending_script = "cmd"
            prev = "other"
            continue
        if kind == "{":
            groups.append(f"script:{pending_script}" if pending_script else "group")
            pending_script = ""
            prev = "other"
            continue
        if kind == "}":
            opened = groups.pop() if groups else "group"
            prev = "script" if opened.startswith("script:") and opened != "script:cmd" else "other"
            continue
        if pending_script:    # a one-token script: x^2, x_1, \sin^2
            prev = "script" if pending_script != "cmd" else "other"
            pending_script = ""
            continue
        prev = kind if kind in ("var", "num", "close") else "other"
    return "".join(out)


def prev_raw(tokens: list[tuple[str, str]], i: int) -> str:
    """Kind of the nearest non-space token before position i."""
    for kind, _ in reversed(tokens[:i]):
        if kind != "space":
            return kind
    return "start"


_MARK = "\x00"


def _group_implicit_products(s: str, functions: frozenset[str] | set[str] | None) -> str:
    """
    [implicit_multiplication] for the parser. An explicit `*` would end the operand of a
    derivative (`\\frac{d}{dx} x * (x+1)` differentiates only x), so the product stays a
    juxtaposition the parser cannot read as a call: `x(x+1)` → `x{(x+1)}`. After a `)` or a
    number the parser already multiplies, so those are left exactly as written.
    """
    s = implicit_multiplication(s, functions, times=lambda prev: _MARK if prev in ("var", "script") else "")
    while _MARK in s:
        at = s.rindex(_MARK)
        rest = s[at + 1:]
        if rest.startswith("("):
            depth = 0
            for i, ch in enumerate(rest):
                depth += (ch == "(") - (ch == ")")
                if depth == 0:
                    rest = "{" + rest[:i + 1] + "}" + rest[i + 1:]
                    break
        s = s[:at] + rest
    return s


def normalize(latex: str, functions: frozenset[str] | set[str] | None = None) -> str:
    s = latex.strip()
    s = _MATHPIX_BAD.sub("", s)
    s = add_sum_bounds(s)
    for pat, rep in _REPLACEMENTS:
        s = re.sub(pat, rep, s)
    s = _group_implicit_products(s, functions)
    # "e^{x}" → "\exp(x)" is done on the SymPy side (Symbol e → E); nothing here.
    # Collapse whitespace and stray equals-signs at the ends.
    s = re.sub(r"\s+", " ", s).strip()
    s = s.strip("= ")
    # A sentence-ending mark after the expression ("= 3\\cos(3x).") is not part of the math; the
    # parser is strict about anything left over at the end, so drop it here.
    s = re.sub(r"(?:\s*[.,;])+$", "", s)
    return s
