"""Pydantic request/response models — the phone ↔ backend contract (docs/api.md)."""
from __future__ import annotations

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class Topic(str, Enum):
    derivative = "derivative"
    integral = "integral"
    series = "series"
    unknown = "unknown"


class RecognizeResponse(BaseModel):
    session_id: str
    latex: str
    confidence: float = Field(ge=0.0, le=1.0)
    topic_guess: Topic
    low_confidence: bool


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
