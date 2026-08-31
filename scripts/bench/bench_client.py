#!/usr/bin/env python3
"""bench_client.py — load client for the benchmark harness.

Runs ONE fixed load profile against one implementation (Janus JVM / Janus native /
every leg): a warm-up request, then N requests at C concurrency against the
chat-completions URL. Tool precedence: hey > wrk > ab > curl-loop; whichever
tool produced the numbers is NAMED in the summary (never hidden — ab's percentile
table is coarser than hey's; the methodology section records this).

Every raw tool output is archived verbatim under .run/ (no cherry-picking — the
stage 6 Review bullet).

Usage:
  bench_client.py --name <leg> --url <chat-completions-url> [--model deepseek-v4-flash]
                  [--n 1000] [--c 10] [--run-dir PATH] [--auth "Bearer sk-x"]

Prints key=value lines:
  tool=<hey|wrk|ab|curl>
  requests_per_sec=<float>
  p50_ms=<float>  p95_ms=<float>   (wrk, whose stock table has no 95th, emits p90_ms instead)
  raw=<relative path of the archived raw output>
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

DONE = "[DONE]"


def log(msg: str) -> None:
    print(msg, flush=True)


def warmup(url: str, body: dict, auth: str | None, timeout: float = 30.0) -> None:
    request = urllib.request.Request(
        url, data=json.dumps(body).encode("utf-8"), method="POST", headers={"Content-Type": "application/json"}
)
    if auth:
        request.add_header("Authorization", auth)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        assert response.status == 200, f"warm-up request failed: HTTP {response.status}"


# ------------------------------------------------------------------ tools

def run_hey(args) -> dict:
    # hey -n N -c C -m POST -H "Authorization:..." -H "Content-Type: application/json" -d BODY URL
    cmd = ["hey", "-n", str(args.n), "-c", str(args.c), "-m", "POST"]
    cmd += ["-H", "Content-Type: application/json"]
    if args.auth:
        cmd += ["-H", f"Authorization: {args.auth}"]
    cmd += ["-d", json.dumps(args.body), args.url]
    out = subprocess.run(cmd, capture_output=True, text=True)
    text = out.stdout or out.stderr
    archive(args, "hey", text)
    # hey text output: "Requests/sec: 1234.56" and "Latency distribution:" -> "50% in 1.23 ms"
    rps = _first(r"Requests/sec:\s*([\d.]+)", text)
    p50 = _first(r"50% in ([\d.]+) ms", text)
    p95 = _first(r"95% in ([\d.]+) ms", text)
    if rps is None or p50 is None or p95 is None:
        raise RuntimeError(f"hey output parse failed:\n{text[:2000]}")
    return {"tool": "hey", "requests_per_sec": float(rps), "p50_ms": float(p50), "p95_ms": float(p95)}


def run_wrk(args) -> dict:
    # wrk -t2 -c C -d 10s -s <lua> URL -> duration-based; approximate N by keeping
    # the fixed profile: run with -d (seconds) derived from a quick calibration is
    # overkill — wrk's raw output still gives req/s + latency distribution. The
    # fixed-N profile is a property of hey/ab/curl; wrk runs 10s (documented in the
    # raw output + methodology).
    #
    # Latency distribution lines look like: " 50.000% 2.00ms" (wrk 4.x prints
    # three-decimal percentages; older builds print "50%"). wrk's stock table is
    # 50/75/90/99 — there is no 95th; the high percentile is emitted under its TRUE
    # key (p90_ms) so RESULTS never compares hey's p95 against wrk's p90.
    # Defaulting missing percentiles to 0.0 silently corrupted RESULTS — raise on
    # parse failure instead.
    # The payload is written to a file and read from Lua (io.open) and the auth
    # header comes from the environment — interpolating either raw into a
    # single-quoted Lua string breaks on any quote/backslash in the value.
    body_file = Path(tempfile.gettempdir()) / "w50_wrk_body.json"
    body_file.write_text(json.dumps(args.body), encoding="utf-8")
    lua = Path(tempfile.gettempdir()) / "w50_bench.lua"
    lua.write_text(
        "wrk.method = 'POST'\n"
        "wrk.headers['Content-Type'] = 'application/json'\n"
        "local auth = os.getenv('JANUS_BENCH_AUTH')\n"
        "if auth then wrk.headers['Authorization'] = auth end\n"
        f"local f = io.open('{body_file}', 'rb')\n"
        "if f then wrk.body = f:read('*a'); f:close() end\n",
        encoding="utf-8",
)
    env = dict(os.environ)
    if args.auth:
        env["JANUS_BENCH_AUTH"] = args.auth
    cmd = ["wrk", "-t2", "-c", str(args.c), "-d", "10s", "-s", str(lua), args.url]
    out = subprocess.run(cmd, capture_output=True, text=True, env=env)
    text = out.stdout or out.stderr
    archive(args, "wrk", text)
    rps = _first(r"Requests/sec:\s*([\d.]+)", text)
    try:
        p50, p95 = parse_wrk_latencies(text)
    except ValueError as exc:
        raise RuntimeError(f"wrk output parse failed:\n{text[:2000]}") from exc
    if rps is None:
        raise RuntimeError(f"wrk output parse failed:\n{text[:2000]}")
    # wrk has no 95th in its stock table: emit the true key (p90_ms/p95_ms) so the
    # RESULTS schema never silently mixes hey's p95 with wrk's p90.
    return {"tool": "wrk", "requests_per_sec": float(rps), "p50_ms": p50, f"p{p95[1]}_ms": p95[0]}


def run_ab(args) -> dict:
    # ab -n N -c C -p body.json -T application/json [-H "Authorization:..."] URL
    body_file = Path(tempfile.gettempdir()) / "w50_ab_body.json"
    body_file.write_text(json.dumps(args.body), encoding="utf-8")
    cmd = ["ab", "-n", str(args.n), "-c", str(args.c), "-p", str(body_file), "-T", "application/json"]
    if args.auth:
        cmd += ["-H", f"Authorization: {args.auth}"]
    cmd += [args.url]
    out = subprocess.run(cmd, capture_output=True, text=True)
    text = out.stdout or out.stderr
    archive(args, "ab", text)
    rps = _first(r"Requests per second:\s*([\d.]+)", text)
    p50 = _first(r"^\s*50%\s+(\d+)", text, re_multiline=True)
    p95 = _first(r"^\s*95%\s+(\d+)", text, re_multiline=True)
    if rps is None or p50 is None or p95 is None:
        raise RuntimeError(f"ab output parse failed:\n{text[:2000]}")
    return {
        "tool": "ab",
        "requests_per_sec": float(rps),
        "p50_ms": float(p50),
        "p95_ms": float(p95),
    }


def run_curl_loop(args) -> dict:
    # Fallback: sequential curl loop (the T234 order); N sequential requests.
    latencies: list[float] = []
    body = json.dumps(args.body).encode("utf-8")
    failures = 0
    start = time.monotonic()
    for _ in range(args.n):
        t0 = time.monotonic()
        cmd = ["curl", "-sf", "-X", "POST", args.url, "-H", "Content-Type: application/json", "-d", body]
        if args.auth:
            cmd += ["-H", f"Authorization: {args.auth}"]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            failures += 1
            continue
        latencies.append((time.monotonic() - t0) * 1000.0)
    elapsed = time.monotonic() - start
    p95 = _percentile_nearest_rank(latencies, 0.95)
    text = (
        f"curl-loop fallback: {args.n} requests, {len(latencies)} ok, {failures} failed, "
        f"{elapsed:.3f}s total\np50={statistics.median(latencies):.2f}ms p95={p95:.2f}ms\n"
)
    archive(args, "curl", text)
    if failures or not latencies:
        raise RuntimeError(f"curl-loop had {failures} failures (see raw output)")
    return {
        "tool": "curl",
        "requests_per_sec": len(latencies) / elapsed,
        "p50_ms": statistics.median(latencies),
        "p95_ms": p95,
    }


# ------------------------------------------------------------------ helpers

def _percentile_nearest_rank(values: list[float], q: float) -> float:
    """Nearest-rank percentile: ceil(q*n)-th smallest (index ceil(q*n)-1)."""
    ordered = sorted(values)
    rank = max(1, int(len(ordered) * q + 0.999999))
    return ordered[min(rank, len(ordered)) - 1]


def _first(pattern: str, text: str, re_multiline: bool = False):
    import re

    flags = re.MULTILINE if re_multiline else 0
    match = re.search(pattern, text, flags)
    return match.group(1) if match else None


def _wrk_percentile_ms(text: str, pct: int) -> str | None:
    """Match wrk's '50%' or '50.000%' latency lines (optional space before ms)."""
    return _first(rf"{pct}(?:\.\d+)?%\s+([\d.]+)\s*ms", text)


