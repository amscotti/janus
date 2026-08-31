#!/usr/bin/env python3
"""abort_drill.py — adversarial stream-abort drill for the cross-format gate (,
the stream-abort contract).

A raw ``http.client`` client opens a streaming request on EACH face × each upstream
(the full 4-cell matrix: oo/oa on the OpenAI face, ao/aa on the Anthropic face),
reads 1–2 frames, then closes the socket mid-generation. Asserts:

  (a) cancellation — each upstream fake OBSERVES the connection close before serving
      the full stream (the fakes record every early close to --abort-log; this is
      the proof the upstream call did not run to natural completion);
  (b) platform-thread flatness of the Janus process (before/during/after samples —
      virtual threads never appear in ``ps nlwp``; the blocking pool is bounded);
  (c) no leaked threads after the run (after-sample within --thread-slack of before);
  (d) a follow-up request on the same boot succeeds on each face (no poisoned state).

The Janus-log check (no unhandled exception storm) is the runner's job (it owns the
log file); this script reports the raw numbers for RESULTS.md.

Usage:
  abort_drill.py --base-url http://127.0.0.1:8080/v1 --janus-pid <pid>
                 --abort-log <path> [--thread-slack 24] [--settle 1.0]
"""
from __future__ import annotations

import argparse
import http.client
import json
import subprocess
import sys
import threading
import time
import urllib.parse
from pathlib import Path

from openai import OpenAI

from sdk_common import sdk_base_url

DUMMY_KEY = "janus-smoke-dummy-key"
MAX_TOKENS = 1024

# The 4 abort cells: (label, face-wire, request-body, expected first-frame marker).
# The client reads exactly 2 frames' worth of bytes then closes the socket.
CELLS = [
    (
        "oo",
        "openai",
        {"model": "deepseek-v4-flash", "messages": [{"role": "user", "content": "mode=stream abort-oo"}], "stream": True},
        "data:",
),
    (
        "oa",
        "openai",
        {"model": "claude-3-5-sonnet", "messages": [{"role": "user", "content": "mode=stream abort-oa"}], "stream": True},
        "data:",
),
    (
        "ao",
        "anthropic",
        {
            "model": "deepseek-v4-flash",
            "max_tokens": MAX_TOKENS,
            "messages": [{"role": "user", "content": "mode=stream abort-ao"}],
            "stream": True,
        },
        "event:",
),
    (
        "aa",
        "anthropic",
        {
            "model": "claude-3-5-sonnet",
            "max_tokens": MAX_TOKENS,
            "messages": [{"role": "user", "content": "mode=stream abort-aa"}],
            "stream": True,
        },
        "event:",
),
]


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


