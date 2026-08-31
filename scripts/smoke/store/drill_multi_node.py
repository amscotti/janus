#!/usr/bin/env python3
"""drill_multi_node.py — store gate 2 multi-node parity drill.

Two real Janus nodes (A, B) sharing ONE Postgres ([janus.store] type = "postgres"
in config.nodeA.toml + config.nodeB.toml), one golden fake upstream behind both
(same committed corpus — usage 14/12 → 5320 micro-USD per request, DeepSeek
0.14/0.28 per 1K). Asserts the shared-DB consistency model live over real sockets:

  (a) key lifecycle across nodes — /key/generate on A authenticates on B (both
      faces, streaming + non-streaming, golden content) and vice versa;
  (b) shared RPM — an rpm: 2 key's requests alternate A/B and the 3rd 429s with
      Retry-After REGARDLESS of which node serves it (shared fixed-window
      counters — atomic upserts, no overshoot); the throttled request never
      dispatches (fake counter flat);
  (c) shared budget — a budget_usd: 0.01064 key's requests split across A/B settle
      exactly ≤ cap + one request cluster-wide; the 3rd 429s BEFORE dispatch
      (fake counter flat) and the shared Postgres spend row lands on exactly the
      cap with zero pending;
  (d) spend aggregation — N successful requests across both nodes: the shared
      Postgres spend total == N × 5320 micro-USD == the sum of the two nodes'
      per-key janus_key_cost_micro_usd_total scrapes (manual math printed);
  (e) call-ring cross-node view — the shared calls table holds one CallRecord per
      settled request under the key's id (records from BOTH nodes, newest-first
      seq order, bounded by retention — the drill's few records << 1000).

DB assertions shell out to ``docker exec <container> psql`` (the runner's drill
Postgres) — stdlib-only, no Python Postgres driver.

Exit 0 = all assertions pass; any failure prints the reason and exits nonzero.

Usage:
  drill_multi_node.py --base-url-a http://127.0.0.1:PORT/v1 --base-url-b http://127.0.0.1:PORT/v1
                      --master-key <key> --counter <fake-counter-file>
                      --pg-container <docker-container-name> [--pg-user janus] [--pg-db janus]
"""
from __future__ import annotations

import argparse
import subprocess
import sys
import time

from anthropic import Anthropic
from openai import OpenAI

from harness_common import (
    FIXTURE_CONTENT,
    GOLDEN_IN,
    GOLDEN_MICRO,
    GOLDEN_OUT,
    MODEL,
    generate_key,
    http_json,
    read_counter,
    scrape_metrics,
    sdk_base_url,
    series_value,
)


def raw_model_request(base: str, key: str):
    """One raw OpenAI-face chat completion with the given key (used for the 429 legs —
    the SDK raises on 429, the raw call lets us assert the envelope + headers)."""
    return http_json(
        "POST",
        f"{base}/chat/completions",
        body={
            "model": MODEL,
            "messages": [{"role": "user", "content": f"w42-raw-{time.time_ns()}"}],
            "max_tokens": 1024,
        },
        headers={"x-api-key": key},
)

WINDOW_SECONDS = 60
BUDGET_CAP_MICRO = 10_640  # 0.01064 USD = 2 × 5320 (the exact-cap budget the plan pins)


def log(msg: str) -> None:
    print(msg, flush=True)


def current_aligned_window() -> int:
    """The epoch-aligned fixed-window start (seconds) — mirrors PgRateLimiter.windowStart."""
    now = int(time.time())
    return now - (now % WINDOW_SECONDS)


