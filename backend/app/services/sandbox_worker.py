"""One sandbox worker: reads `{"fn": name, "args": [...]}` lines on stdin, answers one JSON line each.

Started by services/sandbox.py as `python -m app.services.sandbox_worker`. It exits when stdin
closes (the server went away) and is killed by the server when a call runs past its deadline.
"""
from __future__ import annotations

import json
import logging
import os
import sys


def main() -> None:
    # The protocol owns the real stdout; anything else that prints goes to stderr.
    out = os.fdopen(os.dup(sys.stdout.fileno()), "w", buffering=1)
    sys.stdout = sys.stderr
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(logging.Formatter("INFO:     %(name)s: %(message)s"))
    log = logging.getLogger("proofpad")
    log.addHandler(handler)
    log.setLevel(logging.INFO)
    log.propagate = False

    from . import compute   # imports SymPy and the parser: the slow part, done once

    out.write(json.dumps({"ready": True}) + "\n")
    for line in sys.stdin:
        call = json.loads(line)
        try:
            reply = {"ok": getattr(compute, call["fn"])(*call["args"])}
        except (RecursionError, MemoryError, OverflowError) as exc:
            reply = {"too_complex": type(exc).__name__}
        except ValueError as exc:            # ParseError is a ValueError: the student can fix these
            reply = {"invalid": str(exc)}
        except Exception as exc:             # a bug in a generator must not take the worker down
            reply = {"error": f"{type(exc).__name__}: {exc}"}
        out.write(json.dumps(reply) + "\n")


if __name__ == "__main__":
    main()
