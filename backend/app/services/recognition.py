"""Mathpix client. The only place in the codebase that talks to Mathpix.

With no credentials configured (settings.STUB_RECOGNITION), recognize()
returns a fixed LaTeX string so the app can be developed end to end before
the API key exists.
"""
from __future__ import annotations

import base64
import json
import logging
import re
import time
from dataclasses import dataclass

import httpx

from ..config import settings

log = logging.getLogger("proofpad.recognition")


@dataclass
class RecognitionResult:
    latex: str
    confidence: float
    raw: dict | None = None


@dataclass
class PageLine:
    latex: str
    confidence: float
    bbox: list[int]  # [x0, y0, x1, y1]


@dataclass
class PageResult:
    lines: list[PageLine]
    raw_text: str
    image_w: int
    image_h: int


class RecognitionError(RuntimeError):
    pass


async def recognize(image_bytes: bytes, content_type: str = "image/jpeg") -> RecognitionResult:
    if settings.STUB_RECOGNITION:
        return RecognitionResult(latex=settings.STUB_LATEX, confidence=0.99, raw={"stub": True})

    payload = {
        "src": f"data:{content_type};base64," + base64.b64encode(image_bytes).decode(),
        "formats": ["latex_styled", "text"],
        "data_options": {"include_latex": True},
        "rm_spaces": True,
    }
    headers = {"app_id": settings.MATHPIX_APP_ID, "app_key": settings.MATHPIX_APP_KEY}
    try:
        async with httpx.AsyncClient(timeout=settings.MATHPIX_TIMEOUT_S) as client:
            r = await client.post(settings.MATHPIX_URL, json=payload, headers=headers)
    except httpx.HTTPError as exc:
        raise RecognitionError(f"Mathpix unreachable: {exc}") from exc
    if r.status_code != 200:
        raise RecognitionError(f"Mathpix returned {r.status_code}: {r.text[:200]}")
    data = r.json()
    if "error" in data:
        raise RecognitionError(str(data["error"]))
    latex = data.get("latex_styled") or _strip_delims(data.get("text", ""))
    return RecognitionResult(latex=latex, confidence=float(data.get("confidence", 0.0)), raw=data)


def _image_size(image_bytes: bytes) -> tuple[int, int]:
    """(width, height) of the uploaded image."""
    import io

    from PIL import Image

    with Image.open(io.BytesIO(image_bytes)) as im:
        return im.width, im.height


def _bbox_of(cnt: list) -> list[int]:
    """Axis-aligned [x0, y0, x1, y1] of a Mathpix `cnt` polygon (list of [x, y] points)."""
    xs = [int(p[0]) for p in cnt]
    ys = [int(p[1]) for p in cnt]
    return [min(xs), min(ys), max(xs), max(ys)]


# --- Page mode: turning Mathpix entries into one PageLine per handwritten line -----------------
#
# Mathpix does not reliably give one line_data entry per handwritten line. On a real notebook
# photo it often returns the whole page as ONE math entry (`\begin{array}{l} a \\ b \\ … \end{array}`),
# or one entry holding several `$…$` spans. So every entry is expanded into rows here.

_SKIP_TYPES = {"diagram", "table", "chart", "image", "figure"}
_ROW_ENVS = ("array", "gathered", "aligned", "align", "align*", "gather", "gather*", "split", "eqnarray", "cases")
_SPAN_RE = re.compile(r"\$\$(.+?)\$\$|\$(.+?)\$|\\\((.+?)\\\)|\\\[(.+?)\\\]", re.S)


def _math_spans(text: str) -> list[str]:
    """Every `$…$`, `$$…$$`, `\\(…\\)` or `\\[…\\]` span in `text`, in order, delimiters removed."""
    return [next(g for g in m.groups() if g is not None).strip() for m in _SPAN_RE.finditer(text)]


