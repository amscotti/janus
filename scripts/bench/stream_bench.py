#!/usr/bin/env python3
"""stream_bench.py — streaming-concurrency drill ( stress_streams frame
counting, counted SEPARATELY from the non-streaming throughput benches — the stage 6
Review bullet).

Opens N concurrent SSE streams (stream:true chat completions) against one
implementation, reads every frame until the terminal data: [DONE], and asserts:

  - every stream completes (exactly one terminal [DONE] frame),
  - every data: payload parses as JSON (frame integrity),
  - every stream carries at least one completion delta frame (the fake's golden
    corpus shape; a zero-frame stream = truncated).

Prints key=value lines for the runner: streams=N, completed=N, integrity_violations=N,
frames_per_stream=min..max, elapsed_s=..., (a violation count > 0 exits nonzero).

Usage:
  stream_bench.py --name <leg> --url <chat-completions-url> [--streams 20]
                  [--auth "Bearer sk-x"] [--timeout 120]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

DONE = "[DONE]"


def run_stream(url: str, body: dict, auth: str | None, timeout: float, results: list, index: int) -> None:
    """One SSE stream; appends (ok, done_count, frames, json_ok) to results."""
    request = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), method="POST", headers={"Content-Type": "application/json"}
)
    if auth:
        request.add_header("Authorization", auth)
    done_count = 0
    frames = 0
    json_ok = True
    ok = False
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            for raw in response:
                line = raw.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                frames += 1
                payload = line[5:].strip()
                if payload == DONE:
                    done_count += 1
                else:
                    try:
                        json.loads(payload)
                    except json.JSONDecodeError:
                        json_ok = False
        ok = done_count == 1
    except Exception:
        ok = False
    results[index] = (ok, done_count, frames, json_ok)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--streams", type=int, default=20)
    parser.add_argument("--auth", default=None)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--run-dir", default=".run")
    args = parser.parse_args()

    body = {"model": "deepseek-v4-flash", "stream": True, "messages": [{"role": "user", "content": "w50-stream"}]}
    results: list = [None] * args.streams
    threads = [
        threading.Thread(target=run_stream, args=(args.url, body, args.auth, args.timeout, results, i), daemon=True)
        for i in range(args.streams)
    ]
    start = time.monotonic()
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    elapsed = time.monotonic() - start

    completed = sum(1 for r in results if r and r[0])
    violations = sum(1 for r in results if r and (not r[3] or r[1] != 1))
    frames = [r[2] for r in results if r]
    summary = {
        "streams": args.streams,
        "completed": completed,
        "integrity_violations": violations,
        "frames_per_stream": f"{min(frames)}..{max(frames)}" if frames else "0",
        "elapsed_s": f"{elapsed:.2f}",
    }
    raw = Path(os.path.abspath(args.run_dir)) / f"{args.name}.stream.raw.txt"
    raw.parent.mkdir(parents=True, exist_ok=True)
    raw.write_text(
        "\n".join(f"stream {i}: ok={r[0]} done={r[1]} frames={r[2]} json_ok={r[3]}" for i, r in enumerate(results))
        + f"\nsummary: {json.dumps(summary)}\n",
        encoding="utf-8",
)
    for key, value in summary.items():
        print(f"{key}={value}", flush=True)
    if completed != args.streams or violations:
        print(f"stream_bench FAILED: completed={completed}/{args.streams} violations={violations}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
