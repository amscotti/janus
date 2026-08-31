#!/usr/bin/env python3
"""stress_streams.py — concurrent SSE stability drill for the cross-format gate —
the drill extended to BOTH faces.

Runs N concurrent streaming chat requests per face against a live Janus boot (the
unmodified ``openai`` SDK on the OpenAI face, the unmodified ``anthropic`` SDK on the
Anthropic face) and asserts:

  * every stream completes with >= 1 content delta and valid aggregated content
    (the SDKs raise on malformed chunks / missing termination: ``[DONE]`` on the
    OpenAI face, ``message_stop`` on the Anthropic face)
  * platform-thread count of the *Janus* process stays flat before/during/after —
    virtual threads (the per-stream model) do NOT appear in platform counts, so a
    per-stream platform-thread explosion would show up here (generous ceiling).

Usage:
  stress_streams.py --base-url http://127.0.0.1:8080/v1 --janus-pid <pid>
                    [--streams 50] [--thread-slack 64]
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import threading
import time

from anthropic import Anthropic as AnthropicClient
from openai import OpenAI

from sdk_common import sdk_base_url

DUMMY_KEY = "janus-smoke-dummy-key"
MAX_TOKENS = 1024


def platform_threads(pid: int) -> int:
    """OS/platform thread count of process ``pid`` (never virtual threads)."""
    out = subprocess.run(["ps", "-o", "nlwp=", "-p", str(pid)], capture_output=True, text=True)
    if out.returncode == 0 and out.stdout.strip():
        try:
            return int(out.stdout.strip().split()[0])
        except ValueError:
            pass
    out = subprocess.run(["ps", "-M", str(pid)], capture_output=True, text=True)
    if out.returncode == 0:
        lines = [line for line in out.stdout.splitlines() if line.strip()]
        return max(len(lines) - 1, 0)
    raise RuntimeError(f"cannot measure platform thread count for pid {pid}")


def _warmup(base_url: str) -> None:
    """Drain both streaming paths once so pooled platform threads exist before sampling."""
    oa = OpenAI(base_url=base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    # sdk_base_url: the anthropic SDK appends /v1/messages to base_url itself — a
    # /v1-prefixed gateway URL would hit /v1/v1/messages (404).
    an = AnthropicClient(base_url=sdk_base_url(base_url), api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    list(oa.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "mode=stream warmup-openai"}],
        stream=True,
))
    list(an.messages.create(
        model="deepseek-v4-flash",
        max_tokens=MAX_TOKENS,
        messages=[{"role": "user", "content": "mode=stream warmup-anthropic"}],
        stream=True,
))


def _openai_worker(idx: int, results: dict) -> None:
    client = OpenAI(base_url=_BASE, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    deltas: list[str] = []
    # m1 : stress BOTH upstreams on the OpenAI face — the oa direction
    # (Anthropic upstream → OpenAI face) is the most translation-heavy path and was
    # previously only smoke-tested. OpenAI-face workers get even idx: idx%4==0 → oo
    # (fake OpenAI-compat), idx%4==2 → oa (fake Anthropic).
    model = "deepseek-v4-flash" if idx % 4 == 0 else "claude-3-5-sonnet"
    try:
        stream = client.chat.completions.create(
            model=model,
            messages=[{"role": "user", "content": f"mode=stream stress-{idx}"}],
            stream=True,
)
        for chunk in stream:
            if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                deltas.append(chunk.choices[0].delta.content)
        results[idx] = ("ok", "".join(deltas)) if deltas else ("fail", "no content deltas")
    except Exception as e:  # noqa: BLE001 — recorded per stream for the summary
        results[idx] = ("fail", f"{type(e).__name__}: {e}")


def _anthropic_worker(idx: int, results: dict) -> None:
    # sdk_base_url: the anthropic SDK appends /v1/messages to base_url itself — a
    # /v1-prefixed gateway URL would hit /v1/v1/messages (404).
    client = AnthropicClient(base_url=sdk_base_url(_BASE), api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    deltas: list[str] = []
    stopped = False
    # m1 : stress BOTH upstreams on the Anthropic face too — Anthropic-face
    # workers get odd idx: idx%4==1 → ao (fake OpenAI-compat), idx%4==3 → aa (fake
    # Anthropic upstream) — so the aa cross-format direction is concurrency-stressed.
    model = "deepseek-v4-flash" if idx % 4 == 1 else "claude-3-5-sonnet"
    try:
        stream = client.messages.create(
            model=model,
            max_tokens=MAX_TOKENS,
            messages=[{"role": "user", "content": f"mode=stream stress-a-{idx}"}],
            stream=True,
)
        for event in stream:
            if event.type == "content_block_delta" and getattr(event.delta, "type", None) == "text_delta":
                deltas.append(event.delta.text)
            elif event.type == "message_stop":
                stopped = True
        if not stopped:
            results[idx] = ("fail", "no message_stop terminal")
        elif not deltas:
            results[idx] = ("fail", "no content deltas")
        else:
            results[idx] = ("ok", "".join(deltas))
    except Exception as e:  # noqa: BLE001
        results[idx] = ("fail", f"{type(e).__name__}: {e}")


_BASE = ""


def main() -> None:
    global _BASE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--janus-pid", type=int, required=True)
    parser.add_argument("--streams", type=int, default=50)
    parser.add_argument("--thread-slack", type=int, default=64, help="generous platform-thread ceiling")
    parser.add_argument("--start-timeout", type=float, default=30.0)
    parser.add_argument("--settle", type=float, default=1.0)
    args = parser.parse_args()
    _BASE = args.base_url

    # Warm-up: pooled platform threads exist before the baseline sample.
    _warmup(args.base_url)

    threads_before = platform_threads(args.janus_pid)

    results: dict = {}
    threads: list[threading.Thread] = []
    for i in range(args.streams):
        if i % 2 == 0:
            t = threading.Thread(target=_openai_worker, args=(i, results), name=f"stress-oi-{i}")
        else:
            t = threading.Thread(target=_anthropic_worker, args=(i, results), name=f"stress-ai-{i}")
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
        print(
            f"PASS: {args.streams} concurrent SSE streams (both faces) stable; "
            f"platform threads flat (slack {args.thread_slack})"
)
        sys.exit(0)

    print("FAILURES:", *failures, sep="\n  ", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