def _strip_row_env(latex: str) -> tuple[str, bool]:
    """If `latex` is wholly one row environment, return (its body, True); else (latex, False)."""
    t = latex.strip()
    m = re.match(r"\\begin\{([a-z*]+)\}", t)
    if not m or m.group(1) not in _ROW_ENVS:
        return t, False
    close = "\\end{" + m.group(1) + "}"
    if not t.endswith(close):
        return t, False
    body = t[m.end():len(t) - len(close)]
    if m.group(1) == "array":  # drop the column spec: \begin{array}{l}
        spec = re.match(r"\s*\{[^{}]*\}", body)
        if spec:
            body = body[spec.end():]
    return body, True


def _split_rows(body: str) -> list[str]:
    """Split on `\\\\` that is at brace depth 0 and not inside a nested \\begin…\\end."""
    rows, cur, depth, env, i = [], [], 0, 0, 0
    while i < len(body):
        if body.startswith("\\begin{", i):
            env += 1
        elif body.startswith("\\end{", i):
            env = max(0, env - 1)
        ch = body[i]
        if ch == "\\" and i + 1 < len(body):
            if body[i + 1] == "\\" and depth == 0 and env == 0:
                rows.append("".join(cur))
                cur = []
                i += 2
                continue
            cur.append(body[i:i + 2])  # an escaped char (\{, \}, \,) never changes depth
            i += 2
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth = max(0, depth - 1)
        cur.append(ch)
        i += 1
    rows.append("".join(cur))
    return rows


def _clean_row(row: str) -> str:
    """One row of an environment as standalone LaTeX: no alignment `&`, no trailing rule/space."""
    if "\\begin{" not in row:  # a nested matrix keeps its own column separators
        row = re.sub(r"(?<!\\)&", " ", row)
    row = re.sub(r"\\hline", " ", row)
    return re.sub(r"\s+", " ", row).strip()


def _rows_of(text: str) -> list[str]:
    """The separate handwritten lines inside one Mathpix entry's text (possibly just one)."""
    spans = _math_spans(text)
    if not spans:
        spans = [text.strip()]
    rows: list[str] = []
    for span in spans:
        body, was_env = _strip_row_env(span)
        parts = _split_rows(body) if was_env else [body]
        rows.extend(r for r in (_clean_row(p) for p in parts) if r)
    return rows


def _split_bbox(bbox: list[int], n: int) -> list[list[int]]:
    """Divide a bbox into `n` equal horizontal bands, top to bottom (for per-row thumbnails)."""
    x0, y0, x1, y1 = bbox
    h = (y1 - y0) / n
    return [[x0, round(y0 + i * h), x1, round(y0 + (i + 1) * h)] for i in range(n)]


def _merge_same_row(lines: list[PageLine]) -> list[PageLine]:
    """
    Join fragments Mathpix split off the same handwritten line (e.g. `\\frac{d}{dx}` and
    `\\sin(3x)` as two word entries): consecutive boxes that overlap vertically by at least half
    the shorter one's height and sit side by side become one line, left to right.
    """
    out: list[PageLine] = []
    for ln in lines:
        if out:
            prev = out[-1]
            overlap = min(prev.bbox[3], ln.bbox[3]) - max(prev.bbox[1], ln.bbox[1])
            shorter = min(prev.bbox[3] - prev.bbox[1], ln.bbox[3] - ln.bbox[1])
            x_overlap = min(prev.bbox[2], ln.bbox[2]) - max(prev.bbox[0], ln.bbox[0])
            narrower = min(prev.bbox[2] - prev.bbox[0], ln.bbox[2] - ln.bbox[0])
            if shorter > 0 and overlap >= 0.5 * shorter and x_overlap < 0.5 * max(narrower, 1):
                left, right = (prev, ln) if prev.bbox[0] <= ln.bbox[0] else (ln, prev)
                out[-1] = PageLine(
                    latex=f"{left.latex} {right.latex}",
                    confidence=min(prev.confidence, ln.confidence),
                    bbox=[min(prev.bbox[0], ln.bbox[0]), min(prev.bbox[1], ln.bbox[1]),
                          max(prev.bbox[2], ln.bbox[2]), max(prev.bbox[3], ln.bbox[3])],
                )
                continue
        out.append(ln)
    return out


