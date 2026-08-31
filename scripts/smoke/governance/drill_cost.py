#!/usr/bin/env python3
"""drill_cost.py — governance 2 exact-cost drill ().

Proves cost-on-a-real-request matches manual price×usage EXACTLY against the DeepSeek
pricing table, over real sockets (config.fake.toml — DeepSeek rates 0.14 input / 0.28
output per 1K, the fake serving the committed golden usage 14/12):

    manual math:  14 × 0.14/1000 + 12 × 0.28/1000 = 0.00196 + 0.00336
                = 0.00532 USD = 5320 micro-USD (exact integer ledger)

Each leg snapshots /metrics BEFORE the request and asserts the AFTER delta on the
scraped exposition with ZERO tolerance (the design's "matches manual price×usage
calculation exactly"):

  - OpenAI non-stream:  delta janus_cost_micro_usd_total == 5320.0,
                        janus_tokens_in_total == 14.0, janus_tokens_out_total == 12.0,
                        and the keyed series (janus_key_cost_micro_usd_total{key_id=…})
                        carry the same 5320.0 (this drill's key is the only user).
  - Anthropic non-stream: same 5320.0 through the ao translation (cross-format).
  - OpenAI stream WITH stream_options.include_usage: settles from the terminal usage
                        chunk → 5320.0 (the fake serves the usage frame only when the
                        client asked — Janus never forces include_usage upstream).
  - OpenAI stream WITHOUT include_usage: documented $0 entry (tokens/cost delta 0).
  - Anthropic stream:   the anthropic SDK request carries no include_usage → the fake
                        drops the terminal usage frame → documented $0 entry (the
                        m4 zero-token + Anthropic legs pin the same in-suite).

Usage:
  drill_cost.py --base-url http://127.0.0.1:PORT/v1 --master-key <key> [--rounds 1]
"""
from __future__ import annotations

import argparse
import sys
import time

from anthropic import Anthropic
from openai import OpenAI

from harness_common import (
    DUMMY_KEY,
    GOLDEN_IN,
    GOLDEN_MICRO,
    GOLDEN_OUT,
    MODEL,
    generate_key,
    scrape_metrics,
    sdk_base_url,
    series_value,
)


def log(msg: str) -> None:
    print(msg, flush=True)


def make_openai(base_url: str, api_key: str) -> OpenAI:
    return OpenAI(base_url=base_url, api_key=api_key, timeout=30.0, max_retries=0)


def make_anthropic(base_url: str, api_key: str) -> Anthropic:
    return Anthropic(base_url=sdk_base_url(base_url), api_key=api_key, timeout=30.0, max_retries=0)


def scrape_snapshot(base: str, key_id: str) -> tuple[float, float, float, float]:
    body = scrape_metrics(base)
    return (
        series_value(body, "janus_cost_micro_usd_total"),
        series_value(body, "janus_tokens_in_total"),
        series_value(body, "janus_tokens_out_total"),
        series_value(body, "janus_key_cost_micro_usd_total", {"key_id": key_id}),
    )


def metric_deltas(base: str, key_id: str, before: tuple[float, float, float, float], expect_zero: bool = False):
    """Scrape after the request and return the delta vs ``before`` (taken pre-request).

    The settle is synchronous with the response, but the exposition may lag a beat:
    poll until the cost delta lands (or a fixed settle for the documented $0 legs).
    """
    if expect_zero:
        time.sleep(0.5)
        after = scrape_snapshot(base, key_id)
        return tuple(a - b for a, b in zip(after, before))
    deadline = time.monotonic() + 10.0
    after = before
    while time.monotonic() < deadline:
        after = scrape_snapshot(base, key_id)
        delta = tuple(a - b for a, b in zip(after, before))
        if delta[0] != 0.0:
            return delta
        time.sleep(0.2)
    return tuple(a - b for a, b in zip(after, before))


