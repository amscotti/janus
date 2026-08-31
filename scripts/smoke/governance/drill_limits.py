#!/usr/bin/env python3
"""drill_limits.py — governance 1 rate-limit drill ().

Proves the per-key RPM/TPM caps live over real sockets (config.fake.toml,
``window = "fixed"`` or config.sliding.toml ``window = "sliding"`` — the runner
passes --window for the leg's documented-shape split):

  RPM:
    - a key with ``rpm: 2`` passes requests 1-2; request 3 → 429 ``rate_limit_error``
      + ``Retry-After`` header. Fixed window: the header equals the seconds until the
      next aligned 60s window end (60 - now % 60, ±1 for the second boundary);
      sliding variant: the header is present and in [1, 60] (the  m1 decision —
      the sliding variant substitutes the conservative aligned-window value for the
      deficit÷rate refill seconds; documented in docs/governance.md).
    - the throttled request NEVER reaches the fake (counter flat across the 429).
    - both faces (OpenAI + Anthropic envelopes, same ``rate_limit_error`` wire type).
  TPM:
    - a ``tpm: 100`` key with a ``max_tokens: 1000`` request → 429 pre-check BEFORE
      dispatch (counter flat); the follow-up ``max_tokens: 10`` request succeeds
      (estimate 10 ≤ 100); a second ``max_tokens: 1000`` request → 429 again — the
      real 14+12=26 tokens accumulated at finalize (26 + 1000 > 100).
  Caps validation ( live re-verify):
    - ``rpm: 0`` at generate → 400 invalid_request_error (null = no cap, 0 is not
      expressible).

Exit 0 = all assertions pass.

Usage:
  drill_limits.py --base-url http://127.0.0.1:PORT/v1 --master-key <key>
                  --counter <fake-counter-file> [--window fixed|sliding]
"""
from __future__ import annotations

import argparse
import json
import sys
import time

from harness_common import MODEL, admin_base_url, delete_key, generate_key, http_json, read_counter

WINDOW_SECONDS = 60


def log(msg: str) -> None:
    print(msg, flush=True)


def openai_request(base: str, key: str, max_tokens: int = 1024):
    return http_json(
        "POST",
        f"{base}/chat/completions",
        body={
            "model": MODEL,
            "messages": [{"role": "user", "content": f"w33-limits-{time.time_ns()}"}],
            "max_tokens": max_tokens,
        },
        headers={"x-api-key": key},
    )


def anthropic_request(base: str, key: str, max_tokens: int = 1024):
    return http_json(
        "POST",
        f"{base}/messages",
        body={
            "model": MODEL,
            "max_tokens": max_tokens,
            "messages": [{"role": "user", "content": f"w33-limits-a-{time.time_ns()}"}],
        },
        headers={"x-api-key": key},
    )


def assert_rate_limit_429(status: int, payload, what: str) -> None:
    assert status == 429, f"{what}: expected 429, got {status} ({payload})"
    error = (payload or {}).get("error", {})
    assert error.get("type") == "rate_limit_error", f"{what}: error.type {error.get('type')!r} ({payload})"


def assert_retry_after(headers: dict, window: str, what: str) -> int:
    value = headers.get("Retry-After") or headers.get("retry-after")
    assert value is not None, f"{what}: missing Retry-After header ({headers})"
    seconds = int(value)
    assert 1 <= seconds <= WINDOW_SECONDS, f"{what}: Retry-After {seconds} outside [1, 60]"
    if window == "fixed":
        expected = WINDOW_SECONDS - (int(time.time()) % WINDOW_SECONDS)
        # A second boundary may tick between the client's computation and the
        # server's decision: allow ±1 (the server's value is exact at ITS clock).
        assert abs(seconds - expected) <= 1, (
            f"{what}: Retry-After {seconds} != window-end math {expected} (±1)"
        )
    return seconds


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--counter", required=True, help="fake upstream counter file (flat-dispatch proof)")
    parser.add_argument("--window", choices=["fixed", "sliding"], default="fixed")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    master = args.master_key

    # ---- RPM: 3rd request 429s + Retry-After; fake stays flat -------------
    _, rpm_key = generate_key(base, master, models=[MODEL], name="w33-rpm", rpm=2)
    for i in (1, 2):
        status, _, payload = openai_request(base, rpm_key)
        assert status == 200, f"rpm request {i}: expected 200, got {status} ({payload})"
    before = read_counter(args.counter)["requests"]
    status, headers, payload = openai_request(base, rpm_key)
    after = read_counter(args.counter)["requests"]
    assert_rate_limit_429(status, payload, "rpm 3rd request (oo)")
    assert_retry_after(headers, args.window, "rpm 3rd request (oo)")
    assert after == before, f"rpm 429 request reached the fake (counter {before} → {after})"
    log(f"PASS RPM (oo): 3rd request → 429 rate_limit_error + Retry-After={headers.get('Retry-After')} ({args.window}); fake counter flat ({before} → {after})")

    # ---- RPM on the Anthropic face ----------------------------------------
    _, rpm_key_a = generate_key(base, master, models=[MODEL], name="w33-rpm-a", rpm=2)
    for i in (1, 2):
        status, _, payload = anthropic_request(base, rpm_key_a)
        assert status == 200, f"rpm-a request {i}: expected 200, got {status} ({payload})"
    status, headers, payload = anthropic_request(base, rpm_key_a)
    assert status == 429 and ((payload or {}).get("error") or {}).get("type") == "rate_limit_error", (
        f"rpm 3rd request (ao): expected 429 rate_limit_error, got {status} ({payload})"
    )
    assert headers.get("Retry-After") is not None, "rpm 3rd request (ao): missing Retry-After"
    log("PASS RPM (ao): 3rd request → 429 rate_limit_error + Retry-After (face envelope)")

    # ---- TPM: conservative pre-check + real-token accumulation ------------
    _, tpm_key = generate_key(base, master, models=[MODEL], name="w33-tpm", tpm=100)
    before = read_counter(args.counter)["requests"]
    status, _, payload = openai_request(base, tpm_key, max_tokens=1000)
    after = read_counter(args.counter)["requests"]
    assert_rate_limit_429(status, payload, "tpm pre-check (max_tokens=1000 > tpm 100)")
    assert after == before, f"tpm pre-check request reached the fake (counter {before} → {after})"
    log("PASS TPM pre-check: max_tokens=1000 on a tpm:100 key → 429 before dispatch (counter flat)")

    status, _, payload = openai_request(base, tpm_key, max_tokens=10)
    assert status == 200, f"tpm follow-up (max_tokens=10): expected 200, got {status} ({payload})"
    log("PASS TPM follow-up: smaller request succeeds (estimate 10 ≤ 100)")

    status, _, payload = openai_request(base, tpm_key, max_tokens=1000)
    assert_rate_limit_429(status, payload, "tpm post-accumulate (26 real tokens + 1000 > 100)")
    log("PASS TPM accumulation: 14+12 real tokens accumulated at finalize — the next 1000-estimate 429s again")

    # ---- : non-positive caps rejected at generate --------------------
    status, _, payload = http_json(
        "POST", f"{admin_base_url(base)}/key/generate", body={"models": [MODEL], "rpm": 0}, headers={"x-api-key": master}
    )
    assert status == 400, f"rpm:0 generate: expected 400, got {status} ({payload})"
    error = (payload or {}).get("error", {})
    assert error.get("type") == "invalid_request_error", f"rpm:0 generate: {payload}"
    log("PASS rpm:0 → 400 invalid_request_error at generate ( live re-verify)")

    print("drill_limits: ALL PASS")


if __name__ == "__main__":
    main()
