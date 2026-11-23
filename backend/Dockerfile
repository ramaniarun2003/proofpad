# ProofPad backend — small production image (no dev deps, no tests).
FROM python:3.11-slim

WORKDIR /app

# Install runtime dependencies only. Strip the test-only dep to keep the image lean.
COPY requirements.txt .
RUN grep -ivE '^\s*pytest' requirements.txt > /tmp/requirements.txt \
    && pip install --no-cache-dir -r /tmp/requirements.txt

COPY app/ ./app/

# Public host: no LAN, so don't advertise over mDNS. The platform injects $PORT.
ENV PORT=8000 \
    PROOFPAD_MDNS=0

EXPOSE 8000
CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT}"]
