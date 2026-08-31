#!/usr/bin/env python3
"""kill_midstream.py — raw SSE client for the mid-stream kill-upstream drill (,
port of the drill to BOTH faces).

The runner boots Janus against the fake upstreams, starts this client in the
background, waits for --ready-file (touched after the first complete frame), then
fires the kill (the fakes' pause hook: --resume-file appears → the fake closes its
response mid-frame → EOF-in-pending-frame → Janus must surface an SSE error frame and
complete cleanly — never a hang, never an HTTP error mid-stream).

Face selection:
  --face openai    POST /v1/chat/completions (deepseek-v4-flash) — asserts a JSON error
                   frame (``{"error": {...}}`` with type ``api_error``) then EOF
  --face anthropic POST /v1/messages (claude-3-5-sonnet) — asserts an ``event: error``
                   frame (Anthropic error envelope) then EOF

Usage:
  kill_midstream.py --base-url http://127.0.0.1:8080/v1 --face openai
                    --ready-file /tmp/ready [--bound 5]
"""
from __future__ import annotations

import argparse
import http.client
import json
import sys
import time
import urllib.parse
from pathlib import Path

MAX_TOKENS = 1024


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--face", choices=["openai", "anthropic"], default="openai")
    parser.add_argument("--ready-file", default=None)
    parser.add_argument("--bound", type=float, default=5.0)
    args = parser.parse_args()

    if args.face == "openai":
        path = "/v1/chat/completions"
        body = json.dumps(
            {
                "model": "deepseek-v4-flash",
                "messages": [{"role": "user", "content": "mode=stream&pause=1 kill-midstream"}],
                "stream": True,
            }
)
    else:
        path = "/v1/messages"
        body = json.dumps(
            {
                "model": "claude-3-5-sonnet",
                "max_tokens": MAX_TOKENS,
                "messages": [{"role": "user", "content": "mode=stream&pause=1 kill-midstream"}],
                "stream": True,
            }
)

    url = urllib.parse.urlsplit(args.base_url)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=max(args.bound * 4, 30.0))
    start = time.monotonic()
    conn.request("POST", path, body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    assert resp.status == 200, f"kill-midstream ({args.face}): HTTP {resp.status} (errors go in SSE frames)"
    ctype = resp.getheader("Content-Type", "")
    assert ctype.startswith("text/event-stream"), f"kill-midstream ({args.face}): Content-Type {ctype!r}"

    first_at: float | None = None
    error_frame: dict | None = None
    error_event = False
    frames = 0
    event_name = "message"
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if line.startswith("event:"):
            event_name = line[len("event:") :].strip()
            continue
        if not line.startswith("data:"):
            continue
        payload = line[len("data:") :].strip()
        frames += 1
        if first_at is None:
            first_at = time.monotonic()
            if args.ready_file:
                Path(args.ready_file).touch()
        if args.face == "openai":
            if payload.startswith("{"):
                try:
                    obj = json.loads(payload)
                    if isinstance(obj, dict) and "error" in obj:
                        error_frame = obj["error"]
                except json.JSONDecodeError:
                    pass
        else:
            if event_name == "error":
                error_event = True
                try:
                    error_frame = json.loads(payload).get("error")
                except json.JSONDecodeError:
                    error_frame = {}
    elapsed = time.monotonic() - start
    conn.close()

    assert first_at is not None, f"kill-midstream ({args.face}): no data frame arrived before the stream ended"
    if args.face == "openai":
        assert error_frame is not None, "kill-midstream (openai): expected an SSE error frame after the kill"
        assert error_frame.get("type") == "api_error", (
            f"kill-midstream (openai): SSE error frame type {error_frame.get('type')!r} != api_error"
)
    else:
        assert error_event, "kill-midstream (anthropic): expected an event: error frame after the kill"
        assert error_frame and error_frame.get("type") == "api_error", (
            f"kill-midstream (anthropic): error envelope type {error_frame.get('type') if error_frame else None!r} != api_error"
)
    log(
        f"PASS kill-midstream ({args.face}): {frames} frames, first delta at {first_at - start:.2f}s, "
        f"error frame observed, stream terminated in {elapsed:.2f}s (no hang)"
)


def log(msg: str) -> None:
    print(msg, flush=True)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
