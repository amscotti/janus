#!/usr/bin/env python3
"""drill_killnode.py — store gate 2 kill-node-A drill.

Streams a request on node A, ``kill -9`` A mid-stream, and proves the documented
streaming bound live:

  - the client sees a documented connection reset (the stream does NOT complete —
    no ``[DONE]``; an in-flight request is never migrated to B);
  - node B's ``/health`` stays 200 THROUGHOUT and a fresh request on B succeeds
    with zero client-visible errors (retry-on-B: the operator LB health-checks
    ``/health`` and retries — in-flight requests are never migrated);
  - the aborted stream on A records NO CallRecord (the  m2 decision,
    record-nothing — the shared calls table count for the key is unchanged);
  - node A is actually dead after the kill.

Exit 0 = all assertions pass; any failure prints the reason and exits nonzero.

Usage:
  drill_killnode.py --base-url-a http://127.0.0.1:PORT/v1 --base-url-b http://127.0.0.1:PORT/v1
                    --master-key <key> --pid-a <node-A-JVM-pid>
                    --pg-container <name> [--pg-user janus] [--pg-db janus]
"""
from __future__ import annotations

import argparse
import http.client
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

from harness_common import FIXTURE_CONTENT, MODEL, generate_key, http_json, http_text

RESET_ERRORS = (ConnectionResetError, http.client.RemoteDisconnected, http.client.IncompleteRead, BrokenPipeError, OSError)


def log(msg: str) -> None:
    print(msg, flush=True)


def health(port: int) -> int:
    try:
        status, _headers, _body = http_text(f"http://127.0.0.1:{port}/health", timeout=5.0)
        return status
    except (urllib.error.URLError, OSError):
        return 0


def psql(container: str, sql: str, user: str = "janus", db: str = "janus") -> str:
    out = subprocess.run(
        ["docker", "exec", container, "psql", "-U", user, "-d", db, "-tAc", sql],
        capture_output=True,
        text=True,
    )
    assert out.returncode == 0, f"psql failed (container {container}): {out.stderr.strip()}"
    return out.stdout.strip()


