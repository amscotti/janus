#!/usr/bin/env python3
"""drill_affinity.py — session-affinity stickiness drill.

Live proof that `session-affinity` is config-selectable end-to-end and behaves as
documented (docs/routing.md § Session affinity), against the offline fakes:

1. Stickiness — N sequential non-stream requests all carrying the SAME
   x-janus-session-id header: exactly ONE backend's upstream counter moves, by
   exactly N (rendezvous hash — same session id, same backend, every pick).
2. Sessionless spread — N requests with NO header: both counters move (the
   hardcoded round-robin fallback; sequential requests cycle deterministically).
3. Per-session exclusivity — two DIFFERENT session ids, K requests each: each
   session is served exclusively by one backend (whichever the hash picks — two
   sessions may legitimately share a backend; the pin is never mixing WITHIN one
   session id).

Modeled on drill_fairness.py (sequential here — stickiness is exact, so no
scheduler-noise bounds are needed). Offline: the fake upstreams on localhost are
the only peers.

Usage:
  drill_affinity.py --base-url http://127.0.0.1:8080/v1 --counters <c1>,<c2>
                    [--requests 20] [--sessions-requests 8]
"""
from __future__ import annotations

import argparse
import sys

from openai import OpenAI

from harness_common import delta, snapshot

DUMMY_KEY = "janus-smoke-dummy-key"
STICKY_SESSION = "ws1-affinity-sticky"
SESSION_A = "ws1-affinity-session-a"
SESSION_B = "ws1-affinity-session-b"


def _chat(client: OpenAI, session_id: str | None, tag: str):
    headers = {"x-janus-session-id": session_id} if session_id else {}
    return client.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": f"mode=nonstream {tag}"}],
        extra_headers=headers,
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--counters", required=True)
    parser.add_argument("--requests", type=int, default=20, help="per phase (stickiness + spread)")
    parser.add_argument("--sessions-requests", type=int, default=8, help="per session in phase 3")
    args = parser.parse_args()
    c1, c2 = [p.strip() for p in args.counters.split(",") if p.strip()]

    client = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)

    # ---------------------------------------------------------- 1. stickiness
    before = snapshot([c1, c2])
    for i in range(args.requests):
        completion = _chat(client, STICKY_SESSION, f"sticky-{i}")
        assert completion.choices, f"sticky request {i} returned no choices"
    sticky = delta(before, snapshot([c1, c2]))
    moved = [c for c in (c1, c2) if sticky[c] > 0]
    print(f"stickiness: {args.requests} same-session requests -> per_backend={sticky}")
    if len(moved) != 1 or sticky[moved[0]] != args.requests:
        print(
            f"FAIL: session stickiness — expected exactly one backend to serve all "
            f"{args.requests} requests, got {sticky}",
            file=sys.stderr,
)
        sys.exit(1)

    # ---------------------------------------------------- 2. sessionless spread
    before = snapshot([c1, c2])
    for i in range(args.requests):
        completion = _chat(client, None, f"sessionless-{i}")
        assert completion.choices, f"sessionless request {i} returned no choices"
    spread = delta(before, snapshot([c1, c2]))
    print(f"sessionless spread: {args.requests} no-header requests -> per_backend={spread}")
    if spread[c1] < 1 or spread[c2] < 1 or spread[c1] + spread[c2] != args.requests:
        print(
            f"FAIL: sessionless fallback — expected both backends to share the "
            f"{args.requests} requests (round-robin), got {spread}",
            file=sys.stderr,
)
        sys.exit(1)

    # ------------------------------------------------- 3. per-session exclusivity
    # Two DIFFERENT session ids, K sequential requests each: each session must be
    # served exclusively by ONE backend (whichever the hash picks — two sessions may
    # legitimately share a backend; the pin is never mixing WITHIN one session id).
    for session in (SESSION_A, SESSION_B):
        before = snapshot([c1, c2])
        for i in range(args.sessions_requests):
            completion = _chat(client, session, f"{session}-{i}")
            assert completion.choices, f"{session} request {i} returned no choices"
        exclusive = delta(before, snapshot([c1, c2]))
        movers = [c for c in (c1, c2) if exclusive[c] > 0]
        print(f"session {session}: {args.sessions_requests} requests -> per_backend={exclusive}")
        if len(movers) != 1 or exclusive[movers[0]] != args.sessions_requests:
            print(
                f"FAIL: session {session} must stick to exactly one backend "
                f"({args.sessions_requests} requests), got {exclusive}",
                file=sys.stderr,
)
            sys.exit(1)

    print(
        f"PASS: session-affinity — one session sticks to one backend "
        f"({sticky[moved[0]]}/{args.requests}), no header spreads "
        f"({spread[c1]}/{spread[c2]}), two distinct sessions each stick exclusively"
)


if __name__ == "__main__":
    main()
