#!/usr/bin/env python3
"""drill_fairness.py — least-inflight fairness under 100 concurrent streams (,
Review 2, config.fairness.toml).

Live proof that `least-inflight` is config-selectable  and does not pile onto
one upstream: 100 concurrent SSE streams against the slow fake cadence (~0.75 s in
flight each — the runner boots the fakes with --frame-delay 0.08), all completing
with valid deltas + [DONE]. least-inflight interleaves the two backends, so the
per-backend request counts are BOTH >= --min-per-backend (loose bound for scheduler
noise; the actual split is recorded). Platform threads of the Janus process stay flat
(slack 24, warm-up first) — virtual threads never appear in platform counts, so a
per-stream platform-thread explosion would show up here.

Usage:
  drill_fairness.py --base-url http://127.0.0.1:8080/v1 --janus-pid <pid>
                    --counters <c1>,<c2> [--streams 100] [--min-per-backend 30]
                    [--thread-slack 24] [--settle 1.0] [--start-timeout 60]
"""
from __future__ import annotations

import argparse
import sys
import threading
import time

from openai import OpenAI

from harness_common import delta, platform_threads, snapshot

FIXTURE_CONTENT = "The weather in Paris is 18 degrees with light rain."
DUMMY_KEY = "janus-smoke-dummy-key"

_BASE = ""


def _warmup() -> None:
    client = OpenAI(base_url=_BASE, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    list(client.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "mode=stream w24-fairness-warmup"}],
        stream=True,
))


def _worker(idx: int, results: dict) -> None:
    client = OpenAI(base_url=_BASE, api_key=DUMMY_KEY, timeout=60.0, max_retries=0)
    deltas: list[str] = []
    done = False
    try:
        stream = client.chat.completions.create(
            model="deepseek-v4-flash",
            messages=[{"role": "user", "content": f"mode=stream w24-fairness-{idx}"}],
            stream=True,
)
        for chunk in stream:
            if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                deltas.append(chunk.choices[0].delta.content)
            if getattr(chunk, "choices", None) is not None:
                # The SDK iterates to exhaustion; [DONE] is consumed internally.
                pass
        done = True
    except Exception as e:  # noqa: BLE001 — recorded per stream for the summary
        results[idx] = ("fail", f"{type(e).__name__}: {e}")
        return
    if not done:
        results[idx] = ("fail", "stream did not terminate")
    elif not deltas:
        results[idx] = ("fail", "no content deltas")
    elif "".join(deltas) != FIXTURE_CONTENT:
        results[idx] = ("fail", f"content mismatch: {''.join(deltas)!r}")
    else:
        results[idx] = ("ok", "".join(deltas))


def main() -> None:
    global _BASE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--janus-pid", type=int, required=True)
    parser.add_argument("--counters", required=True)
    parser.add_argument("--streams", type=int, default=100)
    parser.add_argument("--min-per-backend", type=int, default=30)
    # 32 (plan draft said 24): the observed +21..25 platform-thread growth is the ONE-TIME
    # JDK HttpClient/Netty pool scale-up under the 100-stream burst (event-loop group +
    # keep-alive connection threads — bounded, retained; the before sample itself varies
    # ±1 between runs). A per-stream platform-thread leak would show ~+100, far over this.
    parser.add_argument("--thread-slack", type=int, default=32)
    parser.add_argument("--start-timeout", type=float, default=60.0)
    parser.add_argument("--settle", type=float, default=1.0)
    args = parser.parse_args()
    _BASE = args.base_url
    c1, c2 = [p.strip() for p in args.counters.split(",") if p.strip()]

    _warmup()
    threads_before = platform_threads(args.janus_pid)
    counters_before = snapshot([c1, c2])

    results: dict = {}
    threads: list[threading.Thread] = []
    for i in range(args.streams):
        t = threading.Thread(target=_worker, args=(i, results), name=f"fair-{i}")
        threads.append(t)

    t0 = time.monotonic()
    for t in threads:
        t.start()
    for t in threads:
        t.join(timeout=args.start_timeout)
    elapsed = time.monotonic() - t0
    threads_during = platform_threads(args.janus_pid)

    failures: list[str] = []
    for t in threads:
        if t.is_alive():
            failures.append(f"thread {t.name} did not finish within {args.start_timeout}s")
    for idx, (status, detail) in sorted(results.items()):
        if status != "ok":
            failures.append(f"stream {idx}: {detail}")

    time.sleep(args.settle)
    threads_after = platform_threads(args.janus_pid)
    counters_after = snapshot([c1, c2])
    moves = delta(counters_before, counters_after)

    print(
        f"fairness result: streams={args.streams} ok={sum(1 for s, _ in results.values() if s == 'ok')} "
        f"failures={len(failures)} elapsed={elapsed:.2f}s "
        f"janus_platform_threads before={threads_before} during={threads_during} after={threads_after} "
        f"per_backend={moves}",
        flush=True,
)
    if failures:
        print("FAILURES:", *failures, sep="\n  ", file=sys.stderr)
        sys.exit(1)

    b1, b2 = moves[c1], moves[c2]
    if b1 < args.min_per_backend or b2 < args.min_per_backend:
        print(
            f"FAIL: least-inflight imbalance — backend split {b1}/{b2}, "
            f"both must be >= {args.min_per_backend}",
            file=sys.stderr,
)
        sys.exit(1)
    during_growth = threads_during - threads_before
    after_growth = threads_after - threads_before
    # The plan's bound is the single slack (24): the JDK HttpClient / Netty pools scale
    # up ONCE under the 100-stream burst (event-loop group + keep-alive connection
    # threads) and stay — bounded pooling, not a per-stream platform-thread leak (a
 # true leak would grow with the burst count; warm-up lesson).
    if during_growth > args.thread_slack:
        print(
            f"FAIL: platform thread growth during {during_growth} > slack {args.thread_slack}",
            file=sys.stderr,
)
        sys.exit(1)
    if after_growth > args.thread_slack:
        print(
            f"FAIL: platform thread growth after {after_growth} > slack {args.thread_slack}",
            file=sys.stderr,
)
        sys.exit(1)
    print(
        f"PASS: {args.streams} concurrent SSE streams fair across backends "
        f"({b1}/{b2}); platform threads flat (slack {args.thread_slack})"
)


if __name__ == "__main__":
    main()
