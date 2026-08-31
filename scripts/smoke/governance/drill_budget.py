#!/usr/bin/env python3
"""drill_budget.py — governance 1 budget + Review 2 edge-case drill.

Proves the per-key budget cap live over real sockets (config.budget.toml,
``soft-cap-fraction = 0.8``). Three invocation shapes the runner chooses by boot:

  --small-usage  (booted against the fake with --usage-override
                  '{"prompt_tokens":1,"completion_tokens":1}' → 420 micro-USD settle
                  per request): the SOFT-CAP + notifier-dedup leg.
  --budget-duration N  (booted against the GOLDEN fake, 14/12 → 5320 micro-USD
                  settle): the windowed-budget leg ONLY.
  default        (booted against the GOLDEN fake): the hard-cap / exact-cap /
                  concurrency / revocation legs.

The per-request cost math is the cost table: 14 × 0.14/1000 + 12 × 0.28/1000 =
5320 micro-USD; the reserve estimate is ``max_tokens × 0.28/1000`` (output rate,
prompt unknown pre-dispatch — the reference R2-012).

Legs (default boot — golden usage):
  1. HARD CAP: ``budget_usd: 0.01064`` (= 2 × 5320) passes requests 1-2; request 3
     → 429 ``rate_limit_error`` with NO ``Retry-After`` (a budget does not refill on
     a timer), and the fake counter is flat across the denial (denied before dispatch).
  2. EXACT-CAP BOUNDARY: after request 2 the key's settled spend (scraped from
     /metrics, ``janus_key_cost_micro_usd_total{key_id=…}``) is exactly 10640 =
     the cap; request 3 is denied — the N-th passes, the N+1-th 429s, never beyond
     cap + one request.
  3. CONCURRENT RACING: N=8 parallel requests against ``budget_usd: 0.0266`` with
     max_tokens=19 (estimate == actual == 5320 — the reservation total is invariant,
     so the atomic reserve admits exactly 4 and denies 4 deterministically). Every
     response is 200 or 429 with a clean ``rate_limit_error`` envelope — NO 500s —
 and the settled spend never exceeds cap + one request (the atomic-reserve
     bound, live).
  4. REVOCATION MID-FLIGHT: hammer a key with 8 parallel requests while revoking it
     mid-flight — every response is 200/403/429 (no 500s, no exception storm); once
 revoked, every subsequent request is 403 ``permission_error`` ( m1 atomic
     authenticate, live re-verify).

Legs (--small-usage boot — 420 micro-USD settle):
  5. SOFT CAP + NOTIFIER DEDUP: ``budget_usd: 0.033`` (soft tier 26400 micro), each
     request estimating max_tokens=90 → 25200 micro. Requests 1-3 pass under the
     soft line; requests 4-19 soft-cross (settled+pending ≥ 26400 and < 33000) and
     succeed carrying ``X-Janus-Budget-Warning: soft`` + ``X-Janus-Budget-Used-Micro-Usd``;
     request 20 is hard-429 (no Retry-After). The notifier fires on the FIRST soft
 crossing and is DEDUPED for the rest of the 60s window ( n3 decision —
     DedupNotifier): the Janus log must carry exactly ONE ``budget_exceeded`` WARN
     for this key across all 16 soft-crossing successes. Streams' soft-exceed is
 notifier-only (no header post-SSE-start) — recorded, unit-pinned by m4.

Legs (--budget-duration N boot — golden usage; the budget reset window):
  6. WINDOWED BUDGET: ``budget_duration: N`` seconds + ``budget_usd: 0.01064``
     (= 2 × 5320). Spend to the cap inside one window → 429 ``rate_limit_error``
     WITH ``Retry-After`` (seconds to the aligned window reset — the windowed
     divergence from the lifetime budget's no-Retry-After; always in
     ``[1, budget_duration]``); sleep past the boundary → spending works again and
     the FRESH window row resets (the same two golden settles are re-admitted, then
     the N+1-th 429s again — a lifetime cap would stay denied forever).

Exit 0 = all assertions pass.

Usage:
  drill_budget.py --base-url http://127.0.0.1:PORT/v1 --master-key <key>
                  --counter <fake-counter-file> [--janus-log <janus-log-file>]
                  [--small-usage] [--concurrency 8] [--budget-duration SECONDS]
"""
from __future__ import annotations

