"""SQLite persistence via SQLAlchemy: one row per problem-solving session."""
from __future__ import annotations

import datetime as dt
import json
import logging
from pathlib import Path

from sqlalchemy import DateTime, Float, String, Text, create_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker

from .config import settings

log = logging.getLogger("proofpad")


def _writable(path: Path) -> bool:
    try:
        path.mkdir(parents=True, exist_ok=True)
        probe = path / ".write_test"
        probe.write_text("ok")
        probe.unlink()
        return True
    except Exception:
        return False


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
        db_path = Path(url[len("sqlite:///"):])
        # Create the DB directory on startup (it's /tmp/proofpad on Render's free tier, where data
        # is EPHEMERAL — fine, the phone caches sessions in Room). On the Starter plan this points at
        # the /data disk instead. _writable() does mkdir(parents=True); only a truly read-only dir
        # falls back below so the service still starts.
        if not _writable(db_path.parent):
            # The configured dir (e.g. a persistent disk at /data) isn't mountable here —
            # fall back to an ephemeral local file so the service still starts.
            fallback = Path("./data/proofpad.db").resolve()
            log.warning(
                "DATABASE_URL dir %s not writable — using ephemeral %s (data will NOT persist across deploys)",
                db_path.parent, fallback,
            )
            _writable(fallback.parent)
            url = f"sqlite:///{fallback}"
            settings.DATABASE_URL = url
    return create_engine(url, connect_args={"check_same_thread": False} if url.startswith("sqlite") else {})


engine = _make_engine()
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def init_db() -> None:
    Base.metadata.create_all(engine)
    # Create FILES_DIR on startup (/tmp/proofpad/files on the free tier — ephemeral; /data/files on
    # the Starter plan). mkdir(parents=True) via _writable; a read-only dir falls back below.
    if not _writable(settings.STORAGE_DIR):
        fallback = Path("./files").resolve()
        log.warning("FILES_DIR %s not writable — using ephemeral %s (uploads will NOT persist)", settings.STORAGE_DIR, fallback)
        settings.STORAGE_DIR = fallback
        settings.STORAGE_DIR.mkdir(parents=True, exist_ok=True)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
