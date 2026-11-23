"""Mathpix client. The only place in the codebase that talks to Mathpix.

With no credentials configured (settings.STUB_RECOGNITION), recognize()
returns a fixed LaTeX string so the app can be developed end to end before
the API key exists.
"""
from __future__ import annotations

import base64
from dataclasses import dataclass

import httpx

from ..config import settings


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


def _strip_math_delims(text: str) -> str:
    """Return the math span between the first and last `$` (our math_inline_delimiters)."""
    t = text.strip()
    i = t.find("$")
    j = t.rfind("$")
    if i != -1 and j > i:
        return t[i + 1:j].strip()
    return t


def parse_line_data(data: dict) -> list[PageLine]:
    """
    Keep math-bearing lines from Mathpix `line_data`, top-to-bottom by the `cnt` polygon's min y.
    A line counts if its type is "math", or "text" whose content contains a `$…$` math span.
    """
    lines: list[tuple[int, PageLine]] = []
    for entry in data.get("line_data") or []:
        kind = entry.get("type")
        text = entry.get("text") or ""
        cnt = entry.get("cnt")
        if not cnt:
            continue
        is_math = kind == "math" or (kind == "text" and "$" in text)
        if not is_math:
            continue
        bbox = _bbox_of(cnt)
        conf = entry.get("confidence")
        if conf is None:
            conf = entry.get("confidence_rate", 0.0)
        lines.append((bbox[1], PageLine(latex=_strip_math_delims(text), confidence=float(conf or 0.0), bbox=bbox)))
    lines.sort(key=lambda it: it[0])
    return [line for _, line in lines]


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


async def recognize_page(image_bytes: bytes, content_type: str = "image/jpeg") -> PageResult:
    """
    Recognize a whole page of handwritten lines. Uses Mathpix v3/text with line_data so each
    line comes back separately with a pixel bbox — not one LaTeX blob we'd have to split on `\\`.
    """
    if settings.STUB_RECOGNITION:
        return _stub_page()

    img_w, img_h = _image_size(image_bytes)
    payload = {
        "src": f"data:{content_type};base64," + base64.b64encode(image_bytes).decode(),
        "formats": ["text"],
        "include_line_data": True,
        "rm_spaces": True,
        "math_inline_delimiters": ["$", "$"],
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
    return PageResult(lines=parse_line_data(data), raw_text=data.get("text", ""), image_w=img_w, image_h=img_h)


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
