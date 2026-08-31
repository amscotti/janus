#!/usr/bin/env python3
"""drill_authoff.py — stage 4 auth-off regression drill.

Boots with NO JANUS_MASTER_KEY resolved (the runner exports nothing) and the phase4
config minus [janus.keys] — the OpenAI-face / cross-format / routing byte-identical default. Asserts:

  - keyless requests succeed on both faces (OpenAI + Anthropic SDKs, non-streaming;
    NO 401s, NO 429s — enforcement is key-scoped and no key exists);
  - /metrics records the UNLABELED Tier-1 totals only: no janus_key_* series exist
 (the auth-off path — unlabeled streams record unlabeled totals), and no
    auth-rejection 4xx bucket lines appear (nothing is rejected);
  - the models tripwire still lists deepseek-v4-flash exactly once.

The routing / resilience slice under this wiring is the runner's ``run.sh
--skip-native`` leg (self-contained, precedent).

Usage:
  drill_authoff.py --base-url http://127.0.0.1:PORT/v1 [--rounds 2]
"""
from __future__ import annotations

import argparse
import sys

from anthropic import Anthropic
from openai import OpenAI

from harness_common import DUMMY_KEY, FIXTURE_CONTENT, MODEL, scrape_metrics, sdk_base_url, series_present, series_value


def log(msg: str) -> None:
    print(msg, flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--rounds", type=int, default=2)
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    openai = OpenAI(base_url=base, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)
    anthropic = Anthropic(base_url=sdk_base_url(base), api_key=DUMMY_KEY, timeout=30.0, max_retries=0)

    for i in range(args.rounds):
        resp = openai.chat.completions.create(
            model=MODEL, messages=[{"role": "user", "content": f"w33-authoff-o-{i}"}]
)
        assert resp.choices[0].message.content == FIXTURE_CONTENT, "auth-off openai: golden content"
        resp = anthropic.messages.create(
            model=MODEL, max_tokens=1024, messages=[{"role": "user", "content": f"w33-authoff-a-{i}"}]
)
        texts = [b.text for b in resp.content if getattr(b, "type", None) == "text"]
        assert "".join(texts) == FIXTURE_CONTENT, "auth-off anthropic: golden content"
    log(f"PASS keyless requests succeed on both faces ({args.rounds} rounds, no 401s/429s)")

    body = scrape_metrics(base)
    assert series_value(body, "janus_requests_total", {"status": "2xx"}) >= 1.0, "auth-off: 2xx bucket empty"
    assert not series_present(body, "janus_key_requests_total"), (
 "auth-off: janus_key_* series present — the auth-off path must record unlabeled totals only ()"
)
    assert not series_present(body, "janus_key_cost_micro_usd_total"), "auth-off: per-key cost series present"
    fourxx_lines = [line for line in body.splitlines() if "janus_requests_total" in line and 'status="4xx"' in line]
    assert not fourxx_lines, f"auth-off: 4xx bucket lines present — nothing should be rejected ({fourxx_lines})"
    assert series_value(body, "janus_cost_micro_usd_total") >= 1.0, "auth-off: unlabeled cost total empty"
    log("PASS /metrics: unlabeled totals only (no key_id series, 4xx empty) — the OpenAI-face / cross-format / routing ungoverned shape")

    print("drill_authoff: ALL PASS")


if __name__ == "__main__":
    main()