def assert_counter_exact(pg, user, db, key_id: str, total_consumed: int, label: str) -> None:
    """The shared fixed-window RPM counter stayed EXACT across a 3-request leg against an
    rpm:2 key: total_consumed requests spread over the two touched windows (current +
    previous) with no single window ever exceeding the rpm cap — the "denied never
    consumes" invariant (429 path) or the exactness of a mid-leg window rollover (200)."""
    windows = [current_aligned_window() - WINDOW_SECONDS, current_aligned_window()]
    total = 0
    mx = 0
    for w in windows:
        value = psql_int(
            pg,
            f"SELECT COALESCE(SUM(count), 0) FROM rate_limits WHERE key_id = '{key_id}'"
            f" AND dimension = 'requests' AND window_start = {w}",
            user,
            db,
)
        total += value
        mx = max(mx, value)
    assert total == total_consumed, f"{label}: total consumed {total} != {total_consumed}"
    assert mx <= 2, f"{label}: a single window exceeded the rpm:2 cap ({mx})"


def assert_rpm_exact(pg, user, db, key_id: str, status, headers, payload, before: int, after: int) -> str:
    """Assert a 3rd-request RPM outcome against the shared fixed-window counter.

    Either the expected 429 (OpenAI-face rate_limit_error + Retry-After ∈ [1,60] + the
    fake counter FLAT — never dispatched) OR the fixed window rolled between the
    requests (the documented counter reset), in which case the DB proves the reset was
    exact (all three consumed, no window over cap). Returns a short human description.
    """
    error = (payload or {}).get("error", {})
    if status == 429:
        assert error.get("type") == "rate_limit_error", f"rpm 429 envelope wrong: {payload}"
        retry_after = headers.get("Retry-After") or headers.get("retry-after")
        assert retry_after is not None, f"rpm 429 missing Retry-After: {headers}"
        assert 1 <= int(retry_after) <= WINDOW_SECONDS, f"Retry-After {retry_after} outside [1, 60]"
        assert after == before, f"rpm 429 request reached the fake (counter {before} → {after})"
        assert_counter_exact(pg, user, db, key_id, 2, "the denied 3rd request never consumed")
        return f"429 rate_limit_error + Retry-After={int(retry_after)}; fake counter flat ({before} → {after})"
    assert status == 200, f"rpm 3rd request: expected 429 or an exact rollover, got {status} ({payload})"
    assert after == before + 1, f"rollover 3rd dispatched, fake counter {before} → {after}"
    assert_counter_exact(pg, user, db, key_id, 3, "a rollover let the 3rd consume against a fresh window")
    return f"200 after an exact fixed-window rollover (DB-proven); the 3rd dispatched ({before} → {after})"


def psql(container: str, sql: str, user: str = "janus", db: str = "janus") -> str:
    out = subprocess.run(
        ["docker", "exec", container, "psql", "-U", user, "-d", db, "-tAc", sql],
        capture_output=True,
        text=True,
)
    assert out.returncode == 0, f"psql failed (container {container}): {out.stderr.strip()}"
    return out.stdout.strip()


def psql_int(container: str, sql: str, user: str = "janus", db: str = "janus") -> int:
    value = psql(container, sql, user, db)
    try:
        return int(value)
    except ValueError as exc:
        raise AssertionError(f"psql returned non-integer {value!r} for {sql}") from exc


def wait_for(fn, timeout: float = 15.0, interval: float = 0.3, label: str = "condition") -> None:
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            if fn():
                return
        except AssertionError as exc:
            last = exc
        time.sleep(interval)
    raise AssertionError(f"timeout waiting for {label}" + (f": {last}" if last else ""))


def make_openai(base_url: str, api_key: str) -> OpenAI:
    return OpenAI(base_url=base_url, api_key=api_key, timeout=30.0, max_retries=0)


def make_anthropic(base_url: str, api_key: str) -> Anthropic:
    return Anthropic(base_url=sdk_base_url(base_url), api_key=api_key, timeout=30.0, max_retries=0)


