"""SQLite persistence via SQLAlchemy: one row per problem-solving session."""
from __future__ import annotations

import datetime as dt
import json
from pathlib import Path

from sqlalchemy import DateTime, Float, String, Text, create_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker

from .config import settings


class Base(DeclarativeBase):
    pass


class SessionRow(Base):
    __tablename__ = "sessions"

    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    created_at: Mapped[dt.datetime] = mapped_column(DateTime, default=dt.datetime.utcnow)
    device_id: Mapped[str] = mapped_column(String(64), default="")
    image_path: Mapped[str] = mapped_column(String(256), default="")
    recognized_latex: Mapped[str] = mapped_column(Text, default="")
    confidence: Mapped[float] = mapped_column(Float, default=0.0)
    confirmed_latex: Mapped[str] = mapped_column(Text, default="")
    topic: Mapped[str] = mapped_column(String(16), default="unknown")
    steps_json: Mapped[str] = mapped_column(Text, default="[]")
    answer_latex: Mapped[str] = mapped_column(Text, default="")
    verified: Mapped[str] = mapped_column(String(8), default="")  # "", "true", "false"
    mismatch_at: Mapped[str] = mapped_column(String(8), default="")
    log_json: Mapped[str] = mapped_column(Text, default="[]")

    def log(self, event: str, **data) -> None:
        entries = json.loads(self.log_json or "[]")
        entries.append({"t": dt.datetime.utcnow().isoformat(), "event": event, **data})
        self.log_json = json.dumps(entries)


def _make_engine():
    url = settings.DATABASE_URL
    if url.startswith("sqlite:///"):
        Path(url.replace("sqlite:///", "")).parent.mkdir(parents=True, exist_ok=True)
    return create_engine(url, connect_args={"check_same_thread": False} if url.startswith("sqlite") else {})


engine = _make_engine()
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def init_db() -> None:
    Base.metadata.create_all(engine)
    settings.STORAGE_DIR.mkdir(parents=True, exist_ok=True)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