def port_of(base_url: str) -> int:
    root = base_url.rstrip("/")
    if root.endswith("/v1"):
        root = root[: -len("/v1")]
    return int(root.rsplit(":", 1)[1])


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url-a", required=True)
    parser.add_argument("--base-url-b", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--pid-a", required=True, help="node A JVM pid (kill -9 target)")
    parser.add_argument("--pg-container", required=True)
    parser.add_argument("--pg-user", default="janus")
    parser.add_argument("--pg-db", default="janus")
    args = parser.parse_args()

    a = args.base_url_a.rstrip("/")
    b = args.base_url_b.rstrip("/")
    port_a = port_of(a)
    port_b = port_of(b)
    pid_a = int(args.pid_a)

    assert os.path.exists(f"/proc/{pid_a}") or _pid_alive_macos(pid_a), f"node A pid {pid_a} not alive"
    log(f"node A :{port_a} (pid {pid_a})  node B :{port_b}  shared calls table via {args.pg_container}")

    # ------------------------------------------------------------- setup + baseline
    key_id, full_key = generate_key(a, args.master_key, models=[MODEL], name="w42-killnode")
    status_b, _h, _body = http_json(
        "POST",
        f"{b}/chat/completions",
        body={
            "model": MODEL,
            "messages": [{"role": "user", "content": "w42-killnode-baseline"}],
            "max_tokens": 1024,
        },
        headers={"x-api-key": full_key},
    )
    assert status_b == 200, f"baseline request on B: expected 200, got {status_b}"
    calls_before = int(psql(args.pg_container, f"SELECT count(*) FROM calls WHERE key_id = '{key_id}'",
                            args.pg_user, args.pg_db))
    assert calls_before == 1, f"baseline: expected 1 call record, got {calls_before}"
    assert health(port_b) == 200, "B /health must be 200 before the kill"
    log(f"baseline: one settled request on B (calls={calls_before}), B /health 200")

    # ------------------------------------------------------- stream on A, kill A
    log("streaming a request on node A, then kill -9 A mid-stream")
    payload = {
        "model": MODEL,
        "messages": [{"role": "user", "content": "w42-killnode-stream"}],
        "stream": True,
        "stream_options": {"include_usage": True},
    }
    conn = http.client.HTTPConnection("127.0.0.1", port_a, timeout=60.0)
    conn.request(
        "POST",
        "/v1/chat/completions",
        body=json.dumps(payload),
        headers={"Content-Type": "application/json", "x-api-key": full_key},
    )
    resp = conn.getresponse()
    assert resp.status == 200, f"stream on A: expected 200, got {resp.status}"
    frames: list[str] = []
    reset_observation = None
    try:
        for line in resp:
            text = line.decode("utf-8", "replace").strip()
            if not text:
                continue
            frames.append(text)
            if len(frames) >= 2:
                break
        assert len(frames) >= 2, f"stream on A yielded only {len(frames)} frames before the kill"
        log(f"  read {len(frames)} SSE frames from A — killing now")
        os.kill(pid_a, signal.SIGKILL)
        log("  kill -9 delivered to node A")
        for line in resp:  # keep reading until the reset
            text = line.decode("utf-8", "replace").strip()
            if text:
                frames.append(text)
    except RESET_ERRORS as exc:
        reset_observation = f"{type(exc).__name__}: {exc}"
    finally:
        try:
            conn.close()
        except OSError:
            pass

    completed = any("[DONE]" in frame for frame in frames)
    assert not completed, "stream on A COMPLETED cleanly ([DONE] seen) — the kill did not interrupt it"
    log(f"client-visible outcome: stream on A did NOT complete; observed "
        f"{reset_observation or 'clean EOF without [DONE] (connection reset)'} — {len(frames)} frames in total")

    calls_after_abort = int(psql(args.pg_container, f"SELECT count(*) FROM calls WHERE key_id = '{key_id}'",
                                 args.pg_user, args.pg_db))
    assert calls_after_abort == calls_before, (
        f"aborted stream on A must record NOTHING ( m2 decision): calls {calls_before} → {calls_after_abort}"
    )
    log("PASS the aborted stream on A recorded NO CallRecord (calls table unchanged — the  record-nothing decision)")

    # ------------------------------------------------------- B stays healthy + serves
    status_b = health(port_b)
    assert status_b == 200, f"B /health after the kill: expected 200, got {status_b}"
    log("PASS B /health 200 immediately after the kill (and throughout — polled again below)")

    for i in range(5):
        assert health(port_b) == 200, f"B /health poll {i + 1} not 200"
        time.sleep(0.2)

    status_b, _h, body = http_json(
        "POST",
        f"{b}/chat/completions",
        body={
            "model": MODEL,
            "messages": [{"role": "user", "content": "w42-killnode-fresh"}],
            "max_tokens": 1024,
        },
        headers={"x-api-key": full_key},
    )
    assert status_b == 200, f"fresh request on B after the kill: expected 200, got {status_b}"
    assert body["choices"][0]["message"]["content"] == FIXTURE_CONTENT, "fresh B request: golden content"
    log("PASS fresh request on B succeeds immediately with zero client-visible errors (golden content)")

    calls_after = int(psql(args.pg_container, f"SELECT count(*) FROM calls WHERE key_id = '{key_id}'",
                           args.pg_user, args.pg_db))
    assert calls_after == calls_after_abort + 1, (
        f"fresh settled request on B must record exactly one: calls {calls_after_abort} → {calls_after}"
    )
    log("PASS the fresh settled request on B recorded exactly one CallRecord")

    assert not _pid_alive_macos(pid_a), f"node A (pid {pid_a}) still alive after kill -9"
    log("PASS node A is dead (kill -9 confirmed)")

    print("drill_killnode: ALL PASS")
    print("retry semantics (documented bound): in-flight requests are never migrated; the operator LB health-checks"
          " /health and retries on the surviving node — a client whose node dies must retry via the LB.")


def _pid_alive_macos(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


if __name__ == "__main__":
    main()
