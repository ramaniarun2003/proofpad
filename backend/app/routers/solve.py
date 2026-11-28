from __future__ import annotations

import json
import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..config import settings
from ..db import SessionRow, get_db
from ..models import SolveRequest, SolveResponse, VerifyRequest, VerifyResponse
from ..services import sandbox

router = APIRouter(tags=["solve"])
logger = logging.getLogger("proofpad.api")


def _get_or_create(db: Session, session_id: str) -> SessionRow:
    row = db.get(SessionRow, session_id)
    if row is None:
        # Allow solving typed problems that never went through /recognize.
        row = SessionRow(id=session_id or str(uuid.uuid4()))
        db.add(row)
    return row


def _compute(fn: str, *args, timeout: float):
    """Run one compute function under its deadline, mapping the outcomes onto HTTP errors."""
    try:
        return sandbox.run(fn, *args, timeout=timeout)
    except sandbox.TooComplex as exc:
        logger.warning("%s refused or timed out: %s", fn, exc)
        raise HTTPException(422, sandbox.TOO_COMPLEX)
    except sandbox.InvalidInput as exc:
        raise HTTPException(422, str(exc))
    except sandbox.WorkerError as exc:   # a generator bug must not take the API down
        logger.error("%s failed: %s", fn, exc)
        raise HTTPException(500, "step generation failed")


def _check(*latex: str, steps: int = 0) -> None:
    try:
        sandbox.check_input(*latex, steps=steps)
    except sandbox.TooComplex as exc:
        logger.warning("input over a limit: %s", exc)
        raise HTTPException(422, sandbox.TOO_COMPLEX)


@router.post("/solve", response_model=SolveResponse)
def solve(req: SolveRequest, db: Session = Depends(get_db)):
    _check(req.latex)
    out = _compute("solve", req.latex, req.variable, req.topic.value, timeout=settings.SOLVE_TIMEOUT_S)

    row = _get_or_create(db, req.session_id)
    row.confirmed_latex = req.latex
    row.topic = out["topic"]
    row.steps_json = json.dumps(out["steps"])
    row.answer_latex = out["answer_latex"]
    row.verified = "true" if out["verified"] else "false"
    row.mismatch_at = "" if out["mismatch_at"] is None else str(out["mismatch_at"])
    row.log("solve", topic=out["topic"], n_steps=len(out["steps"]), verified=out["verified"],
            mismatch_at=out["mismatch_at"])
    db.commit()

    return SolveResponse(session_id=row.id, **out)


@router.post("/verify", response_model=VerifyResponse)
def verify_endpoint(req: VerifyRequest, db: Session = Depends(get_db)):
    logger.info("verify request: %s", req.model_dump(mode="json"))
    problem = req.problem_latex or req.latex
    row = db.get(SessionRow, req.session_id)

    if req.user_steps is not None:
        user_steps = [s for s in req.user_steps if s.strip()]
        _check(problem, *user_steps, steps=len(user_steps))
        out = _compute("verify_student", problem, req.variable, req.topic.value, user_steps,
                       timeout=settings.VERIFY_TIMEOUT_S)
        if row is not None:
            row.log("verify", verdict=out["verdict"], mismatch_at=out["mismatch_at"], student=True)
            db.commit()
    else:
        edited = [[s.rule, s.before_latex, s.after_latex] for s in req.steps]
        _check(problem, *(text for step in edited for text in step[1:]), steps=len(edited))
        out = _compute("verify_edited", problem, req.variable, req.topic.value, edited,
                       timeout=settings.VERIFY_TIMEOUT_S)
        if row is not None:
            row.log("verify", verified=out["verified"], mismatch_at=out["mismatch_at"], edited=True)
            db.commit()
    response = VerifyResponse(**out)
    logger.info("verify response: %s", response.model_dump(mode="json"))
    return response
