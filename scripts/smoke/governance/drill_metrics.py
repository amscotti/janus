#!/usr/bin/env python3
"""drill_metrics.py — governance 3 metrics + privacy drill.

Runs a mixed workload over real sockets (config.fake.toml boot; OpenAI + Anthropic
faces, streaming + non-streaming, plus an unknown-key 401 and a revoked-key 403 so the
filter-level rejections land in the 4xx bucket), then scrapes GET /metrics and asserts
the Tier-1 contract on the LIVE HTTP exposition:

  - every Tier-1 series is present with a non-zero value after the workload:
      janus_requests_total (2xx AND 4xx — the 4xx bucket includes the filter-level
 401/403 rejections, m2 decision: recorded from KeyAuthFilter),
      janus_request_duration_seconds count + sum, and the percentile-histogram
 _bucket lines (le=…, le="+Inf") — the m3 wording decision (histogram,
      not just count/sum/max),
      janus_tokens_in_total / janus_tokens_out_total / janus_cost_micro_usd_total
      (non-zero after the include_usage stream),
      janus_key_requests_total / janus_key_tokens_* / janus_key_cost_micro_usd_total
      with the key_id label,
      janus_upstream_healthy{provider="openai-compatible"} == 1,
      janus_upstream_breaker_state{provider="openai-compatible"} == 0;
 - /prometheus → 404 (the path pin);
  - PRIVACY CONTRACT live: a distinctive marker string planted in a prompt AND the
    golden response text (the fake's committed body) appear NOWHERE in the exposition
    — no prompt/response text in any series, HELP or TYPE line.

Usage:
  drill_metrics.py --base-url http://127.0.0.1:PORT/v1 --master-key <key>
                   [--rounds 2] [--expect-4xx]   (--expect-4xx off on the auth-off
                   regression boot: no keyed series and no 4xx from auth rejections)
"""
from __future__ import annotations

import argparse
import secrets
import sys

from anthropic import Anthropic
from openai import OpenAI

from harness_common import (
    DUMMY_KEY,
    FIXTURE_CONTENT,
    MODEL,
    delete_key,
    generate_key,
    http_json,
    http_text,
    scrape_metrics,
    sdk_base_url,
    series_present,
    series_value,
)

TIER1 = [
    "janus_requests_total",
    "janus_request_duration_seconds",
    "janus_tokens_in_total",
    "janus_tokens_out_total",
    "janus_cost_micro_usd_total",
    "janus_key_requests_total",
    "janus_key_tokens_in_total",
    "janus_key_tokens_out_total",
    "janus_key_cost_micro_usd_total",
    "janus_upstream_healthy",
    "janus_upstream_breaker_state",
]


