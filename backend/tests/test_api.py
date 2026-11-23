"""End-to-end API tests in stub-recognition mode (no Mathpix key, no network)."""
import io
import os
from unittest.mock import AsyncMock

os.environ["STUB_RECOGNITION"] = "1"
os.environ["DATABASE_URL"] = "sqlite:///./data/test.db"
os.environ["STORAGE_DIR"] = "./data/test_files"

import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.services import recognition


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "ok"
    assert r.json()["stub_recognition"] is True


def test_health_skips_mathpix_unless_requested(client, monkeypatch):
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    reachable = AsyncMock(return_value=True)
    monkeypatch.setattr(recognition, "reachable", reachable)

    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok", "stub_recognition": False, "mathpix_reachable": None}
    reachable.assert_not_called()

    r = client.get("/health", params={"check_mathpix": True})
    assert r.status_code == 200
    assert r.json()["mathpix_reachable"] is True
    reachable.assert_awaited_once()


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


def test_solve_and_reverify_triple_integral(client):
    problem = {"session_id": "", "latex": r"\int_0^1\int_0^x\int_0^y xyz\,dz\,dy\,dx"}
    r = client.post("/solve", json=problem)
    assert r.status_code == 200, r.text
    sol = r.json()
    assert sol["topic"] == "integral"
    assert sol["answer_latex"] == r"\frac{1}{48}"
    assert sol["verified"] is True

    steps = sol["steps"]
    r = client.post("/verify", json={**problem, "steps": steps})
    assert r.status_code == 200, r.text
    assert r.json()["verified"] is True

    steps[0]["after_latex"] = r"\int_0^1\int_0^x x y^3\,dy\,dx"
    r = client.post("/verify", json={**problem, "steps": steps})
    assert r.status_code == 200, r.text
    assert r.json()["verified"] is False and r.json()["mismatch_at"] == 0


def test_solve_and_reverify_mixed_partial_derivative(client):
    problem = {
        "session_id": "",
        "latex": r"\frac{\partial^2}{\partial y\partial x}(x^2y^3)",
        "variable": "z",
    }
    r = client.post("/solve", json=problem)
    assert r.status_code == 200, r.text
    sol = r.json()
    assert sol["topic"] == "derivative"
    assert sol["answer_latex"] == r"6 x y^{2}"
    assert sol["verified"] is True

    steps = sol["steps"]
    assert steps[0]["note"].startswith("Differentiate with respect to x.")
    assert all(a["after_latex"] == b["before_latex"] for a, b in zip(steps, steps[1:]))
    r = client.post("/verify", json={**problem, "steps": steps})
    assert r.status_code == 200, r.text
    assert r.json()["verified"] is True

    steps[-1]["after_latex"] = r"6 x y"
    r = client.post("/verify", json={**problem, "steps": steps})
    assert r.status_code == 200, r.text
    assert r.json()["verified"] is False and r.json()["mismatch_at"] == len(steps) - 1


@pytest.mark.parametrize("latex", [
    r"\frac{d}{dx} \int",
    r"\frac{\partial^2}{\partial x}(x^2y)",
    r"\frac{\partial}{\partial x}",
])
def test_solve_rejects_unparseable_latex(client, latex):
    r = client.post("/solve", json={"session_id": "", "latex": latex, "topic": "unknown"})
    assert r.status_code == 422


def test_verify_edited_steps_flags_the_bad_one(client):
    steps = [
        {"index": 0, "rule": "power rule", "before_latex": r"\frac{d}{dx} x^{3}", "after_latex": r"3 x^{2}", "note": ""},
        {"index": 1, "rule": "typo", "before_latex": r"3 x^{2}", "after_latex": r"3 x", "note": ""},
    ]
    r = client.post("/verify", json={"session_id": "", "latex": r"\frac{d}{dx} x^{3}", "topic": "derivative", "steps": steps})
    assert r.status_code == 200, r.text
    assert r.json()["verified"] is False and r.json()["mismatch_at"] == 1


def test_verify_edited_integral_transitions(client):
    steps = [
        {"index": 0, "rule": "linearity", "before_latex": r"\int\left(5 x^{3}+2 \cos x\right) d x",
         "after_latex": r"\int 5 x^{3} d x+\int 2 \cos x d x", "note": ""},
        {"index": 1, "rule": "constant multiple",
         "before_latex": r"\int 5 x^{3} d x+\int 2 \cos x d x",
         "after_latex": r"5\int x^{3} d x+2\int \cos x d x", "note": ""},
        {"index": 2, "rule": "table",
         "before_latex": r"5\int x^{3} d x+2\int \cos x d x",
         "after_latex": r"5\cdot\frac{x^{4}}{4}+2\cdot\sin x+C", "note": ""},
        {"index": 3, "rule": "simplify",
         "before_latex": r"5\cdot\frac{x^{4}}{4}+2\cdot\sin x+C",
         "after_latex": r"\frac{5 x^{4}}{4}+2\sin x+C", "note": ""},
    ]
    r = client.post("/verify", json={
        "session_id": "",
        "latex": r"\int\left(5 x^{3}+2 \cos x\right) d x",
        "steps": steps,
    })
    assert r.status_code == 200, r.text
    assert r.json()["verified"] is True, r.text


def test_oversized_image_rejected(client):
    big = io.BytesIO(b"\x00" * (3 * 1024 * 1024))
    r = client.post("/recognize", files={"image": ("page.jpg", big, "image/jpeg")})
    assert r.status_code == 413
