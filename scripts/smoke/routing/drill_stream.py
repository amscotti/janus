#!/usr/bin/env python3
"""drill_stream.py — streaming-boundary drills for the routing gate (, gate 4).

The bounded failover contract (docs/routing.md "Streaming boundary", the reference §4.7):
retry/fallback are legal only BEFORE the first chunk; once a backend's stream is
delivered it is never retried or failed over. The runner orchestrates the fake
process lifecycle; this script runs one phase per invocation:

  --phase open-failover    fake-1 DEAD (runner killed it): a streaming request opens
                           on fake-2 (connect-refused → network retryable → retry walk)
                           and the client receives the FULL golden SSE stream
                           (valid deltas + data: [DONE]), byte-identical to a
                           fake-1-served stream; fake-2's stream counter grows.
 --phase midstream THIS script is the background client ( kill_midstream
                           pattern): opens a stream with mode=stream&pause=1 (the
                           runner has set fake-2 to 500 so the stream opens on fake-1),
                           touches --ready-file after the first frame, then reads to
                           completion. The runner fires fake-1's resume-file → the
 fake closes mid-frame ( deterministic close) → the
                           drill asserts an SSE error frame + clean completion within
                           --bound, NO hang, NO silent [DONE].
  --phase breaker-untripped  fake-1 restarted + modes reset: fresh streaming requests
                           succeed and fake-1 receives traffic IMMEDIATELY (no cooldown
                           wait) — the mid-stream failure was transient
                           (recordStreamFailure(b, false)) and must NOT have tripped
                           the breaker.

Usage:
  drill_stream.py --base-url http://127.0.0.1:8080/v1 --counters <c1>,<c2>
                  --phase open-failover|midstream|breaker-untripped
                  [--ready-file PATH] [--bound 5]
"""
from __future__ import annotations

import argparse
import http.client
import json
import sys
import time
import urllib.parse
from pathlib import Path

from harness_common import delta, snapshot

FIXTURE_CONTENT = "The weather in Paris is 18 degrees with light rain."


def log(msg: str) -> None:
    print(msg, flush=True)