def log(msg: str) -> None:
    print(msg, flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--rounds", type=int, default=2)
    parser.add_argument("--expect-4xx", action="store_true", help="auth-on boot: 4xx bucket from filter rejections")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    marker = f"PH4PRIVMARK-{secrets.token_hex(6)}"

    _, key = generate_key(base, args.master_key, models=[MODEL], name="w33-metrics")
    _, revoked_key = generate_key(base, args.master_key, models=[MODEL], name="w33-metrics-rev")
    delete_key(base, args.master_key, full_key=revoked_key)
    openai = OpenAI(base_url=base, api_key=key, timeout=30.0, max_retries=0)
    anthropic = Anthropic(base_url=sdk_base_url(base), api_key=key, timeout=30.0, max_retries=0)

    # ---- mixed workload -----------------------------------------------------
    for i in range(args.rounds):
        prompt = f"w33-metrics-{i} {marker}"
        resp = openai.chat.completions.create(model=MODEL, messages=[{"role": "user", "content": prompt}])
        assert resp.choices[0].message.content, "openai non-stream returned empty content"
        stream = openai.chat.completions.create(
            model=MODEL,
            messages=[{"role": "user", "content": f"mode=stream w33-metrics-s-{i}"}],
            stream=True,
            stream_options={"include_usage": True},
)
        for _chunk in stream:
            pass
        resp = anthropic.messages.create(
            model=MODEL, max_tokens=1024, messages=[{"role": "user", "content": f"w33-metrics-a-{i}"}]
)
        assert resp.content, "anthropic non-stream returned empty content"
    # filter-level rejections
    http_json(
        "POST",
        f"{base}/chat/completions",
        body={"model": MODEL, "messages": [{"role": "user", "content": "w33-metrics-unknown"}]},
        headers={"x-api-key": "sk-janus-unknown-00000000"},
)
    http_json(
        "POST",
        f"{base}/chat/completions",
        body={"model": MODEL, "messages": [{"role": "user", "content": "w33-metrics-revoked"}]},
        headers={"x-api-key": revoked_key},
)
    log(f"PASS mixed workload ({args.rounds} rounds × oo/oo-stream/ao + 401 + 403 rejections); prompt marker {marker} planted")

    # ---- scrape + Tier-1 inventory -------------------------------------------
    body = scrape_metrics(base)
    for name in TIER1:
        if name == "janus_request_duration_seconds":
            # Prometheus exports a Timer as _count/_sum/_max/_bucket samples
            # only — the bare base name is never a sample line, so exact-name
            # series_value is always 0 for it (prefix-summing the siblings
            # would mix count+seconds into a meaningless aggregate, m4).
            # Assert the timer is active via its count and sum lines directly.
            count = series_value(body, "janus_request_duration_seconds_count")
            sum_seconds = series_value(body, "janus_request_duration_seconds_sum")
            assert count > 0, f"Tier-1 timer {name} missing or zero after the workload (_count {count})"
            assert sum_seconds > 0, f"Tier-1 timer {name} missing or zero after the workload (_sum {sum_seconds})"
            log(f"    {name} = {sum_seconds} s (sum) across {count:g} events (count)")
            continue
        value = series_value(body, name)
        if name == "janus_upstream_breaker_state":
            # CLOSED is 0 — the design's "non-zero" wording covers the activity
            # counters; the breaker gauge is asserted exactly below.
            assert value == 0.0, f"janus_upstream_breaker_state != 0 (CLOSED) after the workload ({value})"
            log(f"    {name} = {value} (CLOSED)")
            continue
        assert value > 0, f"Tier-1 series {name} missing or zero after the workload ({value})"
        log(f"    {name} = {value}")
    assert series_value(body, "janus_upstream_healthy", {"provider": "openai-compatible"}) == 1.0, (
        "janus_upstream_healthy{provider=openai-compatible} != 1"
)
    assert series_value(body, "janus_upstream_breaker_state", {"provider": "openai-compatible"}) == 0.0, (
        "janus_upstream_breaker_state{provider=openai-compatible} != 0 (CLOSED)"
)
    # histogram buckets
    assert series_present(body, "janus_request_duration_seconds_bucket"), (
        "no janus_request_duration_seconds_bucket lines — percentile histogram not exported"
)
    assert any('le="+Inf"' in line for line in body.splitlines() if "janus_request_duration_seconds_bucket" in line), (
        "no le=\"+Inf\" bucket line"
)
    if args.expect_4xx:
        assert series_value(body, "janus_requests_total", {"status": "4xx"}) >= 1.0, (
 "janus_requests_total{status=4xx} missing — filter-level 401/403 not recorded ( m2)"
)
    log("PASS all Tier-1 series non-zero; histogram _bucket lines exported; gauges healthy=1 breaker=0; 4xx bucket includes filter rejections")

    # ---- privacy contract -----------------------------------------------------
    assert marker not in body, f"PRIVACY VIOLATION: prompt marker {marker} found in /metrics"
    assert FIXTURE_CONTENT not in body, f"PRIVACY VIOLATION: response text found in /metrics"
    for name in TIER1:
        assert marker not in name and FIXTURE_CONTENT not in name, f"PRIVACY VIOLATION in series name {name}"
    log("PASS privacy contract: prompt marker + golden response text absent from the entire exposition")

    # ---- /prometheus 404 ------------------------------------------------------
    status, _, raw = http_text(f"{base.replace('/v1', '')}/prometheus")
    assert status == 404, f"/prometheus: expected 404, got {status}"
    log("PASS /prometheus → 404 (path pin)")

    print("drill_metrics: ALL PASS")


if __name__ == "__main__":
    main()
