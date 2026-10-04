#!/usr/bin/env python3
"""Evaluation harness (report §5, "accuracy tables").

Runs every problem in a JSON file through a running backend's /solve and prints
per-topic counts: parsed, verified, expected rule/verdict hit. Extend the JSON
with real handwritten samples (add "image": "path.jpg" to go through /recognize).

    python scripts/eval.py --backend http://localhost:8000 scripts/demo_problems.json
"""
import argparse
import collections
import json
import sys
from pathlib import Path

import httpx


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("problems", type=Path)
    ap.add_argument("--backend", default="http://localhost:8000")
    args = ap.parse_args()

    problems = json.loads(args.problems.read_text())
    tally = collections.defaultdict(lambda: collections.Counter())
    failures = []
    with httpx.Client(base_url=args.backend, timeout=60) as c:
        for p in problems:
            t = tally[p["topic"]]
            t["total"] += 1
            session_id = ""
            if "image" in p:
                with open(p["image"], "rb") as f:
                    r = c.post("/recognize", files={"image": (Path(p["image"]).name, f, "image/jpeg")})
                if r.status_code != 200:
                    failures.append((p["id"], f"recognize {r.status_code}")); continue
                rec = r.json()
                session_id, latex = rec["session_id"], rec["latex"]
                t["recognized"] += 1
                if latex.replace(" ", "") == p["latex"].replace(" ", ""):
                    t["ocr_exact"] += 1
            else:
                latex = p["latex"]
            r = c.post("/solve", json={"session_id": session_id, "latex": latex, "topic": p["topic"]})
            if r.status_code != 200:
                failures.append((p["id"], f"solve {r.status_code}: {r.text[:80]}")); continue
            t["parsed"] += 1
            res = r.json()
            if res["verified"]:
                t["verified"] += 1
            rules = {s["rule"] for s in res["steps"]}
            if "expect_rules" in p and set(p["expect_rules"]) <= rules:
                t["expected_rule"] += 1
            if "expect_verdict" in p and res["answer_latex"] == p["expect_verdict"]:
                t["expected_verdict"] += 1
            if "expect_rules" in p and not set(p["expect_rules"]) <= rules:
                failures.append((p["id"], f"rules {sorted(rules)}"))
            if "expect_verdict" in p and res["answer_latex"] != p["expect_verdict"]:
                failures.append((p["id"], f"verdict {res['answer_latex']}"))

    cols = ["total", "recognized", "ocr_exact", "parsed", "verified", "expected_rule", "expected_verdict"]
    print(f"{'topic':12s}" + "".join(f"{c:>16s}" for c in cols))
    for topic, t in sorted(tally.items()):
        print(f"{topic:12s}" + "".join(f"{t[c]:>16d}" for c in cols))
    if failures:
        print("\nfailures:")
        for pid, why in failures:
            print(f"  {pid}: {why}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
