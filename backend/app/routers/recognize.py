from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from sqlalchemy.orm import Session

from ..config import settings
from ..db import SessionRow, get_db
from ..models import RecognizeResponse
from ..services import recognition
from ..services.symbolic import guess_topic

router = APIRouter(tags=["recognize"])


@router.post("/recognize", response_model=RecognizeResponse)
async def recognize(
    image: UploadFile = File(...),
    device_id: str = Form(""),
    db: Session = Depends(get_db),
):
    data = await image.read()
    if len(data) > settings.MAX_IMAGE_BYTES:
        raise HTTPException(413, f"image larger than {settings.MAX_IMAGE_BYTES} bytes")
    if not data:
        raise HTTPException(422, "empty image")

    session_id = str(uuid.uuid4())
    path = settings.STORAGE_DIR / f"{session_id}.jpg"
    path.write_bytes(data)

    try:
        result = await recognition.recognize(data, image.content_type or "image/jpeg")
    except recognition.RecognitionError as exc:
        raise HTTPException(503, f"recognition unavailable: {exc}")

    row = SessionRow(id=session_id, device_id=device_id, image_path=str(path),
                     recognized_latex=result.latex, confidence=result.confidence)
    row.log("recognize", confidence=result.confidence, stub=settings.STUB_RECOGNITION)
    db.add(row)
    db.commit()

    return RecognizeResponse(
        session_id=session_id,
        latex=result.latex,
        confidence=result.confidence,
        topic_guess=guess_topic(result.latex),
        low_confidence=result.confidence < settings.LOW_CONFIDENCE,
    )
