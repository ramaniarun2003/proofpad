"""Runtime configuration, read from environment / .env."""
from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent.parent / ".env")


class Settings:
    MATHPIX_APP_ID: str = os.getenv("MATHPIX_APP_ID", "")
    MATHPIX_APP_KEY: str = os.getenv("MATHPIX_APP_KEY", "")
    MATHPIX_URL: str = os.getenv("MATHPIX_URL", "https://api.mathpix.com/v3/text")
    MATHPIX_TIMEOUT_S: float = float(os.getenv("MATHPIX_TIMEOUT_S", "10"))

    # When no Mathpix credentials are set, /recognize returns a fixed LaTeX
    # string so the Android track can develop against the API before the key exists.
    STUB_RECOGNITION: bool = os.getenv("STUB_RECOGNITION", "").lower() in ("1", "true") or not (
        os.getenv("MATHPIX_APP_ID") and os.getenv("MATHPIX_APP_KEY")
    )
    STUB_LATEX: str = os.getenv("STUB_LATEX", r"\frac{d}{dx} x^{2} \sin(x)")

    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./data/solver.db")
    STORAGE_DIR: Path = Path(os.getenv("STORAGE_DIR", "./data/files"))
    MAX_IMAGE_BYTES: int = int(os.getenv("MAX_IMAGE_BYTES", str(2 * 1024 * 1024)))
    LOW_CONFIDENCE: float = float(os.getenv("LOW_CONFIDENCE", "0.6"))


settings = Settings()
