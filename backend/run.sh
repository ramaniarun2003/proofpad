#!/usr/bin/env bash
# Start the backend on all interfaces so phones on the same Wi-Fi can reach it.
#
#   ./run.sh           two server processes, as in production
#   DEV=1 ./run.sh     one process with --reload, for editing the backend
#
# Solve / verify / parsing run in separate worker processes under a deadline (app/services/sandbox.py),
# so a slow problem cannot block /health in either mode.
set -euo pipefail
cd "$(dirname "$0")"
[ -d .venv ] || python3 -m venv .venv
source .venv/bin/activate
pip install -q -r requirements.txt
PORT="${PORT:-8000}"
echo "Backend on port $PORT  (docs at /docs; the URLs and QR code for the phone are printed below)"
if [ "${DEV:-0}" = "1" ]; then
  exec uvicorn app.main:app --host 0.0.0.0 --port "$PORT" --timeout-keep-alive 5 --reload
fi
exec uvicorn app.main:app --host 0.0.0.0 --port "$PORT" --timeout-keep-alive 5 --workers "${WEB_CONCURRENCY:-2}"