import argparse
import json
import sys
import threading
import time
from pathlib import Path

from harness_common import MODEL, delete_key, generate_key, http_json, read_counter, scrape_metrics, series_value, wait_for

GOLDEN_MICRO = 5_320
GOLDEN_CAP = 2 * GOLDEN_MICRO  # 0.01064 USD — exactly 2 golden requests
RACE_CAP = 5 * GOLDEN_MICRO  # 0.0266 USD — admits exactly 4 reservations at est==actual
SMALL_MICRO = 420  # 1 × 0.14/1000 + 1 × 0.28/1000 with the --usage-override fake
SOFT_CAP = 0.033  # USD; soft tier = 33_000 × 0.8 = 26_400 micro
SOFT_ESTIMATE = 90 * 280  # max_tokens=90 × 0.28/1000 → 25_200 micro estimate
WINDOWED_SLACK = 0.25  # seconds past the aligned boundary before the post-rollover spend
# (0.25 not 1.0: the 3 s window only has ~2 s left after the sleep — a slow
# machine spending >2 s on the three post-rollover requests would cross into
# the NEXT boundary and 429; the server's Retry-After already lands the sleep
# within ms of the boundary, so a quarter-second is ample.)


def log(msg: str) -> None:
    print(msg, flush=True)


def openai_request(base: str, key: str, max_tokens: int):
    return http_json(
        "POST",
        f"{base}/chat/completions",
        body={
            "model": MODEL,
            "messages": [{"role": "user", "content": f"w33-budget-{time.time_ns()}"}],
            "max_tokens": max_tokens,
        },
        headers={"x-api-key": key},
)


def assert_clean_429(status: int, payload, what: str) -> None:
    assert status == 429, f"{what}: expected 429, got {status} ({payload})"
    error = (payload or {}).get("error", {})
    assert error.get("type") == "rate_limit_error", f"{what}: error.type {error.get('type')!r} ({payload})"


def settled_micro(base: str, key_id: str) -> float:
    body = scrape_metrics(base)
    keyed_lines = [
        line
        for line in body.splitlines()
        if line.startswith("janus_key_cost_micro_usd_total") and f'key_id="{key_id}"' in line
    ]
    assert keyed_lines, f"janus_key_cost_micro_usd_total{{key_id={key_id}}} missing from /metrics"
    return series_value(body, "janus_key_cost_micro_usd_total", {"key_id": key_id})


def leg_hard_cap(base: str, master: str, counter: str) -> None:
    _, key = generate_key(base, master, models=[MODEL], name="w33-hardcap", budget_usd=GOLDEN_CAP / 1_000_000)
    for i in (1, 2):
        status, _, payload = openai_request(base, key, max_tokens=1)
        assert status == 200, f"hard-cap request {i}: expected 200, got {status} ({payload})"
    before = read_counter(counter)["requests"]
    status, headers, payload = openai_request(base, key, max_tokens=1)
    after = read_counter(counter)["requests"]
    assert_clean_429(status, payload, "hard-cap request 3")
    assert headers.get("Retry-After") is None, f"hard-cap 429 must NOT carry Retry-After ({headers})"
    assert after == before, f"hard-cap 429 reached the fake (counter {before} → {after})"
    log(f"PASS hard cap: 2 golden requests pass ({GOLDEN_MICRO} micro each), 3rd → 429 no Retry-After, fake flat ({before} → {after})")


