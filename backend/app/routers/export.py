"""Server-side export. Starter returns the session as JSON; the Android track
renders the PDF on-device (PdfDocument). Replace this with a real PDF renderer
(e.g. reportlab + matplotlib mathtext) if server-side export is preferred.
"""
from __future__ import annotations

import json

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from ..db import SessionRow, get_db
from ..models import ExportRequest

router = APIRouter(tags=["export"])


@router.post("/export")
def export(req: ExportRequest, db: Session = Depends(get_db)):
    row = db.get(SessionRow, req.session_id)
    if row is None:
        raise HTTPException(404, "unknown session")
    return {
        "session_id": row.id,
        "created_at": row.created_at.isoformat(),
        "recognized_latex": row.recognized_latex,
        "confirmed_latex": row.confirmed_latex,
        "topic": row.topic,
        "steps": json.loads(row.steps_json or "[]"),
        "answer_latex": row.answer_latex,
        "verified": row.verified == "true",
        "mismatch_at": int(row.mismatch_at) if row.mismatch_at else None,
        "image_available": bool(row.image_path),
    }


@router.get("/sessions")
def list_sessions(limit: int = 50, db: Session = Depends(get_db)):
    """Evaluation helper: dump recent sessions for the accuracy tables."""
    rows = db.query(SessionRow).order_by(SessionRow.created_at.desc()).limit(limit).all()
    return [
        {"session_id": r.id, "created_at": r.created_at.isoformat(), "topic": r.topic,
         "confidence": r.confidence, "verified": r.verified, "mismatch_at": r.mismatch_at,
         "log": json.loads(r.log_json or "[]")}
        for r in rows
    ]
