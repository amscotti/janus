#!/usr/bin/env python3
"""drill_breaker.py — live circuit-breaker cycle for the routing gate (, gate 3).

Boot: config.breaker.toml (max-retries = 0 → single-attempt determinism, allowed-fails
= 100 → passive health OUT of the way, breaker-failure-threshold = 2, cooldown 5s).
Fake-1's failure is driven via its MODE-FILE (500) — never by killing it — so its
request counter stays readable and the "refused while OPEN" assertion is a flat
counter, not an absent process.

Cycle (all assertions live over real sockets):
  1. Two failures on fake-1 → breaker OPEN. While OPEN, subsequent requests never
     reach fake-1 (counter flat) and all succeed via fake-2 (zero client-visible
     errors).
  2. Cooldown elapses → HALF_OPEN → EXACTLY ONE probe to fake-1 (counter +1), which
     fails (fake-1 still 500) → re-OPEN with a fresh cooldown (counter flat again).
  3. Fake-1 flipped healthy → cooldown elapses → the probe succeeds → CLOSED →
     traffic resumes to both backends (counter grows again).

The claim-at-dispatch discipline ( C1) is unit-pinned by the regression pair in
./gradlew build; this drill proves the single-OPEN live cycle end-to-end.

Usage:
  drill_breaker.py --base-url http://127.0.0.1:8080/v1
                   --counters <fake1-counter>,<fake2-counter>
                   --mode-fake1 <fake1-mode-file> --mode-fake2 <fake2-mode-file>
                   [--cooldown 5] [--phase breaker-cycle|exhaustion]

  --phase exhaustion  (plan step 5d — runs on the breaker boot too): BOTH fakes
                   mode-file=500, max-retries=0 → a request gets the 502 server_error
                   envelope within the bound, no hang (the single-attempt exhaustion
                   path; the suppressed chain is unit-pinned, RouterResilientTest).
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

from openai import APIStatusError, OpenAI

from harness_common import delta, read_counter, snapshot

DUMMY_KEY = "janus-smoke-dummy-key"


def log(msg: str) -> None:
    print(msg, flush=True)


def one_request(client: OpenAI) -> str:
    """One non-stream request. Returns 'ok' or raises on failure."""
    resp = client.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "w24-breaker-drill"}],
)
    assert resp.choices[0].message.content, "breaker drill: blank content on success"
    return "ok"


def send_until(client: OpenAI, counter_path: str, target: int, cap: int = 20) -> None:
    """Send requests until fake-1's request counter reaches ``target`` (failures on
    fake-1 are expected and swallowed as 502 envelopes; successes are not)."""
    for _ in range(cap):
        before = read_counter(counter_path)["requests"]
        try:
            one_request(client)
        except APIStatusError as e:
            assert e.status_code == 502, f"breaker drill: unexpected status {e.status_code}"
        if read_counter(counter_path)["requests"] > before:
            if read_counter(counter_path)["requests"] >= target:
                return
    raise AssertionError(f"fake-1 counter never reached {target} (cap {cap})")


def send_expect_all_ok(client: OpenAI, n: int) -> None:
    """Send n requests; every one must succeed (zero client-visible errors)."""
    for _ in range(n):
        one_request(client)


def exhaustion(args) -> None:
    """Both fakes 500, max-retries=0 (breaker boot): 502 server_error within the
    bound, no hang — the single-attempt exhaustion path."""
    args.mode_fake1.write_text("500", encoding="utf-8")
    args.mode_fake2.write_text("500", encoding="utf-8")
    client = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    start = time.monotonic()
    try:
        client.chat.completions.create(
            model="deepseek-v4-flash",
            messages=[{"role": "user", "content": "w24-exhaustion"}],
)
        raise AssertionError("exhaustion: expected APIStatusError (both upstreams 500)")
    except APIStatusError as e:
        elapsed = time.monotonic() - start
        assert e.status_code == 502, f"exhaustion: status {e.status_code} != 502"
        err = e.response.json()["error"]
        assert err["type"] == "server_error", f"exhaustion: envelope type {err['type']!r} != server_error"
        assert elapsed <= args.bound, f"exhaustion took {elapsed:.2f}s (> {args.bound}s bound)"
        log(f"PASS exhaustion: 502 server_error envelope in {elapsed:.2f}s (bound {args.bound}s), no hang")
    args.mode_fake1.write_text("nonstream", encoding="utf-8")
    args.mode_fake2.write_text("nonstream", encoding="utf-8")
    log("ALL PASS (exhaustion)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--counters", required=True, help="fake1-counter,fake2-counter")
    parser.add_argument("--mode-fake1", type=Path, required=True)
    parser.add_argument("--mode-fake2", type=Path, required=True)
    parser.add_argument("--cooldown", type=float, default=5.0)
    parser.add_argument("--phase", choices=["breaker-cycle", "exhaustion"], default="breaker-cycle")
    parser.add_argument("--bound", type=float, default=5.0)
    args = parser.parse_args()

    if args.phase == "exhaustion":
        exhaustion(args)
        return

    c1, c2 = [p.strip() for p in args.counters.split(",") if p.strip()]
    client = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    baseline = read_counter(c1)["requests"]  # the fakes persist across JVM boots — deltas only

    # --- phase 1: drive fake-1 to OPEN, prove refusal + zero client-visible errors.
    args.mode_fake2.write_text("nonstream", encoding="utf-8")
    args.mode_fake1.write_text("500", encoding="utf-8")
    before_open = snapshot([c1, c2])
    send_until(client, c1, baseline + 2)  # 2 failures on fake-1 → OPEN
    assert read_counter(c1)["requests"] == baseline + 2, (
        "breaker must OPEN after exactly 2 failures on fake-1 "
        f"(counter {read_counter(c1)['requests']}, baseline {baseline})"
)
    refused_before = snapshot([c1, c2])
    send_expect_all_ok(client, 6)
    refused_after = snapshot([c1, c2])
    moves = delta(refused_before, refused_after)
    assert moves[c2] == 6, f"OPEN: fake-2 must serve every request while fake-1 is refused, deltas {moves}"
    assert moves[c1] == 0, f"OPEN: fake-1 counter must stay FLAT (refused at dispatch), deltas {moves}"
    log(f"PASS open: fake-1 OPEN after 2 failures — 6/6 succeed via fake-2, fake-1 refused (flat {moves})")

    # --- phase 2: cooldown → HALF_OPEN → exactly one probe → probe fails → re-OPEN.
    time.sleep(args.cooldown + 1.5)
    half_open_before = snapshot([c1, c2])
    for _ in range(12):
        probe_before = read_counter(c1)["requests"]
        try:
            one_request(client)
        except APIStatusError as e:
            assert e.status_code == 502, f"half-open: unexpected status {e.status_code}"
        if read_counter(c1)["requests"] > probe_before:
            break
    assert read_counter(c1)["requests"] == baseline + 3, (
        f"half-open: exactly ONE probe must reach fake-1 "
        f"(counter {read_counter(c1)['requests']}, expected {baseline + 3})"
)
    re_open_before = snapshot([c1, c2])
    send_expect_all_ok(client, 6)
    re_open_after = snapshot([c1, c2])
    moves = delta(re_open_before, re_open_after)
    assert moves[c1] == 0 and moves[c2] == 6, (
        f"probe failure must re-OPEN with a fresh cooldown (flat fake-1, all via fake-2): {moves}"
)
    log(f"PASS half-open: exactly one probe (counter 2→3) failed → re-OPEN, fake-1 flat, 6/6 via fake-2")

    # --- phase 3: flip fake-1 healthy → cooldown → probe succeeds → CLOSED → both move.
    args.mode_fake1.write_text("nonstream", encoding="utf-8")
    time.sleep(args.cooldown + 1.5)
    recover_before = snapshot([c1, c2])
    for _ in range(12):
        probe_before = read_counter(c1)["requests"]
        try:
            one_request(client)
        except APIStatusError as e:
            raise AssertionError(f"recover: probe request failed unexpectedly: status {e.status_code}") from e
        if read_counter(c1)["requests"] > probe_before:
            break
    assert read_counter(c1)["requests"] > baseline + 3, "recover: probe must reach fake-1 and succeed"
    resumed_before = snapshot([c1, c2])
    send_expect_all_ok(client, 6)
    resumed_after = snapshot([c1, c2])
    moves = delta(resumed_before, resumed_after)
    assert moves[c1] > 0 and moves[c2] > 0, (
        f"recover: CLOSED → traffic must resume to BOTH backends, deltas {moves}"
)
    log(f"PASS recover: probe succeeded → CLOSED → traffic resumed to both ({moves})")
    log("ALL PASS (breaker cycle: OPEN → HALF_OPEN(1 probe) → re-OPEN → CLOSED)")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
