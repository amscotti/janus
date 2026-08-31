#!/usr/bin/env python3
"""drill_compose.py — docker compose smoke drill (test-only).

Invoked by run.sh with ``--leg``; stdlib-only HTTP vocabulary
reused from the committed phase5 harness_common.py (imported by path — no
duplicate code). Legs:

  memory   — Janus ALONE (memory store, compose default config): /health/
             readiness 200, /metrics serves janus_requests_total, the
             master-keyed admin key round-trip (/key/generate → /key/list →
             /key/delete, phase4 drill semantics), and the OFFLINE chat
             round-trip through the compose fake upstream (golden 14/12
             content) with the EXACT-cost /metrics delta 5320 micro-USD.
  postgres — postgres-backed Janus (JANUS_COMPOSE_CONFIG=config.postgres.toml,
             profile postgres): boot tripwire + admin key round-trip + ONE
             CallRecord visible in the shared Postgres (psql via docker exec —
             the embedded JDBC driver wrote through).
  multi    — TWO postgres-backed nodes sharing ONE Postgres (profiles postgres
             + node2): a key created on node 1 (janus, :8080) authenticates on
             node 2 (node2, :8082) and chats offline through the shared fake.

Exit 0 = all assertions pass; any failure prints the reason and exits nonzero.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "store"))
from harness_common import (  # noqa: E402
    FIXTURE_CONTENT,
    GOLDEN_IN,
    GOLDEN_MICRO,
    GOLDEN_OUT,
    MODEL,
    admin_base_url,
    delete_key,
    generate_key,
    http_json,
    http_text,
    list_keys,
    scrape_metrics,
    series_value,
)


def log(msg: str) -> None:
    print(msg, flush=True)


def psql(container: str, sql: str, **vars_: str) -> str:
    # Bind values after a charset check (gateway key_id is hex). psql -v / :'var'
    # interpolation is unreliable through `docker exec` (the colon is sent
    # literally on postgres:16-alpine).
    for name, value in vars_.items():
        assert re.fullmatch(r"[0-9a-fA-F_-]+", value), f"refusing to interpolate {name}={value!r}"
        sql = sql.replace(f":'{name}'", f"'{value}'")
    cmd = ["docker", "exec", container, "psql", "-U", "janus", "-d", "janus", "-tAc", sql]
    out = subprocess.run(cmd, capture_output=True, text=True)
    assert out.returncode == 0, f"psql failed (container {container}): {out.stderr.strip()}"
    return out.stdout.strip()


def wait_health(base: str, timeout: float = 120.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        status, _, _ = http_text(f"{base}/health/readiness", timeout=5.0)
        if status == 200:
            return
        time.sleep(0.5)
    raise AssertionError(f"/health/readiness never reached 200 on {base} ({timeout:.0f}s)")


def tripwire_and_roundtrip(base: str, master: str, label: str) -> tuple[str, str]:
    """Boot tripwire + admin key round-trip (phase4 drill semantics) + chat."""
    status, _, payload = http_json(
        "POST",
        f"{base}/v1/chat/completions",
        body={"model": MODEL, "messages": [{"role": "user", "content": f"w51-{label}-tripwire"}]},
        headers={"x-api-key": "no-key"},
)
    assert status == 401, f"{label} tripwire: keyless request returned {status} (auth not enforcing?)"
    key_id, full_key = generate_key(base, master, models=[MODEL], name=f"w51-{label}")
    log(f"PASS {label} tripwire: auth enforcing (keyless 401), master-keyed /key/generate round-trips sk-janus-")
    return key_id, full_key


def chat_roundtrip(base: str, full_key: str, label: str) -> tuple[float, float]:
    """Keyed offline chat round-trip (golden 14/12) + exact-cost /metrics delta."""
    before = series_value(scrape_metrics(base), "janus_cost_micro_usd_total")
    status, _, payload = http_json(
        "POST",
        f"{base}/v1/chat/completions",
        body={"model": MODEL, "messages": [{"role": "user", "content": f"w51-{label}-chat"}], "max_tokens": 1024},
        headers={"x-api-key": full_key},
)
    assert status == 200, f"{label} chat: expected 200, got {status} ({payload})"
    content = payload["choices"][0]["message"]["content"]
    assert content == FIXTURE_CONTENT, f"{label} chat: golden content expected, got {content!r}"
    usage = payload["usage"]
    assert usage["prompt_tokens"] == GOLDEN_IN and usage["completion_tokens"] == GOLDEN_OUT, usage
    deadline = time.monotonic() + 10.0
    delta = 0.0
    while time.monotonic() < deadline:
        after = series_value(scrape_metrics(base), "janus_cost_micro_usd_total")
        delta = after - before
        if delta != 0.0:
            break
        time.sleep(0.2)
    assert delta == float(GOLDEN_MICRO), f"{label} /metrics cost delta {delta} != {GOLDEN_MICRO}"
    log(f"PASS {label} chat round-trip: golden {GOLDEN_IN}/{GOLDEN_OUT} content through the fake;"
        f" /metrics delta exactly {GOLDEN_MICRO} micro-USD")
    return delta, 0.0


def leg_memory(base: str, master: str) -> None:
    wait_health(base)
    # Gauges (janus_upstream_*) are registered at boot; the Counters appear only
    # after their first increment (Micrometer lazy registration) — the
    # janus_requests_total assertion runs after the traffic below.
    status, _, body = http_text(f"{base}/metrics", timeout=30.0)
    assert status == 200 and "janus_upstream_healthy" in body, "/metrics must serve the Tier-1 janus_* series"
    log("PASS memory: /health/readiness 200; /metrics serves the Tier-1 janus_* gauges at boot")
    key_id, full_key = tripwire_and_roundtrip(base, master, "memory")
    chat_roundtrip(base, full_key, "memory")
    status, _, body = http_text(f"{base}/metrics", timeout=30.0)
    assert status == 200 and "janus_requests_total" in body, "/metrics must serve janus_requests_total after traffic"
    log("PASS memory: /metrics serves janus_requests_total after the keyed chat round-trip")
    # /key/list carries REDACTED records (field `id`, never the full key/hash/salt —
    # the phase4 drill_keys contract).
    keys = list_keys(base, master)
    ids = [k.get("id") for k in keys]
    assert key_id in ids, f"generated key {key_id} missing from /key/list (ids={ids})"
    joined = json.dumps(keys)
    assert full_key not in joined and "hash" not in joined and "salt" not in joined, \
        "/key/list leaks the full key or a hash/salt field"
    # Delete = revoke (the semantics): the redacted record stays in the list
    # with a revoked status and the full key 403s (permission_error) from then on.
    deleted = delete_key(base, master, key_id=key_id)
    assert deleted.get("deleted") is True, f"/key/delete: expected deleted:true, got {deleted}"
    status, _, payload = http_json(
        "POST",
        f"{base}/v1/chat/completions",
        body={"model": MODEL, "messages": [{"role": "user", "content": "w51-revoked"}]},
        headers={"x-api-key": full_key},
)
    assert status == 403, f"revoked key must 403 permission_error, got {status} ({payload})"
    log("PASS memory: admin key round-trip (/key/generate → redacted list → revoke → 403)")


def leg_postgres(base: str, master: str, pg_container: str) -> None:
    wait_health(base)
    key_id, full_key = tripwire_and_roundtrip(base, master, "postgres")
    chat_roundtrip(base, full_key, "postgres")
    deadline = time.monotonic() + 15.0
    calls = 0
    while time.monotonic() < deadline:
        calls = int(psql(pg_container, "SELECT count(*) FROM calls WHERE key_id = :'key_id'", key_id=key_id))
        if calls >= 1:
            break
        time.sleep(0.5)
    assert calls >= 1, f"postgres: no CallRecord for {key_id} in the shared Postgres (JDBC write missing?)"
    log(f"PASS postgres: {calls} CallRecord(s) for the key in the shared Postgres calls table"
        " (embedded JDBC driver wrote through)")
    delete_key(base, master, key_id=key_id)
    log("PASS postgres: admin key round-trip + ledger record + key deletion")


def leg_multi(base: str, base2: str, master: str, pg_container: str) -> None:
    wait_health(base)
    wait_health(base2)
    key_id, full_key = tripwire_and_roundtrip(base, master, "multi-node1")
    # The key created on node 1 authenticates on node 2 and chats through the
    # shared fake; the shared Postgres holds the CallRecord under the SAME key.
    chat_roundtrip(base2, full_key, "multi-node2")
    deadline = time.monotonic() + 15.0
    calls = 0
    while time.monotonic() < deadline:
        calls = int(psql(pg_container, "SELECT count(*) FROM calls WHERE key_id = :'key_id'", key_id=key_id))
        if calls >= 1:
            break
        time.sleep(0.5)
    assert calls >= 1, f"multi: no CallRecord for the node-1 key in the shared Postgres"
    log(f"PASS multi: key created on node 1 authenticates on node 2 (offline chat); shared Postgres holds"
        f" {calls} CallRecord(s) under the node-1 key")
    delete_key(base, master, key_id=key_id)
    log("PASS multi: admin key round-trip through node 1 + shared-DB ledger + cleanup")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--leg", required=True, choices=["memory", "postgres", "multi"])
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--base-url-2", default="http://127.0.0.1:8082")
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--pg-container", default="janus-postgres-1")
    args = parser.parse_args()

    if args.leg == "memory":
        leg_memory(args.base_url, args.master_key)
    elif args.leg == "postgres":
        leg_postgres(args.base_url, args.master_key, args.pg_container)
    else:
        leg_multi(args.base_url, args.base_url_2, args.master_key, args.pg_container)
    print(f"drill_compose ({args.leg}): ALL PASS")


if __name__ == "__main__":
    main()