def _open_stream(base_url: str) -> tuple[http.client.HTTPConnection, object]:
    url = urllib.parse.urlsplit(base_url)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=60.0)
    body = json.dumps(
        {
            "model": "deepseek-v4-flash",
            "messages": [{"role": "user", "content": "mode=stream w24-stream-drill"}],
            "stream": True,
        }
)
    conn.request("POST", "/v1/chat/completions", body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    assert resp.status == 200, f"stream drill: HTTP {resp.status} (errors go in SSE frames)"
    ctype = resp.getheader("Content-Type", "")
    assert ctype.startswith("text/event-stream"), f"stream drill: Content-Type {ctype!r}"
    return conn, resp


def _collect_stream(resp) -> tuple[list[str], bool]:
    """Collect SSE frames; return (content deltas, saw [DONE])."""
    deltas: list[str] = []
    done = False
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:") :].strip()
        if payload == "[DONE]":
            done = True
            continue
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            continue
        if "error" in obj:
            continue
        for choice in obj.get("choices", []):
            d = choice.get("delta", {})
            if d.get("content"):
                deltas.append(d["content"])
    return deltas, done


def open_failover(args) -> None:
    c1, c2 = [p.strip() for p in args.counters.split(",") if p.strip()]
    before = snapshot([c1, c2])
    conn, resp = _open_stream(args.base_url)
    deltas, done = _collect_stream(resp)
    conn.close()
    assert done, "open-failover: stream must terminate with data: [DONE]"
    assert len(deltas) >= 2, f"open-failover: expected >= 2 deltas, got {len(deltas)}"
    assert "".join(deltas) == FIXTURE_CONTENT, (
        f"open-failover: content {' '.join(deltas)!r} != golden fixture (failover must be byte-identical)"
)
    after = snapshot([c1, c2])
    moves = delta(before, after, "streams")
    assert moves[c2] >= 1, f"open-failover: fake-2 must serve the stream, deltas {moves}"
    assert moves[c1] == 0, f"open-failover: fake-1 is dead — its counter must be flat, deltas {moves}"
    log(f"PASS open-failover: full golden stream (deltas + [DONE]) from fake-2, byte-identical ({moves})")


def midstream(args) -> None:
    """The background client: open on fake-1 (pause hook), touch ready-file, then
    assert the SSE error frame + clean completion when the runner fires the resume."""
    start = time.monotonic()
    conn, resp = _open_stream_with_pause(args)
    first_at: float | None = None
    error_frame: dict | None = None
    saw_done = False
    deltas: list[str] = []
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:") :].strip()
        if first_at is None:
            first_at = time.monotonic()
            if args.ready_file:
                Path(args.ready_file).touch()
        if payload == "[DONE]":
            saw_done = True
            continue
        try:
            obj = json.loads(payload)
        except json.JSONDecodeError:
            continue
        if "error" in obj:
            error_frame = obj.get("error")
            continue
        for choice in obj.get("choices", []):
            d = choice.get("delta", {})
            if d.get("content"):
                deltas.append(d["content"])
    elapsed = time.monotonic() - start
    conn.close()
    assert first_at is not None, "midstream: no data frame arrived before the stream ended"
    assert error_frame is not None, "midstream: expected an SSE error frame after the kill"
    assert error_frame.get("type") == "api_error", (
        f"midstream: SSE error frame type {error_frame.get('type')!r} != api_error"
)
    assert not saw_done, "midstream: a killed stream must NOT terminate with a silent [DONE]"
    assert elapsed <= args.bound, f"midstream: completion took {elapsed:.2f}s (> {args.bound}s — hung?)"
    log(
        f"PASS midstream: error frame (api_error) + clean completion in {elapsed:.2f}s post-open "
        f"(bound {args.bound}s), {len(deltas)} deltas before the kill, no [DONE]"
)


def _open_stream_with_pause(args) -> tuple[http.client.HTTPConnection, object]:
    url = urllib.parse.urlsplit(args.base_url)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=60.0)
    body = json.dumps(
        {
            "model": "deepseek-v4-flash",
            "messages": [{"role": "user", "content": "mode=stream&pause=1 w24-midstream"}],
            "stream": True,
        }
)
    conn.request("POST", "/v1/chat/completions", body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    assert resp.status == 200, f"midstream: HTTP {resp.status} (errors go in SSE frames)"
    return conn, resp


def breaker_untripped(args) -> None:
    """Fake-1 restarted + modes reset: fresh streams succeed and fake-1 gets traffic
    immediately — the mid-stream failure must NOT have tripped the breaker."""
    c1, _ = [p.strip() for p in args.counters.split(",") if p.strip()]
    before = snapshot([c1])
    ok = 0
    for _ in range(4):
        conn, resp = _open_stream(args.base_url)
        deltas, done = _collect_stream(resp)
        conn.close()
        assert done and "".join(deltas) == FIXTURE_CONTENT, "breaker-untripped: stream failed after restart"
        ok += 1
    after = snapshot([c1])
    moves = delta(before, after)
    assert moves[c1] > 0, (
        f"breaker-untripped: fake-1 must receive traffic immediately after restart "
        f"(mid-stream failure is transient — no cooldown wait), deltas {moves}"
)
    log(f"PASS breaker-untripped: {ok}/4 streams ok, fake-1 served immediately after restart ({moves})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--counters", required=True)
    parser.add_argument("--phase", choices=["open-failover", "midstream", "breaker-untripped"], required=True)
    parser.add_argument("--ready-file", type=Path, default=None)
    parser.add_argument("--bound", type=float, default=5.0)
    args = parser.parse_args()

    if args.phase == "open-failover":
        open_failover(args)
    elif args.phase == "midstream":
        midstream(args)
    else:
        breaker_untripped(args)
    log(f"ALL PASS ({args.phase})")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
