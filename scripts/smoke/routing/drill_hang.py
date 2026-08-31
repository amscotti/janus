#!/usr/bin/env python3
"""drill_hang.py — deterministic hang drill for the routing gate (, step 5e).

Fake-1 is in mode-file ``hang`` (accept-then-close: 200 + headers + a partial
body/frame, then transport EOF). The adapter surfaces the EOF as a `network`
retryable failure, so the retry walk (config.fake.toml: max-retries = 2, backoff
100–500 ms deterministic) fails over to fake-2 within the bound — the client sees one
successful response, never a hang. A TRUE silent hang is bounded by the adapters' 60 s
request timeout (documented in RESULTS.md, not drilled to completion — the gate's
hang variant is the deterministic EOF one).

Asserts: all non-stream requests succeed via fake-2 (fake-1 counter grew — requests
reached it and were failed over), and one stream-open hang fails over to a full golden
SSE stream from fake-2.

Usage:
  drill_hang.py --base-url http://127.0.0.1:8080/v1 --counters <c1>,<c2>
                --mode-fake1 <mode1> [--bound 5]
"""
from __future__ import annotations

import argparse
import http.client
import json
import sys
import time
import urllib.parse
from pathlib import Path

from openai import OpenAI

from harness_common import delta, snapshot

FIXTURE_CONTENT = "The weather in Paris is 18 degrees with light rain."
DUMMY_KEY = "janus-smoke-dummy-key"


def log(msg: str) -> None:
    print(msg, flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--counters", required=True)
    parser.add_argument("--mode-fake1", type=Path, required=True)
    parser.add_argument("--bound", type=float, default=5.0)
    args = parser.parse_args()

    c1, c2 = [p.strip() for p in args.counters.split(",") if p.strip()]
    args.mode_fake1.write_text("hang", encoding="utf-8")
    client = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)

    # --- non-stream hang: every request succeeds (failover within the bound).
    before = snapshot([c1, c2])
    start = time.monotonic()
    for i in range(6):
        resp = client.chat.completions.create(
            model="deepseek-v4-flash",
            messages=[{"role": "user", "content": f"w24-hang-{i}"}],
)
        assert resp.choices[0].message.content == FIXTURE_CONTENT, "hang drill: golden content expected"
    elapsed = time.monotonic() - start
    after = snapshot([c1, c2])
    moves = delta(before, after)
    assert moves[c1] > 0, f"hang: requests must REACH fake-1 before failing over, deltas {moves}"
    assert moves[c2] > 0, f"hang: fake-2 must serve the failed-over requests, deltas {moves}"
    assert elapsed <= args.bound, f"hang: 6 requests took {elapsed:.2f}s (> {args.bound}s — hung?)"
    log(f"PASS hang (non-stream): {elapsed:.2f}s for 6 requests, all via failover ({moves})")

    # --- stream-open hang: full golden stream via fake-2.
    url = urllib.parse.urlsplit(args.base_url)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=30.0)
    body = json.dumps(
        {
            "model": "deepseek-v4-flash",
            "messages": [{"role": "user", "content": "mode=stream w24-hang-stream"}],
            "stream": True,
        }
)
    conn.request("POST", "/v1/chat/completions", body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    assert resp.status == 200, f"hang stream: HTTP {resp.status}"
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
        for choice in obj.get("choices", []):
            d = choice.get("delta", {})
            if d.get("content"):
                deltas.append(d["content"])
    conn.close()
    assert done, "hang stream: must terminate with [DONE]"
    assert "".join(deltas) == FIXTURE_CONTENT, "hang stream: golden content expected via failover"
    log("PASS hang (stream-open): full golden stream delivered via fake-2 failover")

    args.mode_fake1.write_text("nonstream", encoding="utf-8")
    log("ALL PASS (hang drill)")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
