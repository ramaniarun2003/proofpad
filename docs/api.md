# Phone ↔ backend API contract

Base URL: `http://<laptop-ip>:8000/`. JSON unless noted. Every response that concerns a
problem carries `session_id`; later calls reference the same problem with it.
Interactive docs are served by the backend at `/docs`.

Source of truth on each side: `backend/app/models.py` and
`android/app/src/main/java/com/mathnotes/solver/data/api/Dtos.kt`. Change both together.

## `POST /recognize` — multipart
| field | type | notes |
|---|---|---|
| `image` | file (JPEG) | preprocessed on device; **413** if > 2 MB |
| `device_id` | string | for logs only |

Response `200`:
```json
{"session_id": "…", "latex": "\\frac{d}{dx} x^{2} \\sin(x)", "confidence": 0.93,
 "topic_guess": "derivative", "low_confidence": false}
```
The response also carries the **Identify split** (`backend/app/services/identify.py`), for captures
that hold the problem and the student's working, e.g. `\\frac{d}{dx}\\sin(3x) = 3\\cos(3x)`:
```json
{"problem_latex": "\\frac{d}{d x} \\sin (3 x)", "steps": ["3 \\cos (3 x)"],
 "segments": [{"latex": "…", "line": 0, "bbox": [x0, y0, x1, y1]}], "split_uncertain": false}
```
`latex` stays the full recognized string. Lines come from Mathpix `line_data`; each is cut at its
top-level `=`. The problem is the first segment with an operator we solve (d/dx, ′, ∫, Σ, lim, ∂)
and every later segment is a step. No operator → `problem_latex` is the whole string, `steps` is
empty, and `split_uncertain` is true if there were `=` signs. `mode=page` returns the same four fields.

`503` when Mathpix is unreachable (app offers retry / History). With no Mathpix key the backend
runs in **stub mode** and always returns `STUB_LATEX` with confidence 0.99.

## `POST /solve`
```json
{"session_id": "…", "latex": "<confirmed LaTeX>", "topic": "derivative|integral|series|unknown", "variable": "x"}
```
Response `200`:
```json
{"session_id": "…", "topic": "derivative",
 "steps": [{"index": 0, "rule": "product rule", "before_latex": "…", "after_latex": "…", "note": "…"}],
 "answer_latex": "…", "verified": true, "mismatch_at": null, "details": ["direct result: …"]}
```
Invariant: `steps[i].after_latex == steps[i+1].before_latex`. For series, `after_latex` holds the
verdict of that test (`converges` / `diverges` / `inconclusive`) and `answer_latex` the final verdict.
`422` when the LaTeX does not parse (message names the problem). `session_id` may be empty for a
typed problem; the backend creates one.

Partial derivatives use topic `derivative` and take their variables from the
LaTeX. Write `\frac{\partial}{\partial x}(x^2y)` for a first derivative,
`\frac{\partial^2}{\partial x^2}(x^2y)` for a repeated derivative, or
`\frac{\partial^2}{\partial y\partial x}(x^2y)` to differentiate in `x` and then `y`.

Double and triple integrals use topic `integral`. For integrals with bounds, write
one `\int` per variable, as in `\int_0^1\int_0^x xy\,dy\,dx`. Give the differentials in
innermost-to-outermost order. Indefinite integrals also accept `\iint` and `\iiint`
with explicit differentials. The solver evaluates one variable per step and returns
`422` if it cannot evaluate the integral symbolically.

## `POST /verify`
Same shape as `/solve` plus `steps: [...]` (the user's edited chain). Returns
`{"verified": bool, "mismatch_at": int|null, "details": [...]}`. Not supported for series.
The app sends `problem_latex` as `latex` and the student's steps as the chain
(`steps[0].before_latex` = the problem, each later `before_latex` = the previous step). For a
limit, every step must equal the limit's value.

## `POST /export`
`{"session_id": "…"}` → the stored session as JSON (image, LaTeX, steps, answer, verification).
The phone renders the PDF itself; replace with a server-side renderer if preferred.

## `GET /health`
`{"status": "ok", "stub_recognition": true|false, "mathpix_reachable": true|false|null}`

Android uses this endpoint to check the local API. `mathpix_reachable` defaults to `null`.
Use `/health?check_mathpix=true` to check Mathpix separately.

## `GET /sessions?limit=50`
Evaluation helper: recent sessions with confidence, verification outcome and the event log.
