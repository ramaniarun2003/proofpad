# Handwritten Math Notes Digitizer & Solver

Photograph handwritten calculus → recognize (Mathpix) → confirm → rule-based step-by-step
solution (SymPy) → independent verification → PDF. Android app + Python backend.
Phase 1 proposal and slides are in the course project space; this repo is the code.

```
math-notes-solver/
├── backend/     FastAPI + SymPy service  (Python 3.10+)      ← backend track
├── android/     Kotlin + Compose app     (Android Studio)     ← Android track
├── docs/api.md  the phone ↔ backend contract — change both sides together
└── scripts/     demo_problems.json + eval.py (accuracy tables for the report)
```

## Backend — five minutes to running

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env            # leave MATHPIX_* empty → stub mode, no network needed
pytest                          # 31 tests: solver rules, verifier, API round trip
./run.sh                        # http://0.0.0.0:8000  — interactive docs at /docs
```

Try it without the app:
```bash
curl -s localhost:8000/solve -H 'content-type: application/json' \
  -d '{"session_id":"","latex":"\\frac{d}{dx} x^{2} \\sin(x)","topic":"derivative"}' | python3 -m json.tool
python3 ../scripts/eval.py ../scripts/demo_problems.json     # per-topic accuracy table
```

**Stub mode.** With no Mathpix key, `/recognize` returns `STUB_LATEX` for any image, so the Android
track can build the full capture → review → solve flow before the key exists. Set
`MATHPIX_APP_ID` / `MATHPIX_APP_KEY` in `.env` to go live. Mathpix is called from exactly one file
(`app/services/recognition.py`).

### Backend layout (report §4.1)
| Path | Module |
|---|---|
| `app/main.py` | API gateway, `/health` |
| `app/routers/recognize.py` | `/recognize` → Recognition Service |
| `app/routers/solve.py` | `/solve`, `/verify` → Symbolic Engine, Step Generator, Verification |
| `app/routers/export.py` | `/export`, `/sessions` (evaluation dump) |
| `app/services/recognition.py` | Mathpix client + stub |
| `app/services/latex_norm.py` | LaTeX normalization (`\left`, `\dfrac`, `\ln`, …) |
| `app/services/symbolic.py` | `parse_latex` → SymPy tree, topic classification |
| `app/services/steps/derivative.py` | rules: constant, sum, product, quotient, power, chain, exponential, log-diff |
| `app/services/steps/integral.py` | rules: linearity, power/table, u-substitution, by parts (LIATE), partial fractions, CAS fallback |
| `app/services/steps/series.py` | tests: divergence, p-series, geometric, ratio, root, alternating, CAS fallback |
| `app/services/verify.py` | independent recompute + per-step equivalence → `verified`, `mismatch_at` |
| `app/db.py` | SQLite session log via SQLAlchemy |
| `tests/` | `test_solver.py` (rules + verifier), `test_api.py` (HTTP round trip in stub mode) |

**The one invariant to keep:** every generator emits a linear chain where `steps[i].after ==
steps[i+1].before` and every `after` equals the original problem (up to a constant for
antiderivatives). The verifier depends on it — `test_chain_invariant_every_step_equals_truth` guards it.

### Adding a rule
1. Add a branch in `_apply_rule` of the topic's generator **above** the CAS fallback.
2. Add the problem to `tests/test_solver.py` with the rule name you expect, and to
   `scripts/demo_problems.json`.
3. `pytest` — the verifier will tell you if the rule's output is wrong.

## Deploying (Render)
Host the backend publicly so the app works from campus Wi-Fi without the laptop. Config is
`backend/render.yaml` + `backend/Dockerfile`.

1. **Create the service.** Render → *New* → *Blueprint*, pick this repo. Render reads
   `backend/render.yaml` and provisions a free Docker web service `proofpad-backend` with a 1 GB
   disk mounted at `/data` and health check `/health`.
2. **Set the three secrets** (dashboard → the service → *Environment*): `MATHPIX_APP_ID`,
   `MATHPIX_APP_KEY`, and `PROOFPAD_API_KEY` (any long random string — the app must send the same
   value as the `X-ProofPad-Key` header, or every non-`/health` request is `401`). The rest
   (`DATABASE_URL=/data/proofpad.db`, `FILES_DIR=/data/files`, `PROOFPAD_MDNS=0`) come from the blueprint.
3. **Confirm it's up:** `curl https://<service>.onrender.com/health` → `{"status":"ok",...}`.
   Protected routes without the key return `401`:
   `curl -H "X-ProofPad-Key: <key>" https://<service>.onrender.com/discover`.
4. **Point the app at it:** put the real URL and key in `android/local.properties` (gitignored):
   `PUBLIC_BACKEND_URL=https://<service>.onrender.com` and `PROOFPAD_API_KEY=<key>`, then rebuild.
   The app tries saved → USB → mDNS → gateway → this public URL last, so on-LAN it still prefers the laptop.
5. **Keep it warm:** the free instance sleeps after ~15 min. `.github/workflows/keepwarm.yml` pings
   `/health` every 10 min during class hours — set the repo variable `PROOFPAD_URL` to your Render URL.
   First request after a sleep takes up to ~60s (the app shows "waking up…").

   > **Disk note:** Render's *free* plan has no persistent disk, so `/data` is ephemeral — the SQLite
   > file resets on each redeploy. That's fine here (the phone caches every session in Room), and the
   > backend logs a warning and keeps running. For durable history, upgrade the plan so the declared
   > 1 GB disk mounts, or point `DATABASE_URL` at a managed Postgres.
6. **Roll back:** Render dashboard → the service → *Deploys* → pick a previous successful deploy →
   *Rollback* (or *Redeploy*). Disable auto-deploy under *Settings* to pin a version.

**Alternative — Fly.io.** `fly launch` in `backend/` (it reads the `Dockerfile`), then
`fly secrets set MATHPIX_APP_ID=… MATHPIX_APP_KEY=… PROOFPAD_API_KEY=…`,
`fly volumes create proofpad_data --size 1` and mount it at `/data` (`[mounts]` in `fly.toml`),
set `DATABASE_URL=sqlite:////data/proofpad.db` and `FILES_DIR=/data/files`, then `fly deploy`.
Health check path `/health`. Roll back with `fly releases` + `fly deploy --image <prev>`.

## Android — see `android/README.md`
Open `android/` in Android Studio, set `BACKEND_URL` in `local.properties` (or from the app's
Backend button), run on a device. Emulator default is `http://10.0.2.2:8000/`.

## Demo-day network (report §3.4)
Laptop runs `./run.sh` on a dedicated hotspot; phones join the same hotspot and point at the laptop's
IP; the laptop needs an outbound path for Mathpix (backup: phone tether). Sessions are cached in
Room, so **History** on the phone shows solved problems even if the network drops.

## Milestones (from the Phase 1 plan)
- **M1 Oct 30** — photo → preprocess → `/recognize` → editable LaTeX on the phone
- **M2 Nov 16 (Phase 2 presentation)** — derivatives + integrals end to end, verified, demoed live
- **M3 Dec 2** — series, per-step mismatch reporting, PDF export, history; feature freeze
- **Dec 4** slides · **Dec 7** final demo · **Dec 14** final report