def assert_exact(actual: float, expected: float, what: str) -> None:
    assert actual == expected, f"{what}: metric delta {actual} != expected {expected} (zero tolerance)"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--rounds", type=int, default=1)
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    key_id, full_key = generate_key(base, args.master_key, models=[MODEL], name="w33-cost")
    openai = make_openai(base, full_key)
    anthropic = make_anthropic(base, full_key)

    log(f"manual math: {GOLDEN_IN} × 0.14/1000 + {GOLDEN_OUT} × 0.28/1000 = 5320 micro-USD (DeepSeek table)")

    for i in range(args.rounds):
        # ---- OpenAI non-stream --------------------------------------------
        before = scrape_snapshot(base, key_id)
        resp = openai.chat.completions.create(
            model=MODEL, messages=[{"role": "user", "content": f"w33-cost-o-{i}"}]
        )
        assert resp.usage.prompt_tokens == GOLDEN_IN and resp.usage.completion_tokens == GOLDEN_OUT, (
            f"openai non-stream usage {resp.usage}"
        )
        cost, tin, tout, key_cost = metric_deltas(base, key_id, before)
        assert_exact(cost, float(GOLDEN_MICRO), "openai non-stream cost")
        assert_exact(tin, float(GOLDEN_IN), "openai non-stream tokens_in")
        assert_exact(tout, float(GOLDEN_OUT), "openai non-stream tokens_out")
        assert_exact(key_cost, float(GOLDEN_MICRO), "openai non-stream keyed cost")
        log(f"PASS OpenAI non-stream: golden usage {GOLDEN_IN}/{GOLDEN_OUT} → cost delta exactly {GOLDEN_MICRO} micro (keyed series matches)")

        # ---- Anthropic non-stream ------------------------------------------
        before = scrape_snapshot(base, key_id)
        resp = anthropic.messages.create(
            model=MODEL, max_tokens=1024, messages=[{"role": "user", "content": f"w33-cost-a-{i}"}]
        )
        cost, tin, tout, key_cost = metric_deltas(base, key_id, before)
        assert_exact(cost, float(GOLDEN_MICRO), "anthropic non-stream cost")
        assert_exact(tin, float(GOLDEN_IN), "anthropic non-stream tokens_in")
        assert_exact(tout, float(GOLDEN_OUT), "anthropic non-stream tokens_out")
        assert_exact(key_cost, float(GOLDEN_MICRO), "anthropic non-stream keyed cost")
        log("PASS Anthropic non-stream: ao translation meters the same 5320 micro exactly")

        # ---- OpenAI stream WITH include_usage (terminal-chunk settle) ------
        before = scrape_snapshot(base, key_id)
        deltas: list[str] = []
        stream = openai.chat.completions.create(
            model=MODEL,
            messages=[{"role": "user", "content": f"mode=stream w33-cost-usage-{i}"}],
            stream=True,
            stream_options={"include_usage": True},
        )
        for chunk in stream:
            if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
                deltas.append(chunk.choices[0].delta.content)
        assert len(deltas) >= 2, f"include_usage stream: expected deltas, got {len(deltas)}"
        cost, tin, tout, key_cost = metric_deltas(base, key_id, before)
        assert_exact(cost, float(GOLDEN_MICRO), "stream-with-usage cost")
        assert_exact(tin, float(GOLDEN_IN), "stream-with-usage tokens_in")
        assert_exact(tout, float(GOLDEN_OUT), "stream-with-usage tokens_out")
        assert_exact(key_cost, float(GOLDEN_MICRO), "stream-with-usage keyed cost")
        log("PASS OpenAI stream (include_usage): settles from the terminal usage chunk — exactly 5320 micro")

        # ---- OpenAI stream WITHOUT include_usage ($0 documented entry) -----
        before = scrape_snapshot(base, key_id)
        stream = openai.chat.completions.create(
            model=MODEL,
            messages=[{"role": "user", "content": f"mode=stream w33-cost-nousage-{i}"}],
            stream=True,
        )
        for _chunk in stream:
            pass
        cost, tin, tout, key_cost = metric_deltas(base, key_id, before, expect_zero=True)
        assert_exact(cost, 0.0, "stream-without-usage cost ($0 documented)")
        assert_exact(tin, 0.0, "stream-without-usage tokens_in")
        assert_exact(tout, 0.0, "stream-without-usage tokens_out")
        assert_exact(key_cost, 0.0, "stream-without-usage keyed cost")
        log("PASS OpenAI stream (no include_usage): documented $0 settle — Janus never forces include_usage upstream")

        # ---- Anthropic stream (no usage frame served → $0 documented) -------
        before = scrape_snapshot(base, key_id)
        stream = anthropic.messages.create(
            model=MODEL,
            max_tokens=1024,
            messages=[{"role": "user", "content": f"mode=stream w33-cost-a-{i}"}],
            stream=True,
        )
        for _event in stream:
            pass
        cost, tin, tout, key_cost = metric_deltas(base, key_id, before, expect_zero=True)
        assert_exact(cost, 0.0, "anthropic stream cost ($0 documented)")
        assert_exact(key_cost, 0.0, "anthropic stream keyed cost")
        log("PASS Anthropic stream: no terminal usage frame served → documented $0 entry ( m4 pins the settle/release in-suite)")

    print("drill_cost: ALL PASS")


if __name__ == "__main__":
    main()
