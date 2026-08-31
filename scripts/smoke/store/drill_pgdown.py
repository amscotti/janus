#!/usr/bin/env python3
"""drill_pgdown.py — store Review 2 failure drills (Postgres down).

Two drills over real sockets against the runner's drill Postgres container:

  (a) DOWN AT BOOT — [janus.store] type = "postgres" with an UNREACHABLE
      JANUS_DB_URL: the node REFUSES to start (nonzero exit; stderr names the
      ENV VAR "JANUS_DB_URL", never the URL/credentials). The chosen Review-2
 option ( decision): a node silently falling back to memory in a
      multi-node deployment would violate read-your-writes, so the pool is
      constructed fail-fast (HikariCP initializationFailTimeout = 1) and the
      boot dies before serving a single request.

  (b) DOWN MID-RUN — node A is already up against the live drill Postgres; the
      drill stops the container and issues requests: each fails with a CLEAN 5xx
      envelope (500 api_error — the store exception propagates through the
      request path to GatewayExceptionHandler; pinned here: no hang, no stack
      trace in the body, no platform-thread growth), then restarts the container
      and asserts the SAME node serves a request successfully again (HikariCP
      pool recovery).

Exit 0 = all assertions pass; any failure prints the reason and exits nonzero.

Usage:
  drill_pgdown.py --base-url http://127.0.0.1:PORT/v1 --master-key <key>
                  --repo <ABS-repo> --config <ABS config.nodeA.toml>
                  --pg-container <name> --janus-pid <node-A-pid> [--pg-user janus] [--pg-db janus]
                  [--bad-url jdbc:postgresql://127.0.0.1:1/janus] [--bad-port 1]
"""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
import time

from harness_common import FIXTURE_CONTENT, MODEL, admin_base_url, generate_key, http_json, platform_threads

REQUEST_TIMEOUT = 20.0  # "no hang" bound: every outage request must fail fast


def log(msg: str) -> None:
    print(msg, flush=True)


def model_request(base: str, key: str, timeout: float = REQUEST_TIMEOUT):
    started = time.monotonic()
    status, headers, payload = http_json(
        "POST",
        f"{base}/chat/completions",
        body={
            "model": MODEL,
            "messages": [{"role": "user", "content": f"w42-pgdown-{time.time_ns()}"}],
            "max_tokens": 1024,
        },
        headers={"x-api-key": key},
        timeout=timeout,
)
    elapsed = time.monotonic() - started
    return status, headers, payload, elapsed


def assert_clean_5xx(status: int, payload, body_hint: str, what: str) -> None:
    assert status == 500, f"{what}: expected a clean 500, got {status} ({payload})"
    error = (payload or {}).get("error", {})
    assert error.get("type") == "api_error", f"{what}: error.type {error.get('type')!r} ({payload})"
    assert "at io.amscotti" not in body_hint, f"{what}: stack trace leaked into the envelope body"


