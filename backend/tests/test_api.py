"""End-to-end API tests in stub-recognition mode (no Mathpix key, no network)."""
import io
import os

os.environ["STUB_RECOGNITION"] = "1"
os.environ["DATABASE_URL"] = "sqlite:///./data/test.db"
os.environ["STORAGE_DIR"] = "./data/test_files"

import pytest
from fastapi.testclient import TestClient

from app.main import app


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
    assert r.json()["stub_recognition"] is True


def test_recognize_then_solve_then_export(client):
    fake_jpeg = io.BytesIO(b"\xff\xd8\xff" + b"\x00" * 100)
    r = client.post("/recognize", files={"image": ("page.jpg", fake_jpeg, "image/jpeg")}, data={"device_id": "test"})
    assert r.status_code == 200, r.text
    rec = r.json()
    assert rec["latex"] and rec["topic_guess"] == "derivative"

    r = client.post("/solve", json={"session_id": rec["session_id"], "latex": rec["latex"], "topic": "derivative"})
    assert r.status_code == 200, r.text
    sol = r.json()
    assert sol["verified"] is True and sol["mismatch_at"] is None
    assert len(sol["steps"]) >= 2
    assert sol["steps"][0]["after_latex"] == sol["steps"][1]["before_latex"]  # chain invariant

    r = client.post("/export", json={"session_id": rec["session_id"]})
    assert r.status_code == 200
    assert r.json()["answer_latex"] == sol["answer_latex"]

    r = client.get("/sessions")
    assert any(s["session_id"] == rec["session_id"] for s in r.json())


def test_solve_typed_problem_without_recognize(client):
    r = client.post("/solve", json={"session_id": "", "latex": r"\int_0^1 x^2 dx", "topic": "unknown"})
    assert r.status_code == 200, r.text
    assert r.json()["topic"] == "integral"
    assert r.json()["answer_latex"] == r"\frac{1}{3}"


def test_solve_rejects_unparseable_latex(client):
    r = client.post("/solve", json={"session_id": "", "latex": r"\frac{d}{dx} \int", "topic": "unknown"})
    assert r.status_code == 422


def test_verify_edited_steps_flags_the_bad_one(client):
    steps = [
        {"index": 0, "rule": "power rule", "before_latex": r"\frac{d}{dx} x^{3}", "after_latex": r"3 x^{2}", "note": ""},
        {"index": 1, "rule": "typo", "before_latex": r"3 x^{2}", "after_latex": r"3 x", "note": ""},
    ]
    r = client.post("/verify", json={"session_id": "", "latex": r"\frac{d}{dx} x^{3}", "topic": "derivative", "steps": steps})
    assert r.status_code == 200, r.text
    assert r.json()["verified"] is False and r.json()["mismatch_at"] == 1


def test_oversized_image_rejected(client):
    big = io.BytesIO(b"\x00" * (3 * 1024 * 1024))
    r = client.post("/recognize", files={"image": ("page.jpg", big, "image/jpeg")})
    assert r.status_code == 413
