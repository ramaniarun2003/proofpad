"""Hard limits: no input may hang the service. Over-size input is refused, slow work is killed."""
import time

import httpx
import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.main import app
from app.services import sandbox

TOO_COMPLEX = {"detail": "this one is too complex for me"}
# The request that froze the dev server during the 2026-11-27 audit: substituting rationals into
# a power tower builds an astronomically large exact integer and never returns.
TOWER = r"\frac{d}{dx} x^{x^{x}}"
TOWER_STEP = r"x^{x^{x^x}}"


@pytest.fixture()
def client():
    with TestClient(app) as c:
        yield c


def test_power_tower_verify_is_cut_off_and_the_service_keeps_answering(client, monkeypatch):
    monkeypatch.setattr(settings, "VERIFY_TIMEOUT_S", 3.0)
    started = time.monotonic()
    r = client.post("/verify", json={"problem_latex": TOWER, "user_steps": [TOWER_STEP]})
    took = time.monotonic() - started
    assert (r.status_code, r.json()) == (422, TOO_COMPLEX)
    assert took < 8, f"took {took:.1f}s"
    # /health and an ordinary problem still work straight away (the stuck worker was replaced).
    assert client.get("/health").json()["status"] == "ok"
    r = client.post("/solve", json={"session_id": "", "latex": r"\frac{d}{dx} x^2"})
    assert r.status_code == 200 and r.json()["answer_latex"] == "2 x"


def test_health_answers_while_a_slow_verify_is_running(client, monkeypatch):
    import threading

    monkeypatch.setattr(settings, "VERIFY_TIMEOUT_S", 4.0)
    done = {}

    def slow():
        done["r"] = client.post("/verify", json={"problem_latex": TOWER, "user_steps": [TOWER_STEP]})

    t = threading.Thread(target=slow)
    t.start()
    time.sleep(1.0)
    started = time.monotonic()
    assert client.get("/health").status_code == 200
    assert time.monotonic() - started < 1.0
    t.join(timeout=15)
    assert done["r"].status_code == 422


@pytest.mark.parametrize("path, body", [
    ("/solve", {"session_id": "", "latex": "x+" * 1500 + "x"}),                        # 3001 characters
    ("/verify", {"problem_latex": "x+" * 1500 + "x", "user_steps": ["1"]}),
    ("/verify", {"problem_latex": r"\frac{d}{dx} x^2", "user_steps": ["x+" * 1500 + "x"]}),
    ("/verify", {"problem_latex": r"\frac{d}{dx} x^2", "user_steps": ["2x"] * 41}),      # too many steps
    ("/solve", {"session_id": "", "latex": "(" * 30 + "x" + ")" * 30}),                 # nested 30 deep
    ("/solve", {"session_id": "", "latex": "{" * 25 + "x" + "}" * 25}),
])
def test_oversize_input_is_refused_without_being_parsed(client, path, body):
    started = time.monotonic()
    r = client.post(path, json=body)
    assert (r.status_code, r.json()) == (422, TOO_COMPLEX)
    assert time.monotonic() - started < 1.0


def test_input_at_the_limits_is_still_accepted(client):
    assert sandbox.nesting_depth("(" * 24 + "x" + ")" * 24) == 24
    r = client.post("/solve", json={"session_id": "", "latex": "(" * 24 + "x" + ")" * 24})
    assert r.status_code == 200
    r = client.post("/verify", json={"problem_latex": r"\frac{d}{dx} x^2", "user_steps": ["2x"] * 40})
    assert r.status_code == 200 and r.json()["verified"] is True


def test_ordinary_errors_keep_their_own_messages(client):
    r = client.post("/solve", json={"session_id": "", "latex": r"\frac{d}{dx}("})
    assert r.status_code == 422 and r.json()["detail"].startswith("could not parse")
    r = client.post("/verify", json={"problem_latex": r"\sum_{n=1}^{\infty} \frac{1}{n}", "steps": []})
    assert r.status_code == 422 and "series" in r.json()["detail"]


def _mathpix(monkeypatch, tmp_path, response):
    monkeypatch.setattr(settings, "STUB_RECOGNITION", False)
    monkeypatch.setattr(settings, "MATHPIX_APP_ID", "x")
    monkeypatch.setattr(settings, "MATHPIX_APP_KEY", "y")
    monkeypatch.setattr(settings, "STORAGE_DIR", tmp_path)
    monkeypatch.setattr(httpx.AsyncClient, "post", response)


def test_recognize_with_an_oversize_reading_is_200_with_a_parse_error(client, monkeypatch, tmp_path):
    long = "x+" * 1500 + "x"

    async def post(self, url, json=None, headers=None, **kw):
        return httpx.Response(200, json={"latex_styled": long, "confidence": 0.9}, request=httpx.Request("POST", url))

    _mathpix(monkeypatch, tmp_path, post)
    r = client.post("/recognize", files={"image": ("a.jpg", b"\xff\xd8fake", "image/jpeg")})
    assert r.status_code == 200
    body = r.json()
    assert (body["parse_error"], body["steps"], body["topic_guess"]) == ("this one is too complex for me", [], "unknown")
    assert body["problem_latex"] == long


def test_recognize_gives_up_on_a_slow_recognizer(client, monkeypatch, tmp_path):
    import asyncio

    async def post(self, url, json=None, headers=None, **kw):
        await asyncio.sleep(5)

    _mathpix(monkeypatch, tmp_path, post)
    monkeypatch.setattr(settings, "RECOGNIZE_TIMEOUT_S", 0.5)
    started = time.monotonic()
    r = client.post("/recognize", files={"image": ("a.jpg", b"\xff\xd8fake", "image/jpeg")})
    assert (r.status_code, r.json()) == (503, {"detail": "recognition unavailable: timed out"})
    assert time.monotonic() - started < 3