def parse_wrk_latencies(text: str) -> tuple[float, float, int]:
    """Parse wrk Latency Distribution into (p50_ms, high_ms, high_pct).

    wrk prints 50/75/90/99 (not 95). Prefer an explicit 95th when present; else
    return the 90th with its true percentile so callers label it ``p90_ms`` —
    never as p95. Raises ValueError if p50 or the high percentile is missing —
    never invents 0.0.
    """
    p50 = _wrk_percentile_ms(text, 50)
    high95 = _wrk_percentile_ms(text, 95)
    high90 = _wrk_percentile_ms(text, 90)
    if p50 is None or (high95 is None and high90 is None):
        raise ValueError(f"wrk latency distribution parse failed:\n{text[:500]}")
    if high95 is not None:
        return float(p50), float(high95), 95
    return float(p50), float(high90), 90


def archive(args, tool: str, text: str) -> None:
    raw = Path(args.run_dir) / f"{args.name}.{tool}.raw.txt"
    raw.write_text(text, encoding="utf-8")
    log(f"raw tool output archived: {raw}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--url", required=True)
    parser.add_argument("--model", default="deepseek-v4-flash")
    parser.add_argument("--n", type=int, default=1000)
    parser.add_argument("--c", type=int, default=10)
    parser.add_argument("--run-dir", default=".run")
    parser.add_argument("--auth", default=None, help='e.g. "Bearer sk-bench" (for authed runs)')
    parser.add_argument("--no-warmup", action="store_true")
    args = parser.parse_args()
    args.run_dir = os.path.abspath(args.run_dir)
    Path(args.run_dir).mkdir(parents=True, exist_ok=True)
    args.body = {"model": args.model, "messages": [{"role": "user", "content": "w50-bench"}]}

    if not args.no_warmup:
        warmup(args.url, args.body, args.auth)
        log("warm-up request: 200 OK")

    if shutil.which("hey"):
        result = run_hey(args)
    elif shutil.which("wrk"):
        result = run_wrk(args)
    elif shutil.which("ab"):
        result = run_ab(args)
    else:
        result = run_curl_loop(args)

    for key, value in result.items():
        print(f"{key}={value}", flush=True)


if __name__ == "__main__":
    main()