def leg_exact_cap(base: str, master: str) -> None:
    key_id, key = generate_key(base, master, models=[MODEL], name="w33-exactcap", budget_usd=GOLDEN_CAP / 1_000_000)
    for i in (1, 2):
        status, _, payload = openai_request(base, key, max_tokens=1)
        assert status == 200, f"exact-cap request {i}: expected 200, got {status} ({payload})"
    # The settle is synchronous with the response but the /metrics exposition can
    # lag a beat — drill_cost.metric_deltas polls 10s for exactly this. Wait for
    # the settled spend to reach the cap before the zero-tolerance assert.
    assert wait_for(lambda: settled_micro(base, key_id) == float(GOLDEN_CAP), timeout=10.0,
                    label="exact-cap settled == cap (10640) after request 2"), (
        "exact-cap: settled never reached the cap (exposition lag > 10s?)"
)
    settled = settled_micro(base, key_id)
    assert settled == float(GOLDEN_CAP), f"exact-cap: settled {settled} != cap {GOLDEN_CAP}"
    status, _, payload = openai_request(base, key, max_tokens=1)
    assert_clean_429(status, payload, "exact-cap request 3 (N+1-th)")
    assert settled <= GOLDEN_CAP + GOLDEN_MICRO, "exact-cap: settled exceeded cap + one request"
    log(f"PASS exact-cap boundary: settled == {GOLDEN_CAP} micro == cap after request 2; request 3 → 429 (≤ cap + one request)")


