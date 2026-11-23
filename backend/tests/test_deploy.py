"""The shared-secret gate used by the public deployment."""
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app


def test_api_key_guard(monkeypatch):
    monkeypatch.setattr(settings, "PROOFPAD_API_KEY", "secret")
    with TestClient(app) as c:
        assert c.get("/health").status_code == 200                                      # health is always open
        assert c.get("/discover").status_code == 401                                    # no key → blocked
        assert c.get("/discover", headers={"X-ProofPad-Key": "nope"}).status_code == 401  # wrong key → blocked
        assert c.get("/discover", headers={"X-ProofPad-Key": "secret"}).status_code == 200  # right key → allowed


def test_open_when_no_key_configured(monkeypatch):
    monkeypatch.setattr(settings, "PROOFPAD_API_KEY", "")
    with TestClient(app) as c:
        assert c.get("/discover").status_code == 200   # local dev: unauthenticated
