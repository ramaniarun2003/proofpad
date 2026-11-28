from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from sqlalchemy.orm import Session

from ..config import settings
from ..db import SessionRow, get_db
from ..models import PageLine, PageRecognizeResponse, ProblemSplit, RecognizeResponse, Segment
from ..services import identify, recognition
from ..services.symbolic import ParseError, parse

router = APIRouter(tags=["recognize"])


@router.post("/recognize")
async def recognize(
    image: UploadFile = File(...),
    device_id: str = Form(""),
    mode: str = Form("formula"),
    db: Session = Depends(get_db),
):
    """`mode=formula` (default): one expression. `mode=page`: a multi-line page with per-line bboxes."""
    data = await image.read()
    if len(data) > settings.MAX_IMAGE_BYTES:
        raise HTTPException(413, f"image larger than {settings.MAX_IMAGE_BYTES} bytes")
    if not data:
        raise HTTPException(422, "empty image")

    session_id = str(uuid.uuid4())
    path = settings.STORAGE_DIR / f"{session_id}.jpg"
    path.write_bytes(data)
    content_type = image.content_type or "image/jpeg"

    if mode == "page":
        try:
            page = await recognition.recognize_page(data, content_type)
        except recognition.RecognitionError as exc:
            raise HTTPException(503, f"recognition unavailable: {exc}")
        row = SessionRow(id=session_id, device_id=device_id, image_path=str(path),
                         recognized_latex=page.raw_text, confidence=0.0)
        row.log("recognize", mode="page", lines=len(page.lines), stub=settings.STUB_RECOGNITION)
        db.add(row)
        db.commit()
        return PageRecognizeResponse(
            session_id=session_id,
            lines=[PageLine(latex=ln.latex, confidence=ln.confidence, bbox=ln.bbox) for ln in page.lines],
            raw_text=page.raw_text,
            image_w=page.image_w,
            image_h=page.image_h,
            **_split_fields(identify.identify([(ln.latex, ln.bbox) for ln in page.lines])),
        )

    try:
        result = await recognition.recognize(data, content_type)
    except recognition.RecognitionError as exc:
        raise HTTPException(503, f"recognition unavailable: {exc}")

    # Identify: prefer Mathpix's per-line reading (with bboxes); fall back to the full string.
    split = identify.identify([(ln.latex, ln.bbox) for ln in result.lines] if result.lines else result.latex)

    row = SessionRow(id=session_id, device_id=device_id, image_path=str(path),
                     recognized_latex=result.latex, confidence=result.confidence)
    found = split.problems or [split]
    problems = [_problem(p) for p in found]
    chosen = problems[split.primary]
    row.log("recognize", confidence=result.confidence, stub=settings.STUB_RECOGNITION,
            steps=len(chosen.steps), split_uncertain=chosen.split_uncertain, split_at=chosen.split_at,
            parse_error=chosen.parse_error, problems=len(problems))
    db.add(row)
    db.commit()

    return RecognizeResponse(
        session_id=session_id,
        latex=result.latex,
        confidence=result.confidence,
        low_confidence=result.confidence < settings.LOW_CONFIDENCE,
        segments=[Segment(latex=s.latex, line=s.line, bbox=s.bbox) for s in split.segments],
        problems=problems,
        problem_index=split.primary,
        **chosen.model_dump(exclude={"bbox"}),
    )


def _problem(split: identify.Identified) -> ProblemSplit:
    """One problem with its topic. A reading the parser rejects keeps its LaTeX and loses its steps."""
    out = ProblemSplit(problem_latex=split.problem_latex, steps=split.steps, split_at=split.split_at,
                       split_uncertain=split.split_uncertain, bbox=split.bbox)
    try:
        out.topic_guess = parse(split.problem_latex).topic
    except ParseError as exc:
        out.parse_error = str(exc)
        out.steps, out.split_at = [], ""
    return out


def _split_fields(split: identify.Identified) -> dict:
    return {
        "problem_latex": split.problem_latex,
        "steps": split.steps,
        "segments": [Segment(latex=s.latex, line=s.line, bbox=s.bbox) for s in split.segments],
        "split_uncertain": split.split_uncertain,
        "split_at": split.split_at,
    }
