#!/usr/bin/env python3
"""drill_abort.py — adversarial stream-abort drill for the routing gate (, native
leg; the pattern adapted to the single-alias two-backend shape).

A raw ``http.client`` client opens a streaming request on the OpenAI face
(``deepseek-v4-flash`` — the stage 3 two-backend alias), reads 1–2 frames, then closes
the socket mid-generation. Asserts:

  (a) cancellation — the serving fake OBSERVES the connection close before serving
      the full stream (the fakes record every early close to --abort-log; the runner
      clears the log before each run so counts are this run's);
  (b) platform-thread flatness of the Janus process (before/during/after samples —
      virtual threads never appear in ``ps nlwp``; slack is generous);
  (c) a follow-up request on the same boot succeeds (no poisoned state).

The Janus-log check (no unhandled exception storm) is the runner's job.

Usage:
  drill_abort.py --base-url http://127.0.0.1:8080/v1 --janus-pid <pid>
                 --abort-log <path> [--cells 4] [--thread-slack 24]
"""
from __future__ import annotations

import argparse
import http.client
import json
import sys
import threading
import time
import urllib.parse
from pathlib import Path

from openai import OpenAI

from harness_common import platform_threads

DUMMY_KEY = "janus-smoke-dummy-key"


def abort_cell(base_url: str) -> None:
    """Open a stream, read 1-2 frames, then close the socket mid-generation."""
    url = urllib.parse.urlsplit(base_url)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=30.0)
    body = json.dumps(
        {
            "model": "deepseek-v4-flash",
            "messages": [{"role": "user", "content": "mode=stream w24-abort"}],
            "stream": True,
        }
)
    conn.request("POST", "/v1/chat/completions", body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    assert resp.status == 200, f"abort: HTTP {resp.status}"
    frames = 0
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if line.startswith("data:"):
            frames += 1
        if frames >= 2:
            break
    assert frames >= 1, "abort: no data frame arrived before the abort"
    conn.close()  # client-side abort mid-generation


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--janus-pid", type=int, required=True)
    parser.add_argument("--abort-log", type=Path, required=True)
    parser.add_argument("--cells", type=int, default=4)
    parser.add_argument("--thread-slack", type=int, default=24)
    parser.add_argument("--settle", type=float, default=1.0)
    args = parser.parse_args()

    if args.abort_log.exists():
        args.abort_log.unlink()

    # Warm-up: pooled platform threads exist before the baseline sample.
    warm = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    list(warm.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "mode=stream w24-abort-warmup"}],
        stream=True,
))

    threads_before = platform_threads(args.janus_pid)

    def worker() -> None:
        abort_cell(args.base_url)

    threads: list[threading.Thread] = []
    for i in range(args.cells):
        t = threading.Thread(target=worker, name=f"abort-{i}")
        threads.append(t)
    t0 = time.monotonic()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=30.0)
    elapsed = time.monotonic() - t0
    threads_during = platform_threads(args.janus_pid)
    time.sleep(args.settle)
    threads_after = platform_threads(args.janus_pid)

    # (a) the fakes observed every early close.
    observed = 0
    if args.abort_log.exists():
        for line in args.abort_log.read_text(encoding="utf-8").splitlines():
            if line.strip():
                try:
                    if json.loads(line).get("early_close"):
                        observed += 1
                except json.JSONDecodeError:
                    pass
    if observed < args.cells:
        print(
            f"FAIL: upstream observed {observed}/{args.cells} early closes (abort-log {args.abort_log})",
            file=sys.stderr,
)
        sys.exit(1)

    # (c) follow-up succeeds (no poisoned state).
    follow = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    resp = follow.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "w24-abort-followup"}],
)
    assert resp.choices[0].message.content, "abort follow-up returned blank content"

    # (b) platform threads flat.
    during_growth = threads_during - threads_before
    after_growth = threads_after - threads_before
    if during_growth > args.thread_slack:
        print(f"FAIL: platform thread growth during {during_growth} > slack {args.thread_slack}", file=sys.stderr)
        sys.exit(1)
    if after_growth > args.thread_slack // 2:
        print(f"FAIL: platform thread growth after {after_growth} > {args.thread_slack // 2}", file=sys.stderr)
        sys.exit(1)

    print(
        f"abort result: cells={args.cells} upstream_observed={observed} elapsed={elapsed:.2f}s "
        f"janus_platform_threads before={threads_before} during={threads_during} after={threads_after}",
        flush=True,
)
    print(f"PASS: {args.cells} aborts — upstream observed {observed} early closes; platform threads flat; follow-up ok")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
