#!/usr/bin/env bash
# Start the backend on all interfaces so phones on the same Wi-Fi can reach it.
set -euo pipefail
cd "$(dirname "$0")"
[ -d .venv ] || python3 -m venv .venv
source .venv/bin/activate
pip install -q -r requirements.txt
echo "Backend on http://$(hostname -I 2>/dev/null | awk '{print $1}' || echo 0.0.0.0):8000  (docs at /docs)"
exec uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
