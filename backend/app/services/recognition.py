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
    try:
        async with httpx.AsyncClient(timeout=3) as client:
            r = await client.get("https://api.mathpix.com/")
        return r.status_code < 500
    except httpx.HTTPError:
        return False
