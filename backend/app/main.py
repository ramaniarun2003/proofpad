"""FastAPI entry point.  Run:  uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload"""
from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from .config import settings
from .db import init_db
from .models import HealthResponse
from .routers import export, recognize, solve
from .services import discovery, recognition

@asynccontextmanager
async def lifespan(_: FastAPI):
    init_db()
    discovery.print_connection_block(settings.PORT, settings.APP_VERSION)
    mdns = None
    if settings.MDNS_ENABLED:
        try:
            mdns = await discovery.register_mdns(settings.PORT, settings.APP_VERSION)
            print(f"mDNS: advertising _proofpad._tcp.local. on port {settings.PORT}", flush=True)
        except Exception as exc:  # advertising is best-effort; the app still works without it
            print(f"mDNS: not advertising ({type(exc).__name__}: {exc})", flush=True)
    try:
        yield
    finally:
        if mdns is not None:
            await discovery.unregister_mdns(mdns)


app = FastAPI(
    lifespan=lifespan,
    title="Handwritten Math Notes Digitizer & Solver — backend",
    version="0.1.0",
    description="Recognize (Mathpix) → parse (SymPy) → rule-based steps → independent verification.",
)
# No CORS — the only client is the native Android app, which isn't subject to CORS.

# Shared-secret gate for the public deployment. When PROOFPAD_API_KEY is set, every request
# except /health must send a matching X-ProofPad-Key header, so a public URL can't burn Mathpix
# credit. Empty key = open (local dev).
@app.middleware("http")
async def require_api_key(request: Request, call_next):
    if settings.PROOFPAD_API_KEY and request.url.path != "/health":
        if request.headers.get("X-ProofPad-Key") != settings.PROOFPAD_API_KEY:
            return JSONResponse({"detail": "invalid or missing X-ProofPad-Key"}, status_code=401)
    return await call_next(request)


app.include_router(recognize.router)
app.include_router(solve.router)
app.include_router(export.router)


@app.get("/health", response_model=HealthResponse)
async def health():
    return HealthResponse(
        status="ok",
        stub_recognition=settings.STUB_RECOGNITION,
        mathpix_reachable=await recognition.reachable(),
    )


@app.get("/discover")
async def discover():
    """Every URL this machine is reachable at, for the Android QR / discovery UI."""
    return {"urls": discovery.urls(settings.PORT), "version": settings.APP_VERSION}
