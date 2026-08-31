#!/usr/bin/env python3
"""startup_rss.py — startup + RSS measurement (stage 0 procedure kept).

Three modes:

  cold  — spawn a fresh process (wrapping it in /usr/bin/time for max-RSS capture),
          measure ms from spawn to the first successful GET /health, print
          cold_ms= / max_rss_kb= / rss_source= (macOS `time -l` maximum resident
          set size; Linux `time -v` Maximum resident set size (kbytes) — the host
          OS is named with every number in RESULTS.md).
  warm  — measure /health latency on an ALREADY-BOOTED process (no spawn).
  rss   — sample the resident set size of a running process (ps -o rss), the
          post-warmup-under-load number every implementation is measured with.

Usage:
  startup_rss.py cold --health-url URL --cmd CMD [ARG...]
  startup_rss.py warm --health-url URL
  startup_rss.py rss --pid PID
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

HEALTH_OK = 200


def log(msg: str) -> None:
    print(msg, flush=True)


def health_ok(url: str, timeout: float = 3.0) -> bool:
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.status == HEALTH_OK
    except Exception:
        return False


def wait_health(url: str, bound_s: float = 60.0) -> float:
    """ms until the first successful /health; raises if never healthy."""
    start = time.monotonic()
    while time.monotonic() - start < bound_s:
        if health_ok(url):
            return (time.monotonic() - start) * 1000.0
        time.sleep(0.005)
    raise RuntimeError(f"/health never returned 200 within {bound_s}s ({url})")


def time_binary() -> str:
    """The /usr/bin/time binary (NOT the shell keyword)."""
    return "/usr/bin/time"


def parse_max_rss(text: str, os_name: str) -> int:
    if os_name == "darwin":
        # macOS <time -l> prints "<bytes> maximum resident set size" (26.x) or
        # "maximum resident set size <bytes>" (older) — accept both orders.
        match = re.search(r"(\d+)\s+maximum resident set size|maximum resident set size\s+(\d+)", text)
        if not match:
            raise RuntimeError(f"cannot parse macOS /usr/bin/time -l RSS:\n{text[:2000]}")
        return int(match.group(1) or match.group(2)) // 1024  # bytes -> KiB
    match = re.search(r"Maximum resident set size \(kbytes\):\s*(\d+)", text)
    if not match:
        raise RuntimeError(f"cannot parse Linux /usr/bin/time -v RSS:\n{text[:2000]}")
    return int(match.group(1))


def child_pid(ppid: int) -> int | None:
    """The direct child of /usr/bin/time (the actual target process)."""
    out = subprocess.run(["pgrep", "-P", str(ppid)], capture_output=True, text=True)
    pids = [p for p in out.stdout.split() if p.strip()]
    return int(pids[0]) if pids else None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["cold", "warm", "rss"])
    parser.add_argument("--health-url", default="http://127.0.0.1:18090/health")
    parser.add_argument("--cmd", nargs=argparse.REMAINDER, default=None)
    parser.add_argument("--pid", type=int, default=None)
    args = parser.parse_args()

    os_name = sys.platform  # darwin | linux

    if args.mode == "warm":
        ms = wait_health(args.health_url, bound_s=10.0)
        print(f"warm_ms={ms:.1f}")
        return

    if args.mode == "rss":
        assert args.pid, "--pid required for rss mode"
        out = subprocess.run(["ps", "-o", "rss=", "-p", str(args.pid)], capture_output=True, text=True)
        if out.returncode != 0 or not out.stdout.strip():
            raise RuntimeError(f"cannot sample RSS for pid {args.pid}: {out.stderr.strip()}")
        print(f"rss_kb={int(out.stdout.strip().split()[0])}")
        return

    # cold: spawn under /usr/bin/time, wait for /health, then wait for exit.
    assert args.cmd, "--cmd required for cold mode"
    time_cmd = [time_binary(), "-l"] if os_name == "darwin" else [time_binary(), "-v"]
    # start_new_session: /usr/bin/time wraps the target; the target (and any of its
    # children) must die with the wrapper, so the whole process group is killed on
    # timeout — otherwise the orphaned grandchild keeps stderr open and communicate
    # hangs (observed in bring-up).
    proc = subprocess.Popen(
        time_cmd + list(args.cmd),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
        start_new_session=True,
)
    cold_ms = wait_health(args.health_url, bound_s=120.0)
    log(f"cold boot -> /health: {cold_ms:.0f} ms (pid {proc.pid})")
    # /usr/bin/time prints its report when its CHILD exits; killing the whole group
    # (including the wrapper) loses the max-RSS report. TERM only the target child.
    import signal

    target = child_pid(proc.pid)
    if target is not None:
        os.kill(target, signal.SIGTERM)
    try:
        _, stderr = proc.communicate(timeout=15)
    except subprocess.TimeoutExpired:
        if target is not None:
            os.kill(target, signal.SIGKILL)
        os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        _, stderr = proc.communicate()
    rss_kb = parse_max_rss(stderr or "", os_name)
    print(f"cold_ms={cold_ms:.1f}")
    print(f"max_rss_kb={rss_kb}")
    print(f"rss_source=os-{os_name} /usr/bin/time max-RSS (whole lifetime incl. boot)")


if __name__ == "__main__":
    main()