def parse_entries(entries: list[dict] | None) -> list[PageLine]:
    """
    Mathpix `line_data` / `word_data` entries → one PageLine per handwritten line, top to bottom.

    - "math" entries, and "text" entries holding a math span, are kept; diagrams, tables,
      headers and empty entries are dropped.
    - An entry whose text is a row environment (array / gathered / aligned …) is split on `\\\\`;
      one holding several `$…$` or `\\(…\\)` spans yields one line per span. The entry's bbox is
      divided vertically between its rows.
    - Fragments of the same handwritten line are merged, and the result is ordered by min y.
    """
    lines: list[PageLine] = []
    for entry in entries or []:
        kind = entry.get("type")
        text = (entry.get("text") or "").strip()
        cnt = entry.get("cnt")
        if not cnt or not text or kind in _SKIP_TYPES:
            continue
        if not (kind == "math" or (kind == "text" and _math_spans(text))):
            continue
        rows = _rows_of(text) if kind == "math" else [r for s in _math_spans(text) for r in _rows_of(f"${s}$")]
        # A lone character ("B", "1", ">") is a margin mark or stray stroke, never a line of work.
        rows = [r for r in rows if len(r.replace(" ", "")) > 1]
        if not rows:
            continue
        conf = entry.get("confidence")
        if conf is None:
            conf = entry.get("confidence_rate", 0.0)
        conf = min(1.0, max(0.0, float(conf or 0.0)))
        for row, bbox in zip(rows, _split_bbox(_bbox_of(cnt), len(rows))):
            lines.append(PageLine(latex=row, confidence=conf, bbox=bbox))
    lines.sort(key=lambda ln: ln.bbox[1])
    return _merge_same_row(lines)


def parse_line_data(data: dict) -> list[PageLine]:
    """Lines from a Mathpix response's `line_data` (see [parse_entries])."""
    return parse_entries(data.get("line_data"))


def _math_entry_count(entries: list[dict] | None) -> int:
    return sum(1 for e in entries or [] if e.get("type") == "math" and (e.get("text") or "").strip())


def _stub_page() -> PageResult:
    """Five stacked lines in a synthetic 1000×1500 image, so the wizard works without Mathpix."""
    exprs = [
        r"\int x^2 \, dx",
        r"\frac{d}{dx} \sin(3x)",
        r"\frac{e^x}{x+1}",
        r"\sum \frac{1}{n^2}",
        r"\lim_{x \to 0} \ln(1+x)",
    ]
    w, h = 1000, 1500
    lines = []
    for i, e in enumerate(exprs):
        y0 = 120 + i * 260
        lines.append(PageLine(latex=e, confidence=0.95, bbox=[90, y0, 910, y0 + 180]))
    return PageResult(lines=lines, raw_text="\n".join(exprs), image_w=w, image_h=h)


async def _mathpix_page(image_bytes: bytes, content_type: str, **extra) -> dict:
    payload = {
        "src": f"data:{content_type};base64," + base64.b64encode(image_bytes).decode(),
        "formats": ["text"],
        "rm_spaces": True,
        "math_inline_delimiters": ["$", "$"],
        **extra,
    }
    headers = {"app_id": settings.MATHPIX_APP_ID, "app_key": settings.MATHPIX_APP_KEY}
    try:
        async with httpx.AsyncClient(timeout=settings.MATHPIX_TIMEOUT_S) as client:
            r = await client.post(settings.MATHPIX_URL, json=payload, headers=headers)
    except httpx.HTTPError as exc:
        raise RecognitionError(f"Mathpix unreachable: {exc}") from exc
    if r.status_code != 200:
        raise RecognitionError(f"Mathpix returned {r.status_code}: {r.text[:200]}")
    data = r.json()
    if "error" in data:
        raise RecognitionError(str(data["error"]))
    return data


