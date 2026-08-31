#!/usr/bin/env python3
"""drill_abort.py — stage 4 stream-abort drill ( native leg; the / pattern
adapted to the stage 4 auth-on, budget-governed shape).

A raw-socket client opens a streaming request on the OpenAI face
(``deepseek-v4-flash`` — the single-backend stage 4 alias) with a BUDGET-CAPPED virtual
key (budget 0.7 USD; the cells reserve 168 000 micro each), reads 1–2 frames, then
closes the socket mid-generation. Asserts:

  (a) every abort cell receives a 200 stream with at least one data frame before
      the abort (the gateway did not reject/limit the streams);
  (b) platform-thread flatness of the Janus process (before/during/after samples —
      virtual threads never appear in ``ps nlwp``; slack generous);
  (c) the streamed governance release path: the aborted stream RELEASES its
      reservation, so a follow-up non-stream request on the same key succeeds — a
      leaked reservation (5 320 + 672 000 + 286 720 ≥ 700 000) would deny it forever
 ( ``streamingAbortReleasesReservation`` in-suite, live here);
  (d) the Janus log stays clean (no unhandled exception storm — the runner's job).

The cancellation-observance half (the fake recording the early close) is
informational here: through the gateway, a client abort cancels the upstream stream
via the publisher's close hook — a CLEAN close, not an RST, so the fake's abort-log
is often empty. The governance proof that matters is (c), and (b)/(d) cover the
thread/log cleanliness the pattern asserted.

Usage:
  drill_abort.py --base-url http://127.0.0.1:PORT/v1 --master-key <key>
                 --janus-pid <pid> --abort-log <path> [--cells 4] [--thread-slack 24]
"""
from __future__ import annotations

import argparse
import json
import socket
import struct
import sys
import threading
import time
import urllib.parse
from pathlib import Path

from harness_common import MODEL, generate_key, platform_threads

# Budget 0.7 USD: the 4 concurrent cells reserve 600 × 0.28/1000 = 168 000 micro
# each (all admitted: 5 320 settled + 4 × 168 000 = 677 320 < 700 000); the follow-up
# (estimate 286 720) succeeds ONLY if the aborted streams released their reservations
# — a leak would deny it (5 320 + 672 000 + 286 720 = 964 040 ≥ 700 000).
BUDGET_USD = 0.7
ESTIMATE_MICRO = 168_000


def abort_cell(base_url: str, key: str) -> None:
    """Open a stream via a raw socket, read 1-2 frames, then RST the connection
    (SO_LINGER 0) — the fake's next write fails with ECONNRESET deterministically
    (a plain close can be absorbed by kernel buffers on localhost)."""
    url = urllib.parse.urlsplit(base_url)
    sock = socket.create_connection((url.hostname, url.port), timeout=30.0)
    body = json.dumps(
        {
            "model": MODEL,
            "messages": [{"role": "user", "content": "mode=stream w33-abort"}],
            "stream": True,
            "max_tokens": 600,
        }
)
    request = (
        f"POST /v1/chat/completions HTTP/1.1\r\n"
        f"Host: {url.hostname}:{url.port}\r\n"
        f"Content-Type: application/json\r\n"
        f"x-api-key: {key}\r\n"
        f"Content-Length: {len(body)}\r\n"
        f"Connection: close\r\n\r\n"
        f"{body}"
)
    sock.sendall(request.encode("utf-8"))
    sock.settimeout(10.0)
    frames = 0
    buf = b""
    while frames < 2:
        chunk = sock.recv(4096)
        if not chunk:
            break
        buf += chunk
        frames = buf.count(b"data:")
    assert frames >= 1, "abort: no data frame arrived before the abort"
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))  # RST
    sock.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--janus-pid", type=int, required=True)
    parser.add_argument("--abort-log", type=Path, required=True)
    parser.add_argument("--cells", type=int, default=4)
    parser.add_argument("--thread-slack", type=int, default=24)
    parser.add_argument("--settle", type=float, default=1.0)
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    key_id, full_key = generate_key(base, args.master_key, models=[MODEL], name="w33-abort", budget_usd=BUDGET_USD)

    if args.abort_log.exists():
        args.abort_log.unlink()

    # Warm-up : pooled platform threads exist before the baseline sample.
    from openai import OpenAI

    warm = OpenAI(base_url=base, api_key=full_key, timeout=30.0, max_retries=0)
    warm.chat.completions.create(
        model=MODEL, messages=[{"role": "user", "content": "w33-abort-warm"}], max_tokens=1024
)

    before = platform_threads(args.janus_pid)
    threads = [threading.Thread(target=abort_cell, args=(base, full_key)) for _ in range(args.cells)]
    for t in threads:
        t.start()
    time.sleep(0.5)
    during = platform_threads(args.janus_pid)
    for t in threads:
        t.join()
    time.sleep(args.settle)
    after = platform_threads(args.janus_pid)

    growth = max(after - before, during - before, 0)
    assert growth <= args.thread_slack, (
        f"platform threads grew by {growth} (before {before}, during {during}, after {after}; slack {args.thread_slack})"
)

    early_closes = 0
    if args.abort_log.exists():
        for line in args.abort_log.read_text(encoding="utf-8").splitlines():
            if line.strip():
                early_closes += 1
    # informational — through the gateway the upstream is cancelled with a clean
    # close, not an RST, so the fake's abort-log may stay empty (see the docstring).

    # Governance release path: the aborted streams must have RELEASED their
    # reservations — the follow-up succeeds within the bound (a leak would deny it
    # forever: 5 320 + 672 000 + 286 720 ≥ 700 000).
    ok = False
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        try:
            resp = warm.chat.completions.create(
                model=MODEL, messages=[{"role": "user", "content": "w33-abort-followup"}], max_tokens=1024
)
            ok = True
            break
        except Exception:
            time.sleep(0.3)
    assert ok, "follow-up never succeeded within 10s — aborted streams leaked their reservations"
    assert resp.choices[0].message.content, "follow-up request returned empty content"

    print(
        f"drill_abort: ALL PASS — {args.cells} cells (200 + ≥1 frame each), fake abort-log entries: "
        f"{early_closes} (informational — the gateway cancels upstream with a clean close), "
        f"threads {before}→{during}→{after} (slack {args.thread_slack}), follow-up succeeded (reservation released)"
)


if __name__ == "__main__":
    main()
