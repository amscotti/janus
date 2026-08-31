#!/usr/bin/env python3
"""drill_health.py — live passive-health cycle + all-upstreams-down for the stage 3
gate (, + Review 3).

Boot: config.health.toml (max-retries = 0 → single-attempt determinism, allowed-fails
= 2 → flips unhealthy fast, cooldown 3 SECONDS, breaker-failure-threshold = 100 → the
breaker stays OUT of the way; only the soft health filter acts).

  --phase health-cycle:
    1. Two failures on fake-1 (mode-file 500) → unhealthy → during the cooldown every
       request succeeds via fake-2 and fake-1's counter is FLAT (soft-excluded).
    2. Cooldown elapses → fake-1 flipped healthy → the trial attempt succeeds →
       recovered → traffic resumes to both backends.
  --phase all-down (Review 3 — all-upstreams-down): the runner has killed BOTH fakes;
       one request → 502 api_error envelope within the bound, no hang, no exception
       storm (the fail-open semantics — health fail-open + breaker fail-open
       single-probe — are recorded from the Janus log by the runner).

Usage:
  drill_health.py --base-url http://127.0.0.1:8080/v1 --counters <c1>,<c2>
                  --mode-fake1 <mode1> --mode-fake2 <mode2> [--cooldown 3]
                  --phase health-cycle|all-down [--bound 5]
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
    resp = client.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "w24-health-drill"}],
)
    assert resp.choices[0].message.content, "health drill: blank content on success"
    return "ok"


def send_until(client: OpenAI, counter_path: str, target: int, cap: int = 20) -> None:
    for _ in range(cap):
        before = read_counter(counter_path)["requests"]
        try:
            one_request(client)
        except APIStatusError as e:
            assert e.status_code == 502, f"health drill: unexpected status {e.status_code}"
        if read_counter(counter_path)["requests"] > before:
            if read_counter(counter_path)["requests"] >= target:
                return
    raise AssertionError(f"fake-1 counter never reached {target} (cap {cap})")


def health_cycle(args) -> None:
    c1, c2 = [p.strip() for p in args.counters.split(",") if p.strip()]
    client = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    baseline = read_counter(c1)["requests"]  # the fakes persist across JVM boots — deltas only

    args.mode_fake2.write_text("nonstream", encoding="utf-8")
    args.mode_fake1.write_text("500", encoding="utf-8")
    send_until(client, c1, baseline + 2)  # 2 failures on fake-1 → unhealthy (allowed-fails = 2)
    assert read_counter(c1)["requests"] == baseline + 2, (
        "health must flip unhealthy after exactly 2 failures on fake-1 "
        f"(counter {read_counter(c1)['requests']}, baseline {baseline})"
)

    flat_before = snapshot([c1, c2])
    for _ in range(6):
        one_request(client)
    flat_after = snapshot([c1, c2])
    moves = delta(flat_before, flat_after)
    assert moves[c2] == 6 and moves[c1] == 0, (
        f"unhealthy: during cooldown fake-1 must be soft-excluded (flat), all via fake-2: {moves}"
)
    log(f"PASS unhealthy: fake-1 excluded during cooldown — 6/6 via fake-2, fake-1 flat ({moves})")

    time.sleep(args.cooldown + 1.5)  # probation deadline passes
    args.mode_fake1.write_text("nonstream", encoding="utf-8")  # flip healthy for the trial
    recover_before = snapshot([c1, c2])
    for _ in range(12):
        before = read_counter(c1)["requests"]
        try:
            one_request(client)
        except APIStatusError as e:
            raise AssertionError(f"recover: trial request failed unexpectedly: status {e.status_code}") from e
        if read_counter(c1)["requests"] > before:
            break
    assert read_counter(c1)["requests"] > baseline + 2, "recover: the trial attempt must reach fake-1"
    resumed_before = snapshot([c1, c2])
    for _ in range(6):
        one_request(client)
    resumed_after = snapshot([c1, c2])
    moves = delta(resumed_before, resumed_after)
    assert moves[c1] > 0 and moves[c2] > 0, f"recover: traffic must resume to both, deltas {moves}"
    log(f"PASS recovered: trial succeeded → healthy → traffic resumed to both ({moves})")
    log("ALL PASS (health cycle: unhealthy → cooldown flat → trial → recovered)")


def all_down(args) -> None:
    """Both fakes dead (runner killed them): 502 api_error within the bound, no hang."""
    client = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    start = time.monotonic()
    try:
        one_request(client)
        raise AssertionError("all-down: expected APIStatusError (both upstreams dead)")
    except APIStatusError as e:
        elapsed = time.monotonic() - start
        assert e.status_code == 502, f"all-down: status {e.status_code} != 502"
        err = e.response.json()["error"]
        assert err["type"] == "api_error", f"all-down: envelope type {err['type']!r} != api_error"
        assert elapsed <= args.bound, f"all-down took {elapsed:.2f}s (> {args.bound}s bound)"
        log(f"PASS all-down: 502 api_error envelope in {elapsed:.2f}s (bound {args.bound}s), no hang")
    log("ALL PASS (all-upstreams-down)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--counters", required=True)
    parser.add_argument("--mode-fake1", type=Path, required=True)
    parser.add_argument("--mode-fake2", type=Path, required=True)
    parser.add_argument("--cooldown", type=float, default=3.0)
    parser.add_argument("--phase", choices=["health-cycle", "all-down"], default="health-cycle")
    parser.add_argument("--bound", type=float, default=5.0)
    args = parser.parse_args()
    if args.phase == "all-down":
        all_down(args)
    else:
        health_cycle(args)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
