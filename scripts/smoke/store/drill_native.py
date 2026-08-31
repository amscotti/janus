#!/usr/bin/env python3
"""drill_native.py — store gate 4 native-image leg drill.

Runs against the GraalVM native binary booted with [janus.store] type = "postgres"
(MICRONAUT_CONFIG_FILES boot — the image's mainClass is JanusApplication, NOT
--config). Asserts:

  - the boot tripwire: a keyless model request 401s (auth enforcing in the image —
    JANUS_MASTER_KEY resolved from the env) and the master-keyed /key/generate
    round-trips a sk-janus- key; /v1/models lists deepseek-v4-flash exactly once;
  - a keyed request round-trips through the image with the golden usage 14/12 and
    content (the fake behind it), and the scraped /metrics cost delta is EXACTLY
    5320 micro-USD (the JDBC path meters through the image);
  - ONE recorded CallRecord is visible in the shared Postgres calls table (the
    image's PostgresCallStore wrote through the embedded JDBC driver).

Exit 0 = all assertions pass; any failure prints the reason and exits nonzero.

Usage:
  drill_native.py --base-url http://127.0.0.1:PORT/v1 --master-key <key>
                  --pg-container <name> [--pg-user janus] [--pg-db janus]
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time

from harness_common import (
    FIXTURE_CONTENT,
    GOLDEN_IN,
    GOLDEN_MICRO,
    GOLDEN_OUT,
    MODEL,
    generate_key,
    http_json,
    http_text,
    scrape_metrics,
    series_value,
)


def log(msg: str) -> None:
    print(msg, flush=True)


def psql(container: str, sql: str, user: str = "janus", db: str = "janus") -> str:
    out = subprocess.run(
        ["docker", "exec", container, "psql", "-U", user, "-d", db, "-tAc", sql],
        capture_output=True,
        text=True,
)
    assert out.returncode == 0, f"psql failed (container {container}): {out.stderr.strip()}"
    return out.stdout.strip()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--pg-container", required=True)
    parser.add_argument("--pg-user", default="janus")
    parser.add_argument("--pg-db", default="janus")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")

    # ------------------------------------------------------------- boot tripwire
    status, _, _ = http_json(
        "POST",
        f"{base}/chat/completions",
        body={"model": MODEL, "messages": [{"role": "user", "content": "w42-native-tripwire"}]},
        headers={"x-api-key": "no-key"},
)
    assert status == 401, f"native tripwire: keyless request returned {status} (auth not enforcing in the image?)"
    key_id, full_key = generate_key(base, args.master_key, models=[MODEL], name="w42-native")
    log("PASS native tripwire: auth enforcing (keyless 401), master-keyed /key/generate round-trips sk-janus-")

    models_status, _, models = http_text(f"{base}/models", timeout=15.0)
    assert models_status == 200, f"/v1/models: expected 200, got {models_status}"
    assert models.count("deepseek-v4-flash") == 1, f"/v1/models must list deepseek-v4-flash exactly once: {models}"
    log("PASS /v1/models lists deepseek-v4-flash exactly once")

    # ------------------------------------------------------------- keyed round-trip
    before = series_value(scrape_metrics(base), "janus_cost_micro_usd_total")
    status, _, payload = http_json(
        "POST",
        f"{base}/chat/completions",
        body={
            "model": MODEL,
            "messages": [{"role": "user", "content": "w42-native-keyed"}],
            "max_tokens": 1024,
        },
        headers={"x-api-key": full_key},
)
    assert status == 200, f"native keyed request: expected 200, got {status} ({payload})"
    content = payload["choices"][0]["message"]["content"]
    assert content == FIXTURE_CONTENT, f"native keyed request: golden content expected, got {content!r}"
    usage = payload["usage"]
    assert usage["prompt_tokens"] == GOLDEN_IN and usage["completion_tokens"] == GOLDEN_OUT, usage
    log(f"PASS native keyed round-trip: golden usage {GOLDEN_IN}/{GOLDEN_OUT} + content through the image")

    deadline = time.monotonic() + 10.0
    delta = 0.0
    while time.monotonic() < deadline:
        after = series_value(scrape_metrics(base), "janus_cost_micro_usd_total")
        delta = after - before
        if delta != 0.0:
            break
        time.sleep(0.2)
    assert delta == float(GOLDEN_MICRO), f"native /metrics cost delta {delta} != {GOLDEN_MICRO} (zero tolerance)"
    log(f"PASS native /metrics: cost delta exactly {GOLDEN_MICRO} micro-USD (14/12 × DeepSeek table)")

    deadline = time.monotonic() + 10.0
    calls = 0
    while time.monotonic() < deadline:
        calls = int(psql(args.pg_container, f"SELECT count(*) FROM calls WHERE key_id = '{key_id}'",
                         args.pg_user, args.pg_db))
        if calls >= 1:
            break
        time.sleep(0.3)
    assert calls >= 1, f"native: no CallRecord for the key in the shared calls table (JDBC write missing?)"
    log(f"PASS native CallRecord: {calls} record(s) for the key visible in the shared Postgres calls table"
        " (embedded JDBC driver wrote through)")

    print("drill_native: ALL PASS")


if __name__ == "__main__":
    main()
