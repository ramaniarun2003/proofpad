from __future__ import annotations

import json
import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import SessionRow, get_db
from ..models import SolveRequest, SolveResponse, Topic, UserStepCheck, VerifyRequest, VerifyResponse
from ..services import steps as step_service
from ..services.symbolic import ParseError, parse
from ..services.verify import verify, verify_latex_steps, verify_user_steps

router = APIRouter(tags=["solve"])
logger = logging.getLogger(__name__)


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
    steps = [s.to_model(i, sol.format_latex) for i, s in enumerate(sol.steps)]

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
    request_data = req.model_dump(mode="json")
    logger.info("verify request: %s", request_data)
    problem = req.problem_latex or req.latex
    try:
        parsed = parse(problem, req.variable)
    except ParseError as exc:
        raise HTTPException(422, str(exc))
    topic = parsed.topic if req.topic in (Topic.unknown, None) else req.topic
    if topic == Topic.series:
        raise HTTPException(422, "edited series steps cannot be re-verified; re-run /solve")
    row = db.get(SessionRow, req.session_id)

    if req.user_steps is not None:
        # The student's own chain: per-step status, rule label and mistake hint.
        if parsed.topic != Topic.unknown:
            topic = parsed.topic       # the operator written in the problem decides, not the chip
        if topic == Topic.unknown:
            topic = Topic.derivative   # same default as /solve for a bare expression
        if topic == Topic.series:
            raise HTTPException(422, "series steps cannot be checked step by step; re-run /solve")
        try:
            res = verify_user_steps(parsed, topic, [s for s in req.user_steps if s.strip()])
        except ValueError as exc:
            raise HTTPException(422, str(exc))
        if row is not None:
            row.log("verify", verdict=res.verdict, mismatch_at=res.mismatch_at, student=True)
            db.commit()
        response = VerifyResponse(
            verified=res.verdict == "verified",
            mismatch_at=res.mismatch_at,
            details=res.details,
            verdict=res.verdict,
            steps=[UserStepCheck(latex=s.latex, status=s.status, rule=s.rule, hint=s.hint) for s in res.steps],
            expected_final=res.expected_final,
            user_final_equals_expected=res.user_final_equals_expected,
        )
        logger.info("verify response: %s", response.model_dump(mode="json"))
        return response

    vr = verify_latex_steps(
        parsed,
        topic,
        [(s.rule, s.before_latex, s.after_latex) for s in req.steps],
    )
    if row is not None:
        row.log("verify", verified=vr.verified, mismatch_at=vr.mismatch_at, edited=True)
        db.commit()
    response = VerifyResponse(verified=vr.verified, mismatch_at=vr.mismatch_at, details=vr.details,
                              verdict="verified" if vr.verified else "mismatch")
    logger.info("verify response: %s", response.model_dump(mode="json"))
    return response
