from __future__ import annotations

import json
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import SessionRow, get_db
from ..models import SolveRequest, SolveResponse, Topic, VerifyRequest, VerifyResponse
from ..services import steps as step_service
from ..services.symbolic import ParseError, parse
from ..services.verify import verify, verify_latex_steps

router = APIRouter(tags=["solve"])


def _get_or_create(db: Session, session_id: str) -> SessionRow:
    row = db.get(SessionRow, session_id)
    if row is None:
        # Allow solving typed problems that never went through /recognize.
        row = SessionRow(id=session_id or str(uuid.uuid4()))
        db.add(row)
    return row


@router.post("/solve", response_model=SolveResponse)
def solve(req: SolveRequest, db: Session = Depends(get_db)):
    try:
        parsed = parse(req.latex, req.variable)
    except ParseError as exc:
        raise HTTPException(422, str(exc))
    try:
        topic, sol = step_service.solve(parsed, req.topic)
    except ValueError as exc:
        raise HTTPException(422, str(exc))
    except Exception as exc:  # a generator bug must not take the API down
        raise HTTPException(500, f"step generation failed: {exc}")

    vr = verify(parsed, topic, sol)
    steps = [s.to_model(i) for i, s in enumerate(sol.steps)]

    row = _get_or_create(db, req.session_id)
    row.confirmed_latex = req.latex
    row.topic = topic.value
    row.steps_json = json.dumps([s.model_dump() for s in steps])
    row.answer_latex = sol.answer_latex
    row.verified = "true" if vr.verified else "false"
    row.mismatch_at = "" if vr.mismatch_at is None else str(vr.mismatch_at)
    row.log("solve", topic=topic.value, n_steps=len(steps), verified=vr.verified, mismatch_at=vr.mismatch_at)
    db.commit()

    return SolveResponse(session_id=row.id, topic=topic, steps=steps, answer_latex=sol.answer_latex,
                         verified=vr.verified, mismatch_at=vr.mismatch_at, details=vr.details)


@router.post("/verify", response_model=VerifyResponse)
def verify_endpoint(req: VerifyRequest, db: Session = Depends(get_db)):
    try:
        parsed = parse(req.latex, req.variable)
    except ParseError as exc:
        raise HTTPException(422, str(exc))
    topic = parsed.topic if req.topic in (Topic.unknown, None) else req.topic
    if topic == Topic.series:
        raise HTTPException(422, "edited series steps cannot be re-verified; re-run /solve")
    vr = verify_latex_steps(parsed, topic, [(s.rule, s.after_latex) for s in req.steps])
    row = db.get(SessionRow, req.session_id)
    if row is not None:
        row.log("verify", verified=vr.verified, mismatch_at=vr.mismatch_at, edited=True)
        db.commit()
    return VerifyResponse(verified=vr.verified, mismatch_at=vr.mismatch_at, details=vr.details)