async def recognize_page(image_bytes: bytes, content_type: str = "image/jpeg") -> PageResult:
    """
    Recognize a whole page of handwritten lines with Mathpix v3/text.

    First ask for line_data. When Mathpix collapses the page into a single math block (what it
    does with tightly spaced lines on ruled paper — it even merges neighbouring lines inside the
    block), ask again for word_data, whose boxes follow the individual expressions, and keep
    whichever reading found more lines.
    """
    if settings.STUB_RECOGNITION:
        return _stub_page()

    img_w, img_h = _image_size(image_bytes)
    ts = time.strftime("%Y%m%d_%H%M%S") + f"_{int(time.time() * 1000) % 1000:03d}"

    data = await _mathpix_page(image_bytes, content_type, include_line_data=True)
    _save_page_debug(ts, "page_mathpix", data, "line_data", image_bytes)
    lines = parse_line_data(data)
    source = "line_data"

    if _math_entry_count(data.get("line_data")) <= 1 or len(lines) < 3:
        try:
            words = await _mathpix_page(image_bytes, content_type, include_word_data=True)
        except RecognitionError as exc:
            log.warning("page mathpix: word_data fallback failed: %s", exc)
        else:
            _save_page_debug(ts, "page_mathpix_words", words, "word_data")
            word_lines = parse_entries(words.get("word_data"))
            if len(word_lines) > len(lines):
                lines, source = word_lines, "word_data"

    log.info("page mathpix: lines=%d (source=%s)", len(lines), source)
    for i, ln in enumerate(lines):
        log.info("  line %d: y=%d..%d %r", i + 1, ln.bbox[1], ln.bbox[3], ln.latex[:60])
    return PageResult(lines=lines, raw_text=data.get("text", ""), image_w=img_w, image_h=img_h)


def _save_page_debug(ts: str, name: str, data: dict, key: str, image_bytes: bytes | None = None) -> None:
    """
    Always keep the raw Mathpix page response under files/debug/<ts>_<name>.json (plus the image
    that produced it) and log what came back, so a wrong line count can be traced to Mathpix's
    segmentation vs our splitting. Never fails the request.
    """
    entries = data.get(key) or []
    log.info("page mathpix: %d %s entries", len(entries), key)
    for i, e in enumerate(entries):
        log.info("  [%d] type=%s text=%r", i, e.get("type"), (e.get("text") or "")[:60])
    try:
        debug_dir = settings.STORAGE_DIR / "debug"
        debug_dir.mkdir(parents=True, exist_ok=True)
        (debug_dir / f"{ts}_{name}.json").write_text(json.dumps(data, indent=2))
        if image_bytes is not None:
            (debug_dir / f"{ts}_page_upload.jpg").write_bytes(image_bytes)
    except OSError as exc:
        log.warning("could not save page debug files: %s", exc)


def _strip_delims(text: str) -> str:
    """Mathpix 'text' wraps math in \\( … \\) or $$; keep the first math span."""
    for open_, close in (("\\(", "\\)"), ("\\[", "\\]"), ("$$", "$$"), ("$", "$")):
        if open_ in text:
            start = text.index(open_) + len(open_)
            end = text.find(close, start)
            if end > start:
                return text[start:end].strip()
    return text.strip()


async def reachable() -> bool:
    if settings.STUB_RECOGNITION:
        return False
    # Probe the real endpoint (settings.MATHPIX_URL = v3/text), not the bare api.mathpix.com root,
    # which has no handler and hangs. A GET there answers in ~60ms with 401/405 — proof it's reachable.
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            r = await client.get(settings.MATHPIX_URL)
        return r.status_code < 500
    except httpx.HTTPError:
        return False
