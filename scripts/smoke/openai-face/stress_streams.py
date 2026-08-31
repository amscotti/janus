#!/usr/bin/env python3
"""stress_streams.py — concurrent SSE stability drill for the OpenAI-face gate.

Runs N concurrent streaming chat requests (one ``openai`` SDK client per thread —
the unmodified-SDK proof extended to load) against a live Janus boot and asserts:

  * every stream completes with >= 1 content delta and valid aggregated content
    (the SDK raises on malformed chunks / missing [DONE] termination)
  * platform-thread count of the *Janus* process stays flat before/during/after —
 virtual threads (the per-stream model) do NOT appear in platform counts, so
    a per-stream platform-thread explosion would show up here. The assertion is a
    generous ceiling (not per-stream growth); raw numbers are printed for RESULTS.md.

Platform-thread measurement: ``ps -o nlwp=`` (Linux) with ``ps -M`` (macOS/BSD)
fallback — both count only OS/platform threads, never virtual threads.

Usage:
  stress_streams.py --base-url http://127.0.0.1:8080/v1 --janus-pid <pid>
                    [--streams 50] [--model deepseek-v4-flash] [--thread-slack 64]
                    [--start-timeout 30]
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import threading
import time

from openai import OpenAI

DUMMY_KEY = "janus-smoke-dummy-key"


def platform_threads(pid: int) -> int:
    """OS/platform thread count of process ``pid`` (never virtual threads)."""
    out = subprocess.run(["ps", "-o", "nlwp=", "-p", str(pid)], capture_output=True, text=True)
    if out.returncode == 0 and out.stdout.strip():
        try:
            return int(out.stdout.strip().split()[0])
        except ValueError:
            pass
    # macOS/BSD: `ps -M <pid>` prints one line per thread after a header line.
    out = subprocess.run(["ps", "-M", str(pid)], capture_output=True, text=True)
    if out.returncode == 0:
        lines = [line for line in out.stdout.splitlines() if line.strip()]
        return max(len(lines) - 1, 0)
    raise RuntimeError(f"cannot measure platform thread count for pid {pid}")


def _warmup(base_url: str, model: str, streams: int) -> None:
    """Drain the streaming path once so pooled platform threads exist before sampling."""
    client = OpenAI(base_url=base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    threads: list[threading.Thread] = []
    for i in range(streams):
        t = threading.Thread(
            target=lambda: list(client.chat.completions.create(
                model=model,
                messages=[{"role": "user", "content": f"mode=stream warmup-{i}"}],
                stream=True,
)),
            name=f"warmup-{i}",
)
        t.start()
        threads.append(t)
    for t in threads:
        t.join(timeout=30.0)


def worker(client: OpenAI, model: str, idx: int, started: threading.Event, results: dict) -> None:
    deltas: list[str] = []
    try:
        stream = client.chat.completions.create(
            model=model,
            # "mode=stream" is the fake-upstream marker (same as smoke_sdk) — without it
            # the fake serves the non-stream JSON and Janus sees an empty SSE stream.
            messages=[{"role": "user", "content": f"mode=stream stress-{idx}"}],
            stream=True,
)
        for chunk in stream:
            if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                deltas.append(chunk.choices[0].delta.content)
                started.set()
        if not deltas:
            results[idx] = ("fail", "no content deltas")
            return
        results[idx] = ("ok", "".join(deltas))
    except Exception as e:  # noqa: BLE001 — recorded per stream for the summary
        results[idx] = ("fail", f"{type(e).__name__}: {e}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--janus-pid", type=int, required=True)
    parser.add_argument("--model", default="deepseek-v4-flash")
    parser.add_argument("--streams", type=int, default=50)
    parser.add_argument("--thread-slack", type=int, default=64, help="generous platform-thread ceiling")
    parser.add_argument("--start-timeout", type=float, default=30.0)
    parser.add_argument("--settle", type=float, default=1.0, help="post-run settle before the after-sample")
    args = parser.parse_args()

    # Warm-up: Micronaut's blocking executor grows its (cached, pooled) platform-thread
    # set on first demand — a fresh boot shows +~40 threads after the first 50-stream
    # burst even though they are pooled and stable afterward (verified: runs 2+ are
    # flat). Warm the pool first so the baseline sample measures steady state, not
    # pool warm-up.
    _warmup(args.base_url, args.model, streams=5)

    threads_before = platform_threads(args.janus_pid)

    started = threading.Event()
    results: dict = {}
    threads: list[threading.Thread] = []
    for i in range(args.streams):
        client = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
        t = threading.Thread(target=worker, args=(client, args.model, i, started, results), name=f"stress-{i}")
        threads.append(t)

    t0 = time.monotonic()
    for t in threads:
        t.start()
    if not started.wait(args.start_timeout):
        print(f"FAIL: no stream produced a first delta within {args.start_timeout}s", file=sys.stderr)
        sys.exit(1)

    threads_during = platform_threads(args.janus_pid)

    failures: list[str] = []
    for t in threads:
        t.join(timeout=args.start_timeout)
        if t.is_alive():
            failures.append(f"thread {t.name} did not finish within {args.start_timeout}s")
    elapsed = time.monotonic() - t0

    time.sleep(args.settle)
    threads_after = platform_threads(args.janus_pid)

    for idx, (status, detail) in sorted(results.items()):
        if status != "ok":
            failures.append(f"stream {idx}: {detail}")

    ok = not failures
    print(
        f"stress result: streams={args.streams} ok={sum(1 for s, _ in results.values() if s == 'ok')} "
        f"failures={len(failures)} elapsed={elapsed:.2f}s "
        f"janus_platform_threads before={threads_before} during={threads_during} after={threads_after}",
        flush=True,
)
    if ok:
        during_growth = threads_during - threads_before
        after_growth = threads_after - threads_before
        if during_growth > args.thread_slack:
            print(
                f"FAIL: platform thread growth during {during_growth} > slack {args.thread_slack} "
                f"(per-stream platform-thread explosion)", file=sys.stderr
)
            sys.exit(1)
        if after_growth > args.thread_slack // 2:
            print(
                f"FAIL: platform thread growth after {after_growth} > {args.thread_slack // 2} "
                f"(threads not released)", file=sys.stderr
)
            sys.exit(1)
        print(f"PASS: {args.streams} concurrent SSE streams stable; platform threads flat (slack {args.thread_slack})")
        sys.exit(0)

    print("FAILURES:", *failures, sep="\n  ", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
