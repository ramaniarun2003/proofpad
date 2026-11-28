"""Run CPU-bound work in worker processes with a hard deadline.

`run("solve", latex, variable, topic, timeout=10)` hands the call to an idle worker
(services/sandbox_worker.py) and waits at most `timeout` seconds. A worker that is still busy at
the deadline is killed and replaced, and the caller gets [TooComplex]. The web process itself never
runs SymPy, so /health and every other request keep answering whatever a single input does.
"""
from __future__ import annotations

import atexit
import json
import logging
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

from ..config import settings

log = logging.getLogger("proofpad.sandbox")

TOO_COMPLEX = "this one is too complex for me"
_BACKEND_ROOT = Path(__file__).resolve().parents[2]
_STARTUP_TIMEOUT_S = 60.0


class TooComplex(Exception):
    """The input is over a size limit, or the work ran past its deadline."""


class InvalidInput(ValueError):
    """The worker rejected the input (a parse error or a ValueError); the text is for the student."""


class WorkerError(RuntimeError):
    """The worker hit an unexpected exception, or died."""


class _Worker:
    def __init__(self) -> None:
        self.proc = subprocess.Popen(
            [sys.executable, "-m", "app.services.sandbox_worker"],
            cwd=_BACKEND_ROOT, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1,
        )
        self.replies: queue.Queue[str | None] = queue.Queue()
        threading.Thread(target=self._read, daemon=True).start()
        self.ready = False

    def _read(self) -> None:
        assert self.proc.stdout is not None
        for line in self.proc.stdout:
            self.replies.put(line)
        self.replies.put(None)     # the worker exited

    def reply(self, timeout: float) -> dict | None:
        """The next reply, None if the worker died; raises queue.Empty at the deadline."""
        line = self.replies.get(timeout=timeout)
        return None if line is None else json.loads(line)

    def wait_ready(self) -> bool:
        if not self.ready:
            try:
                self.ready = self.reply(_STARTUP_TIMEOUT_S) == {"ready": True}
            except queue.Empty:
                self.ready = False
        return self.ready

    def send(self, fn: str, args: tuple) -> None:
        assert self.proc.stdin is not None
        self.proc.stdin.write(json.dumps({"fn": fn, "args": list(args)}) + "\n")
        self.proc.stdin.flush()

    def kill(self) -> None:
        try:
            self.proc.kill()
            self.proc.wait(timeout=5)
        except Exception:
            pass


_idle: queue.Queue[_Worker] = queue.Queue()
_all: list[_Worker] = []
_lock = threading.Lock()
_started = False


def _spawn() -> None:
    worker = _Worker()
    with _lock:
        _all.append(worker)
    if worker.wait_ready():
        _idle.put(worker)
    else:
        log.error("sandbox worker failed to start")
        _retire(worker)


def _retire(worker: _Worker) -> None:
    worker.kill()
    with _lock:
        if worker in _all:
            _all.remove(worker)


def start() -> None:
    """Start the workers (idempotent). Called at server startup so the first request is not slow."""
    global _started
    with _lock:
        if _started:
            return
        _started = True
    for _ in range(max(1, settings.SANDBOX_WORKERS)):
        threading.Thread(target=_spawn, daemon=True).start()


def stop() -> None:
    global _started
    with _lock:
        workers, _started = list(_all), False
        _all.clear()
    while not _idle.empty():
        try:
            _idle.get_nowait()
        except queue.Empty:
            break
    for worker in workers:
        worker.kill()


atexit.register(stop)


def run(fn: str, *args, timeout: float):
    """Call `compute.<fn>(*args)` in a worker. Raises TooComplex, InvalidInput or WorkerError."""
    start()
    deadline = time.monotonic() + timeout
    try:
        # Every worker busy: wait for one, no longer than this call's own deadline (longer only
        # while the workers are still starting up and importing SymPy).
        worker = _idle.get(timeout=timeout if _all_ready() else _STARTUP_TIMEOUT_S)
    except queue.Empty:
        raise TooComplex("no worker became free in time") from None
    try:
        worker.send(fn, args)
        reply = worker.reply(max(0.05, deadline - time.monotonic()))
    except queue.Empty:
        log.warning("sandbox: %s ran past %.0fs; worker %s killed", fn, timeout, worker.proc.pid)
        _retire(worker)
        threading.Thread(target=_spawn, daemon=True).start()
        raise TooComplex(f"{fn} ran past {timeout:.0f}s") from None
    except (BrokenPipeError, OSError) as exc:
        _retire(worker)
        threading.Thread(target=_spawn, daemon=True).start()
        raise WorkerError(f"worker unavailable: {exc}") from exc
    if reply is None:      # died mid-call (out of memory, a crash in a C extension)
        log.warning("sandbox: worker %s died during %s", worker.proc.pid, fn)
        _retire(worker)
        threading.Thread(target=_spawn, daemon=True).start()
        raise TooComplex(f"worker died during {fn}")
    _idle.put(worker)
    if "ok" in reply:
        return reply["ok"]
    if "too_complex" in reply:
        raise TooComplex(reply["too_complex"])
    if "invalid" in reply:
        raise InvalidInput(reply["invalid"])
    raise WorkerError(reply.get("error", "unknown worker error"))


def _all_ready() -> bool:
    with _lock:
        return bool(_all) and all(w.ready for w in _all)


# --- Input limits, checked before anything is parsed -------------------------------------------

_OPENERS, _CLOSERS = "{([", "})]"


def nesting_depth(latex: str) -> int:
    depth = deepest = 0
    for ch in latex:
        if ch in _OPENERS:
            depth += 1
            deepest = max(deepest, depth)
        elif ch in _CLOSERS:
            depth = max(0, depth - 1)
    return deepest


def check_input(*latex: str, steps: int = 0) -> None:
    """Raise TooComplex when any string is too long or too deeply nested, or there are too many steps."""
    if steps > settings.MAX_STEPS:
        raise TooComplex(f"{steps} steps (limit {settings.MAX_STEPS})")
    for text in latex:
        if len(text) > settings.MAX_LATEX_CHARS:
            raise TooComplex(f"{len(text)} characters (limit {settings.MAX_LATEX_CHARS})")
        if nesting_depth(text) > settings.MAX_LATEX_DEPTH:
            raise TooComplex(f"nested {nesting_depth(text)} deep (limit {settings.MAX_LATEX_DEPTH})")
