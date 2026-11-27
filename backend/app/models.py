"""Pydantic request/response models — the phone ↔ backend contract (docs/api.md)."""
from __future__ import annotations

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class Topic(str, Enum):
    derivative = "derivative"
    integral = "integral"
    series = "series"
    limit = "limit"
    unknown = "unknown"


class Segment(BaseModel):
    """One piece of the capture after the Identify split (a line, cut at its top-level `=`)."""
    latex: str
    line: int
    bbox: Optional[list[int]] = None  # the source line's [x0, y0, x1, y1], when Mathpix gave one


class RecognizeResponse(BaseModel):
    session_id: str
    latex: str                       # the full recognized string (problem and working together)
    confidence: float = Field(ge=0.0, le=1.0)
    topic_guess: Topic
    low_confidence: bool
    # Identify split (services/identify.py): the problem, then the student's own steps in order.
    problem_latex: str = ""
    steps: list[str] = []
    segments: list[Segment] = []
    split_uncertain: bool = False    # "=" present but no operator found: the app should ask


class PageLine(BaseModel):
    latex: str
    confidence: float = Field(ge=0.0, le=1.0)
    bbox: list[int]  # [x0, y0, x1, y1] in pixels of the uploaded image


class PageRecognizeResponse(BaseModel):
    """mode=page: one entry per handwritten line, top-to-bottom, with pixel bboxes."""
    session_id: str
    lines: list[PageLine]
    raw_text: str
    image_w: int
    image_h: int
    problem_latex: str = ""
    steps: list[str] = []
    segments: list[Segment] = []
    split_uncertain: bool = False


class Step(BaseModel):
    index: int
    rule: str
    before_latex: str
    after_latex: str
    note: str = ""


class SolveRequest(BaseModel):
    session_id: str
    latex: str
    topic: Topic = Topic.unknown
    variable: str = "x"


class SolveResponse(BaseModel):
    session_id: str
    topic: Topic
    steps: list[Step]
    answer_latex: str
    verified: bool
    mismatch_at: Optional[int] = None
    details: list[str] = []


class VerifyRequest(BaseModel):
    session_id: str
    latex: str
    topic: Topic = Topic.unknown
    variable: str = "x"
    steps: list[Step]


class VerifyResponse(BaseModel):
    verified: bool
    mismatch_at: Optional[int] = None
    details: list[str] = []


class ExportRequest(BaseModel):
    session_id: str


class HealthResponse(BaseModel):
    status: str
    stub_recognition: bool
    mathpix_reachable: Optional[bool] = None


class ErrorResponse(BaseModel):
    error: str
    detail: str = ""
