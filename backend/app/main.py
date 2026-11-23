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
    yield


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
async def health(check_mathpix: bool = False):
    """Check the local API and optionally test the connection to Mathpix."""
    return HealthResponse(
        status="ok",
        stub_recognition=settings.STUB_RECOGNITION,
        mathpix_reachable=await recognition.reachable() if check_mathpix else None,
    )


@app.get("/discover")
async def discover():
    """List local interface URLs for direct connections."""
    return {"urls": discovery.urls(settings.PORT), "version": settings.APP_VERSION}