def keyed_cost_sum(base_a: str, base_b: str, key_id: str) -> float:
    """Sum the two nodes' per-key cost series — the per-node-metrics-sum guidance."""
    total = 0.0
    for base in (base_a, base_b):
        body = scrape_metrics(base)
        total += series_value(body, "janus_key_cost_micro_usd_total", {"key_id": key_id})
    return total


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url-a", required=True)
    parser.add_argument("--base-url-b", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--counter", required=True, help="fake upstream counter file (flat-dispatch proof)")
    parser.add_argument("--pg-container", required=True)
    parser.add_argument("--pg-user", default="janus")
    parser.add_argument("--pg-db", default="janus")
    args = parser.parse_args()

    a = args.base_url_a.rstrip("/")
    b = args.base_url_b.rstrip("/")
    master = args.master_key
    pg = args.pg_container

    log(f"node A: {a}   node B: {b}   drill Postgres container: {pg}")
    log(f"manual math: golden usage {GOLDEN_IN}/{GOLDEN_OUT} × DeepSeek 0.14/0.28 per 1K"
        f" = {GOLDEN_MICRO} micro-USD per request; budget cap 0.01064 USD = {BUDGET_CAP_MICRO} micro-USD = 2 requests")

    # ----------------------------------------------------------------- (a) keys
    log("\n(a) key lifecycle across nodes")
    key_id, full_key = generate_key(a, master, models=[MODEL], name="w42-lifecycle")
    openai_b = make_openai(b, full_key)
    anthropic_b = make_anthropic(b, full_key)

    resp = openai_b.chat.completions.create(model=MODEL, messages=[{"role": "user", "content": "w42-key-a2b-o"}])
    assert resp.choices[0].message.content == FIXTURE_CONTENT, "key from A: openai non-stream on B golden content"
    assert resp.usage.prompt_tokens == GOLDEN_IN and resp.usage.completion_tokens == GOLDEN_OUT, resp.usage
    log("PASS /key/generate on A → sk-janus key succeeds on B (OpenAI non-stream, golden usage 14/12)")

    resp = anthropic_b.messages.create(
        model=MODEL, max_tokens=1024, messages=[{"role": "user", "content": "w42-key-a2b-a"}]
)
    texts = [block.text for block in resp.content if getattr(block, "type", None) == "text"]
    assert "".join(texts) == FIXTURE_CONTENT, "key from A: anthropic non-stream on B golden content"
    log("PASS key from A succeeds on B (Anthropic non-stream, golden content)")

    deltas = []
    stream = openai_b.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": "w42-key-a2b-stream"}],
        stream=True,
        stream_options={"include_usage": True},
)
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
            deltas.append(chunk.choices[0].delta.content)
    assert len(deltas) >= 2, f"key from A: openai stream on B deltas {len(deltas)}"
    assert "".join(deltas) == FIXTURE_CONTENT, "key from A: streamed content on B == golden"
    log("PASS key from A succeeds on B (OpenAI stream, include_usage — terminal-chunk settle)")

    key_b, full_key_b = generate_key(b, master, models=[MODEL], name="w42-lifecycle-b")
    openai_a = make_openai(a, full_key_b)
    resp = openai_a.chat.completions.create(model=MODEL, messages=[{"role": "user", "content": "w42-key-b2a-o"}])
    assert resp.choices[0].message.content == FIXTURE_CONTENT, "key from B: openai non-stream on A golden content"
    log("PASS /key/generate on B → sk-janus key succeeds on A (symmetric)")

    wait_for(
        lambda: psql_int(pg, f"SELECT count(*) FROM calls WHERE key_id = '{key_id}'", args.pg_user, args.pg_db) == 3,
        label=f"3 call records for {key_id} (the 3 settled requests on B)",
)
    log("PASS shared calls table: 3 CallRecords under the key from A (written by node B)")

    # ----------------------------------------------------------------- (b) RPM
    log("\n(b) shared RPM (fixed-window, exact cross-node)")
    rpm_id, rpm_key = generate_key(a, master, models=[MODEL], name="w42-rpm", rpm=2)
    rpm_openai_a = make_openai(a, rpm_key)
    rpm_openai_b = make_openai(b, rpm_key)
    for label, node, base, client in (
        ("1→A", "A", a, rpm_openai_a),
        ("2→B", "B", b, rpm_openai_b),
):
        _ = label
        resp = client.chat.completions.create(
            model=MODEL, messages=[{"role": "user", "content": f"w42-rpm-{node}-{time.time_ns()}"}]
)
        assert resp.choices, f"rpm request on {node}: expected 200 with choices"
    before = read_counter(args.counter)["requests"]
    status, headers, payload = raw_model_request(a, rpm_key)  # 3rd request → node A
    after = read_counter(args.counter)["requests"]
    # Window-pinned: the 3rd must 429 (same-window exactness) OR the fixed window rolled
    # between the requests — the DB proves the reset was exact either way (no flake on
    # the epoch-aligned boundary).
    outcome = assert_rpm_exact(pg, args.pg_user, args.pg_db, rpm_id, status, headers, payload, before, after)
    log(f"PASS shared RPM: requests 1→A 2→B passed, 3rd (served by A) → {outcome}")

    # mirrored alternation (3rd served by B)
    rpm2_id, rpm_key2 = generate_key(a, master, models=[MODEL], name="w42-rpm2", rpm=2)
    rpm2_openai_b = make_openai(b, rpm_key2)
    rpm2_openai_a = make_openai(a, rpm_key2)
    for node, client in (("B", rpm2_openai_b), ("A", rpm2_openai_a)):
        resp = client.chat.completions.create(
            model=MODEL, messages=[{"role": "user", "content": f"w42-rpm2-{node}-{time.time_ns()}"}]
)
        assert resp.choices, f"rpm2 request on {node}: expected 200"
    before = read_counter(args.counter)["requests"]
    status, headers, payload = raw_model_request(b, rpm_key2)
    after = read_counter(args.counter)["requests"]
    outcome = assert_rpm_exact(pg, args.pg_user, args.pg_db, rpm2_id, status, headers, payload, before, after)
    log(f"PASS shared RPM mirrored: requests 1→B 2→A passed, 3rd (served by B) → {outcome}"
        " — the shared counter is node-agnostic")

    # ----------------------------------------------------------------- (c) budget
    log("\n(c) shared budget (exact cross-node, no overspend)")
    budget_id, budget_key = generate_key(a, master, models=[MODEL], name="w42-budget", budget_usd=0.01064)
    budget_openai_a = make_openai(a, budget_key)
    budget_openai_b = make_openai(b, budget_key)
    for node, client in (("A", budget_openai_a), ("B", budget_openai_b)):
        resp = client.chat.completions.create(
            model=MODEL,
            # tiny reserve: the golden actual is 26 tokens and the estimate prices
            # prompt + output — with max_tokens=1 the reserve stays ~2 000 micro
            # so TWO reserves fit the 10 640 cap and the third is denied pre-dispatch.
            max_tokens=1,
            messages=[{"role": "user", "content": f"w42-budget-{node}-{time.time_ns()}"}],
)
        assert resp.choices, f"budget request on {node}: expected 200"
    wait_for(
        lambda: psql_int(pg, f"SELECT settled FROM spend WHERE key_id = '{budget_id}'", args.pg_user, args.pg_db)
        == BUDGET_CAP_MICRO,
        label="budget settled == cap (10640) after the two 200s",
)
    before = read_counter(args.counter)["requests"]
    status, headers, payload = raw_model_request(a, budget_key)  # 3rd → node A
    after = read_counter(args.counter)["requests"]
    assert status == 429, f"budget 3rd request: expected 429, got {status} ({payload})"
    error = (payload or {}).get("error", {})
    assert error.get("type") == "rate_limit_error", f"budget 429 envelope wrong: {payload}"
    assert "Retry-After" not in headers and "retry-after" not in headers, (
        "a budget cap does not refill on a timer — no Retry-After on the budget 429"
)
    assert after == before, f"budget 429 request reached the fake (counter {before} → {after}) — must 429 BEFORE dispatch"
    settled = psql_int(pg, f"SELECT settled FROM spend WHERE key_id = '{budget_id}'", args.pg_user, args.pg_db)
    pending = psql_int(pg, f"SELECT pending FROM spend WHERE key_id = '{budget_id}'", args.pg_user, args.pg_db)
    assert settled == BUDGET_CAP_MICRO, f"budget settled {settled} != cap {BUDGET_CAP_MICRO}"
    assert pending == 0, f"budget pending {pending} != 0 after the denials"
    log(f"PASS shared budget: 2 requests split A/B settled exactly {BUDGET_CAP_MICRO} micro-USD (= cap, no overspend);"
        f" 3rd (served by A) → 429 BEFORE dispatch (fake flat), no Retry-After; DB settled={settled} pending={pending}")

    # ----------------------------------------------------------------- (d) spend
    log("\n(d) spend aggregation (DB total == per-node metrics sum == manual math)")
    spend_id, spend_key = generate_key(
        a, master, models=[MODEL], name="w42-spend", budget_usd=1.0
)  # budgeted: the shared spend row is created by reserve/settle (unbudgeted keys skip it)
    spend_openai_a = make_openai(a, spend_key)
    spend_openai_b = make_openai(b, spend_key)
    spend_anthropic_b = make_anthropic(b, spend_key)
    n = 0
    resp = spend_openai_a.chat.completions.create(
        model=MODEL, max_tokens=16, messages=[{"role": "user", "content": "w42-spend-1"}]
)
    assert resp.usage.prompt_tokens == GOLDEN_IN, resp.usage
    n += 1
    resp = spend_anthropic_b.messages.create(
        model=MODEL, max_tokens=16, messages=[{"role": "user", "content": "w42-spend-2"}]
)
    n += 1
    stream = spend_openai_b.chat.completions.create(
        model=MODEL,
        max_tokens=16,
        messages=[{"role": "user", "content": "w42-spend-3"}],
        stream=True,
        stream_options={"include_usage": True},
)
    for _chunk in stream:
        pass
    n += 1
    expected = n * GOLDEN_MICRO
    log(f"manual math: {n} successful requests across both nodes × {GOLDEN_MICRO} micro-USD = {expected} micro-USD")

    wait_for(
        lambda: psql_int(pg, f"SELECT settled FROM spend WHERE key_id = '{spend_id}'", args.pg_user, args.pg_db)
        == expected,
        label=f"DB spend settled == {expected}",
)
    db_total = psql_int(pg, f"SELECT settled FROM spend WHERE key_id = '{spend_id}'", args.pg_user, args.pg_db)
    assert db_total == expected, f"DB spend {db_total} != manual {expected}"
    log(f"PASS shared Postgres spend table: settled == {db_total} == manual math")

    wait_for(
        lambda: abs(keyed_cost_sum(a, b, spend_id) - expected) < 0.001,
        label=f"per-node metrics sum == {expected}",
)
    metrics_sum = keyed_cost_sum(a, b, spend_id)
    assert abs(metrics_sum - expected) < 0.001, f"metrics sum {metrics_sum} != manual {expected}"
    log(f"PASS per-node metrics sum: A + B janus_key_cost_micro_usd_total == {metrics_sum} == DB total == manual math"
        " (the per-node-metrics-sum guidance — scrape every node and sum externally)")

    # ----------------------------------------------------------------- (e) calls
    log("\n(e) call-ring cross-node view (shared calls table)")
    calls = psql_int(pg, f"SELECT count(*) FROM calls WHERE key_id = '{spend_id}'", args.pg_user, args.pg_db)
    assert calls == n, f"shared calls table: expected {n} records for {spend_id}, got {calls}"
    newest = psql(
        pg,
        f"SELECT status || ':' || stream FROM calls WHERE key_id = '{spend_id}'"
        " ORDER BY at_epoch_millis DESC, seq DESC LIMIT 3",
        args.pg_user,
        args.pg_db,
).splitlines()
    assert len(newest) == n, f"newest-first call rows: {newest}"
    assert all(row.split(":")[0] == "OK" for row in newest), f"call statuses not all OK: {newest}"
    log(f"PASS shared calls table holds {calls} CallRecords for the key (requests hit BOTH nodes; newest-first,"
        f" all OK; retention 1000 ≫ {calls} — nothing pruned): {newest}")

    print("drill_multi_node: ALL PASS")


if __name__ == "__main__":
    main()