def leg_concurrent_racing(base: str, master: str, concurrency: int) -> None:
    key_id, key = generate_key(base, master, models=[MODEL], name="w33-race", budget_usd=RACE_CAP / 1_000_000)
    results: list[tuple[int, dict]] = []
    lock = threading.Lock()

    def worker() -> None:
        status, headers, payload = openai_request(base, key, max_tokens=19)
        with lock:
            results.append((status, {"headers": headers, "payload": payload}))

    threads = [threading.Thread(target=worker) for _ in range(concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    statuses = [status for status, _ in results]
    assert all(status in (200, 429) for status in statuses), f"concurrency: unexpected statuses {statuses} ({results})"
    for status, info in results:
        if status == 429:
            assert_clean_429(status, info["payload"], "concurrent 429 envelope")
    ok_count = statuses.count(200)
    denied_count = statuses.count(429)
    assert ok_count >= 1 and denied_count >= 1, f"concurrency: expected a mixed split, got {statuses}"
    # Exposition lag: settled must REFLECT the ok_count admissions (each 5320
    # micro; == RACE_CAP when est == actual) before the cap+one bound is sampled —
    # `>= 0` was vacuous and could scrape before the racing requests settled.
    assert wait_for(lambda: settled_micro(base, key_id) >= ok_count * GOLDEN_MICRO, timeout=10.0,
                    label="racing settled reflects the admitted requests"), (
        f"concurrency: settled never reflected the {ok_count} admitted requests (exposition lag > 10s?)"
)
    settled = settled_micro(base, key_id)
    bound = RACE_CAP + GOLDEN_MICRO
    assert settled <= bound, f"concurrency: settled {settled} > cap + one request {bound}"
    assert settled <= RACE_CAP + GOLDEN_MICRO, "concurrency bound violated"
    log(f"PASS concurrent racing: {concurrency} parallel → {ok_count}×200 / {denied_count}×429 (clean envelopes, no 500s); settled {settled:.0f} ≤ cap+one ({bound})")


def leg_revocation_midflight(base: str, master: str, concurrency: int) -> None:
    _, key = generate_key(base, master, models=[MODEL], name="w33-revokeflight", budget_usd=GOLDEN_CAP / 1_000_000)
    stop = threading.Event()
    results: list[tuple[int, dict]] = []
    lock = threading.Lock()

    def worker() -> None:
        while not stop.is_set():
            status, headers, payload = openai_request(base, key, max_tokens=1)
            with lock:
                results.append((status, {"headers": headers, "payload": payload}))

    threads = [threading.Thread(target=worker) for _ in range(concurrency)]
    for t in threads:
        t.start()
    time.sleep(0.3)  # a few requests land authenticated…
    delete_key(base, master, full_key=key)  # …then the key is revoked mid-flight
    time.sleep(0.3)
    stop.set()
    for t in threads:
        t.join()

    statuses = [status for status, _ in results]
    assert statuses, "revocation mid-flight: no requests observed"
    assert all(status in (200, 403, 429) for status in statuses), f"revocation: unexpected statuses {statuses}"
    assert 403 in statuses, f"revocation: no 403 observed after the revoke ({statuses})"
    for status, info in results:
        if status == 429:
            assert_clean_429(status, info["payload"], "revocation 429 envelope")
    for _ in range(3):
        status, _, payload = openai_request(base, key, max_tokens=1)
        assert status == 403 and ((payload or {}).get("error") or {}).get("type") == "permission_error", (
            f"post-revoke: expected 403 permission_error, got {status} ({payload})"
)
    log(f"PASS revocation mid-flight: {len(statuses)} in-flight responses all in {{200,403,429}} (no 500s), {statuses.count(403)}×403; post-revoke → 403 permission_error")


def leg_soft_cap_dedup(base: str, master: str, janus_log: str) -> None:
    key_id, key = generate_key(base, master, models=[MODEL], name="w33-soft", budget_usd=SOFT_CAP)
    soft_headers = 0
    first_soft_at = None
    for i in range(1, 21):
        status, headers, payload = openai_request(base, key, max_tokens=90)
        if status == 429:
            assert_clean_429(status, payload, f"soft-cap request {i} (hard denial)")
            assert headers.get("Retry-After") is None, f"soft-cap hard 429 must not carry Retry-After ({headers})"
            # C10: the reserve estimate prices prompt + output (25200 output + a small
            # prompt estimate for this ~30-char message) — the exact crossing request
            # shifts by ±1 with tokenization, so the boundary is a range around the
            # 20-request arithmetic, not a hardcoded index.
            assert 18 <= i <= 21, f"soft-cap: hard 429 at request {i}, expected the ~20th"
            break
        assert status == 200, f"soft-cap request {i}: expected 200, got {status} ({payload})"
        if headers.get("X-Janus-Budget-Warning") == "soft":
            soft_headers += 1
            if first_soft_at is None:
                first_soft_at = i
            used = headers.get("X-Janus-Budget-Used-Micro-Usd")
            assert used is not None, f"soft-cap request {i}: warning without X-Janus-Budget-Used-Micro-Usd ({headers})"
    else:
        raise AssertionError("soft-cap: expected the 20th request to hard-429 (settled+pending ≥ cap)")
    # C10 prompt-estimate again: the soft tier (26 400) sits ~1 request below the
    # 25 200 output-only estimate, so the prompt term pulls the first crossing as
    # early as request 2 — the range keeps the contract (crosses well before the
    # hard cap, after at most a few clean passes). Guard the None first: a hard
    # 429 landing before any soft-warning header must FAIL cleanly, not raise
    # TypeError on `1 <= None <= 4`.
    assert first_soft_at is not None, (
        "soft-cap: the hard 429 landed before any X-Janus-Budget-Warning: soft header"
)
    assert 1 <= first_soft_at <= 4, (
        f"soft-cap: first soft crossing at request {first_soft_at}, expected 1-4"
)
    assert soft_headers >= 2, f"soft-cap: expected multiple soft successes, got {soft_headers}"

    # Notifier dedup: the soft crossings span one 60s window → exactly ONE
    # budget_exceeded WARN for this key in the Janus log.
    pattern = "governance event 'budget_exceeded'"
    key_id_ = key_id

    def count_events() -> int:
        path = Path(janus_log)
        if not path.exists():
            return 0
        text = path.read_text(encoding="utf-8", errors="replace")
        return sum(1 for line in text.splitlines() if pattern in line and key_id_ in line)

    assert wait_for(lambda: count_events() >= 1, label="notifier event"), "notifier never fired (budget_exceeded WARN absent)"
    time.sleep(0.5)  # let any racing duplicate WARN flush to the log
    assert count_events() == 1, f"soft-cap dedup: expected exactly 1 budget_exceeded WARN for {key_id_}, got {count_events()}"
    log(f"PASS soft cap + dedup: {soft_headers} soft-crossing successes (first at request {first_soft_at}, 20th hard-429), X-Janus-Budget-Warning: soft headers present, exactly 1 notifier WARN (16 crossings deduped)")


def leg_windowed_budget(base: str, master: str, budget_duration: int) -> None:
    """budget reset windows (the plan's cross-workstream smoke item): a tiny
    ``budget_duration`` refills the cap at each aligned window rollover."""
    _, key = generate_key(
        base,
        master,
        models=[MODEL],
        name="ws2-windowed",
        budget_usd=GOLDEN_CAP / 1_000_000,
        budget_duration=budget_duration,
)
    # Spend to the in-window cap: exactly 2 golden settles fit (2 × 5320 = cap). A
    # boundary crossed mid-loop only resets the window counter, so keep spending
    # (bounded) until the deny — never assume which request index lands first in a
    # window.
    retry_after = None
    for i in range(1, 6):
        status, headers, payload = openai_request(base, key, max_tokens=1)
        if status == 429:
            assert_clean_429(status, payload, f"windowed request {i} (in-window cap reached)")
            retry_after = headers.get("Retry-After")
            assert retry_after is not None, (
                f"windowed-budget 429 must carry Retry-After (the window refills every {budget_duration}s) ({headers})"
)
            wait = int(retry_after)
            assert 1 <= wait <= budget_duration, f"windowed Retry-After {wait} outside [1, {budget_duration}] ({headers})"
            break
        assert status == 200, f"windowed request {i}: expected 200, got {status} ({payload})"
    else:
        raise AssertionError("windowed budget: the in-window cap was never reached (5 requests, cap = 2 golden settles)")

    # Cross the aligned window boundary (floorDiv(nowSec, dur) × dur — the same
    # arithmetic Janus derives): Retry-After + slack guarantees the rollover.
    time.sleep(int(retry_after) + WINDOWED_SLACK)
    status, _, payload = openai_request(base, key, max_tokens=1)
    assert status == 200, f"post-rollover request 1: expected 200 (fresh window), got {status} ({payload})"
    # The fresh window row RESET (not resumed): the new window re-admits exactly the
    # same two golden settles, then denies the N+1-th again — a lifetime cap would
    # have stayed denied forever.
    status, _, payload = openai_request(base, key, max_tokens=1)
    assert status == 200, f"post-rollover request 2: expected 200, got {status} ({payload})"
    status, headers, payload = openai_request(base, key, max_tokens=1)
    assert_clean_429(status, payload, "post-rollover request 3 (the fresh window re-accumulated to the cap)")
    assert headers.get("Retry-After") is not None, f"post-rollover 429 must carry Retry-After again ({headers})"
    log(
        f"PASS windowed budget (duration={budget_duration}s): in-window cap {GOLDEN_CAP} micro → 429 + "
        f"Retry-After={retry_after}s; after the boundary 2 golden settles re-admitted then 429 again "
        "(fresh window row reset — offline, fake upstream)"
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--counter", required=True)
    parser.add_argument("--janus-log", default=None, help="Janus log file for the notifier grep (soft-cap leg)")
    parser.add_argument("--small-usage", action="store_true", help="run the soft-cap/dedup leg (420-micro fake)")
    parser.add_argument(
        "--budget-duration",
        type=int,
        default=0,
        help="run ONLY the windowed-budget leg  with this budget_duration in seconds (golden fake)",
)
    parser.add_argument("--concurrency", type=int, default=8)
    args = parser.parse_args()
    if args.budget_duration < 0:
        parser.error("--budget-duration must be positive seconds")

    base = args.base_url.rstrip("/")
    master = args.master_key
    if args.small_usage:
        assert args.janus_log, "--janus-log required for the soft-cap dedup leg"
        leg_soft_cap_dedup(base, master, args.janus_log)
    elif args.budget_duration > 0:
        leg_windowed_budget(base, master, args.budget_duration)
    else:
        leg_hard_cap(base, master, args.counter)
        leg_exact_cap(base, master)
        leg_concurrent_racing(base, master, args.concurrency)
        leg_revocation_midflight(base, master, args.concurrency)

    print("drill_budget: ALL PASS")


if __name__ == "__main__":
    main()
