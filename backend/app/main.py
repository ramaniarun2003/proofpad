"""FastAPI entry point.  Run:  uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload"""
from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

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
            mdns = discovery.register_mdns(settings.PORT, settings.APP_VERSION)
            print(f"mDNS: advertising _proofpad._tcp.local. on port {settings.PORT}", flush=True)
        except Exception as exc:  # advertising is best-effort; the app still works without it
            print(f"mDNS: not advertising ({exc})", flush=True)
    try:
        yield
    finally:
        if mdns is not None:
            discovery.unregister_mdns(mdns)


app = FastAPI(
    lifespan=lifespan,
    title="Handwritten Math Notes Digitizer & Solver — backend",
    version="0.1.0",
    description="Recognize (Mathpix) → parse (SymPy) → rule-based steps → independent verification.",
)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
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