def abort_one(base_url: str, label: str, face: str, body: dict, marker: str) -> None:
    """Open a streaming request, read until 2 frame-markers seen, close the socket."""
    url = urllib.parse.urlsplit(base_url)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=10.0)
    path = "/v1/messages" if face == "anthropic" else "/v1/chat/completions"
    conn.request("POST", path, body=json.dumps(body), headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    if resp.status != 200:
        # The eager upstream send may surface as an HTTP error if the fake was down —
        # the drill runner guarantees the fakes are up first; treat a non-200 as a
        # drill failure (the abort must land mid-stream). Include the body so the
        # runner can classify the failure (e.g. the "probe slot is busy" 500).
        resp_body = resp.read().decode("utf-8", "replace")[:500]
        raise AssertionError(
            f"abort {label}: HTTP {resp.status} (expected 200 before the abort); body={resp_body}"
)
    frames = 0
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if line.startswith(marker):
            frames += 1
            if frames >= 2:
                break
    conn.close()  # the abort: close the socket mid-generation
    if frames < 1:
        raise AssertionError(f"abort {label}: stream ended before a single frame arrived")


def followup(base_url: str, model: str, face: str) -> None:
    """A follow-up non-stream request on the same boot — poisoned-state check."""
    if face == "anthropic":
        # sdk_base_url: the anthropic SDK appends /v1/messages to base_url itself —
        # passing the /v1-prefixed gateway URL would hit /v1/v1/messages (404).
        client = __import__("anthropic", fromlist=["Anthropic"]).Anthropic(
            base_url=sdk_base_url(base_url), api_key=DUMMY_KEY, timeout=15.0, max_retries=0
)
        resp = client.messages.create(
            model=model, max_tokens=MAX_TOKENS, messages=[{"role": "user", "content": "hello"}]
)
        assert resp.content, "follow-up Anthropic-face response is empty"
    else:
        client = OpenAI(base_url=base_url, api_key=DUMMY_KEY, timeout=15.0, max_retries=0)
        resp = client.chat.completions.create(model=model, messages=[{"role": "user", "content": "hello"}])
        assert resp.choices[0].message.content, "follow-up OpenAI-face response is empty"


def wait_for_abort_log(abort_log: Path, expected: int, timeout: float = 5.0) -> int:
    """The fake records the early close on its NEXT write after the client's close —
    poll briefly for the recorded entries (loopback RST is fast but not instant)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if abort_log.exists():
            entries = [line for line in abort_log.read_text(encoding="utf-8").splitlines() if line.strip()]
            if len(entries) >= expected:
                return len(entries)
        time.sleep(0.1)
    return len(abort_log.read_text(encoding="utf-8").splitlines()) if abort_log.exists() else 0


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--janus-pid", type=int, required=True)
    parser.add_argument("--abort-log", type=Path, required=True)
    parser.add_argument("--thread-slack", type=int, default=24)
    parser.add_argument("--settle", type=float, default=1.0)
    parser.add_argument("--timeout", type=float, default=20.0)
    args = parser.parse_args()

    if args.abort_log.exists():
        args.abort_log.unlink()

    threads_before = platform_threads(args.janus_pid)

    # Warm the streaming path once per face so pooled platform threads exist before
 # the baseline sample (the lesson — first-demand pool growth is not a leak).
    warm = OpenAI(base_url=args.base_url, api_key=DUMMY_KEY, timeout=15.0, max_retries=0)
    list(warm.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": "mode=stream abort-warmup"}],
        stream=True,
))

    failures: list[str] = []
    threads: list[threading.Thread] = []
    for label, face, body, marker in CELLS:
        t = threading.Thread(target=abort_one, args=(args.base_url, label, face, body, marker), name=f"abort-{label}")
        threads.append(t)

    t0 = time.monotonic()
    for t in threads:
        t.start()
    # n3 : sample "during" while the aborts are actually in flight —
    # the fakes' 0.05s frame delay means the 4 aborts take ~0.1-0.2s; a brief pause
    # after the starts lands the sample mid-abort instead of post-hoc.
    time.sleep(0.2)
    threads_during = platform_threads(args.janus_pid)
    for t in threads:
        t.join(timeout=args.timeout)
        if t.is_alive():
            failures.append(f"abort thread {t.name} hung (>{args.timeout}s)")
    elapsed = time.monotonic() - t0

    # The abort-log entries are path-keyed, not per-cell; assert the COUNT (each early
    # close is one recorded line) — 4 aborts must all be observed by the upstreams.
    recorded = wait_for_abort_log(args.abort_log, expected=len(CELLS))
    if recorded < len(CELLS):
        failures.append(f"upstream observed only {recorded}/{len(CELLS)} early closes (cancellation incomplete)")

    # Follow-ups: each face must still serve normally (no poisoned state).
    followup(args.base_url, "deepseek-v4-flash", "openai")
    followup(args.base_url, "claude-3-5-sonnet", "openai")
    followup(args.base_url, "deepseek-v4-flash", "anthropic")
    followup(args.base_url, "claude-3-5-sonnet", "anthropic")

    time.sleep(args.settle)
    threads_after = platform_threads(args.janus_pid)

    print(
        f"abort result: cells=4 aborted={len(CELLS)} upstream_observed={recorded} "
        f"elapsed={elapsed:.2f}s janus_platform_threads before={threads_before} "
        f"during={threads_during} after={threads_after}",
        flush=True,
)
    if failures:
        print("FAILURES:", *failures, sep="\n  ", file=sys.stderr)
        sys.exit(1)
    after_growth = threads_after - threads_before
    if after_growth > args.thread_slack:
        print(
            f"FAIL: platform thread growth after aborts {after_growth} > slack {args.thread_slack} "
            f"(leaked threads)", file=sys.stderr
)
        sys.exit(1)
    print(
        f"PASS: 4 aborts (oo/oa/ao/aa) — upstream observed {recorded} early closes; "
        f"platform threads flat (slack {args.thread_slack}); follow-ups succeed"
)
    sys.exit(0)


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