def free_port() -> int:
    import socket

    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--config", required=True, help="ABS path to config.nodeA.toml")
    parser.add_argument("--pg-container", required=True)
    parser.add_argument("--janus-pid", required=True, help="the already-up node A JVM pid (thread-leak check)")
    parser.add_argument("--pg-user", default="janus")
    parser.add_argument("--pg-db", default="janus")
    parser.add_argument("--bad-url", default="jdbc:postgresql://127.0.0.1:1/janus")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")

    # ------------------------------------------------------------- (a) down at boot
    log(f"\n(a) down at boot — bad JANUS_DB_URL={args.bad_url!r}")
    boot_env = dict(os.environ)
    boot_env.update(
        {
            "JANUS_DB_URL": args.bad_url,
            "JANUS_DB_USER": args.pg_user,
            "JANUS_DB_PASS": "janus",
            "JANUS_MASTER_KEY": args.master_key,
            "MICRONAUT_SERVER_PORT": str(free_port()),
        }
)
    log("booting a fresh node with the unreachable URL (expect: refuse to start, nonzero exit)")
    try:
        boot = subprocess.run(
            [os.path.join(args.repo, "gradlew"), ":janus-cli:run", "--no-daemon", "--args=--config " + args.config],
            capture_output=True,
            text=True,
            env=boot_env,
            timeout=180,
)
    except subprocess.TimeoutExpired as exc:
        print("boot did NOT fail within 180s — the node started despite the unreachable DB?!", file=sys.stderr)
        print(exc.stdout or "", file=sys.stderr)
        print(exc.stderr or "", file=sys.stderr)
        sys.exit(1)
    combined = (boot.stdout or "") + (boot.stderr or "")
    assert boot.returncode != 0, f"down-at-boot: node EXITED 0 with an unreachable DB — fail-fast broken"
    assert "JANUS_DB_URL" in combined, (
        f"down-at-boot: refusal error does not name the env var JANUS_DB_URL:\n{combined[-3000:]}"
)
    assert args.bad_url not in combined, (
        f"down-at-boot: the refusal error LEAKS the JDBC URL (credentials may be embedded):\n{combined[-3000:]}"
)
    refuse_line = next(
        (line.strip() for line in combined.splitlines() if "JANUS_DB_URL" in line), combined.strip().splitlines()[-1]
)
    log(f"PASS down at boot: node refused to start (exit {boot.returncode}); refusal names the env var,"
        f" never the URL — {refuse_line!r}")

    # ------------------------------------------------------------ (b) down mid-run
    log(f"\n(b) down mid-run — stop {args.pg_container}, assert clean 5xx + recovery")
    key_id, full_key = generate_key(base, args.master_key, models=[MODEL], name="w42-pgdown")
    threads_before = platform_threads(int(args.janus_pid))

    subprocess.run(["docker", "stop", args.pg_container], check=True, capture_output=True)
    log("Postgres container stopped")

    # a few outage requests — every one must fail with the clean 5xx envelope, fast.
    for i in range(3):
        status, _headers, payload, elapsed = model_request(base, full_key)
        hint = str(payload)
        assert_clean_5xx(status, payload, hint, f"outage model request {i + 1}")
        assert elapsed < REQUEST_TIMEOUT, f"outage request {i + 1} HUNG for {elapsed:.1f}s (no-hang contract)"
        log(f"  outage request {i + 1}: 500 api_error envelope in {elapsed:.2f}s (no hang, no stack)")

    t0 = time.monotonic()
    status, _headers, payload = http_json(
        "POST",
        # admin_base_url strips a trailing "/v1" SUFFIX — base.rstrip('/v1') would
        # strip the CHARACTER SET {'/', 'v', '1'} and mangle a port like 49191.
        f"{admin_base_url(base)}/key/generate",
        body={"name": "w42-pgdown-admin"},
        headers={"x-api-key": args.master_key},
        timeout=REQUEST_TIMEOUT,
)
    elapsed = time.monotonic() - t0
    assert_clean_5xx(status, payload, str(payload), "outage admin /key/generate")
    assert elapsed < REQUEST_TIMEOUT, f"outage admin request HUNG for {elapsed:.1f}s"
    log(f"  outage admin /key/generate: 500 api_error envelope in {elapsed:.2f}s")

    threads_after = platform_threads(int(args.janus_pid))
    assert threads_after - threads_before < 20, (
        f"thread leak: node A platform threads {threads_before} → {threads_after} during the outage"
)
    log(f"  no thread leak: platform threads {threads_before} → {threads_after}")

    subprocess.run(["docker", "start", args.pg_container], check=True, capture_output=True)
    log("Postgres container restarted — waiting for HikariCP pool recovery")
    deadline = time.monotonic() + 90.0
    recovered = False
    while time.monotonic() < deadline:
        status, _headers, payload, elapsed = model_request(base, full_key)
        if status == 200 and payload and payload.get("choices"):
            assert payload["choices"][0]["message"]["content"] == FIXTURE_CONTENT, (
                "recovery request did not return the golden content"
)
            recovered = True
            break
        time.sleep(1.0)
    assert recovered, "node A did NOT recover after the Postgres restart (90s) — HikariCP pool recovery broken"
    log(f"PASS down mid-run: clean 5xx throughout the outage (no hang/leak/retry storm); the SAME node served a"
        f" golden 200 after the container restart ({elapsed:.2f}s round-trip) — HikariCP pool recovery")

    print("drill_pgdown: ALL PASS")


if __name__ == "__main__":
    main()
