# ProofPad backend — small production image (no dev deps, no tests).
FROM python:3.11-slim

WORKDIR /app

# Install runtime dependencies only. Strip the test-only dep to keep the image lean.
COPY requirements.txt .
RUN grep -ivE '^\s*pytest' requirements.txt > /tmp/requirements.txt \
    && pip install --no-cache-dir -r /tmp/requirements.txt

COPY app/ ./app/

# Public host: no LAN, so don't advertise over mDNS. The platform injects $PORT.
# Two server processes so one request cannot hold up /health, each with one solver worker
# process (app/services/sandbox.py) to stay inside a small instance's memory.
ENV PORT=8000 \
    PROOFPAD_MDNS=0 \
    WEB_CONCURRENCY=2 \
    SANDBOX_WORKERS=1

EXPOSE 8000
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --timeout-keep-alive 5 --workers ${WEB_CONCURRENCY}"]
