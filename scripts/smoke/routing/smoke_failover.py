#!/usr/bin/env python3
"""smoke_failover.py — routing / resilience 1 SDK harness around the UNMODIFIED ``openai`` and
``anthropic`` packages (; fresh venv, pins).

Drives a live Janus boot whose alias ``deepseek-v4-flash`` has TWO backends (provider
``deepseek`` → fake1 :9877, provider ``openai-compatible`` → fake2 :9878 — both
OpenAI-format adapters serving the SAME golden bodies, so either backend winning a
failover is byte-identical to the client). The runner orchestrates the fake process
lifecycle between invocations (kill fake1 → restart fake1) and passes the per-backend
counter files so THIS script can prove *which backend served*:

  --check happy        both fakes healthy: N mixed requests (OpenAI SDK non-stream +
                       stream + Anthropic SDK non-stream + stream) all succeed with
                       golden content; BOTH backend counters move (round-robin split)
  --check post-kill    fake1 DEAD (runner killed it): N mixed requests all succeed
                       with golden content via fake2 (zero client-visible errors);
                       fake1 counter flat from the kill point, fake2 moved
  --check chaos        one chaos iteration (fake1 dead): N mixed requests all succeed
                       (the runner loops this 10×; the Janus-log clean assertion is
                       the runner's job)
  --check resume       fake1 restarted: N mixed requests all succeed; BOTH counters
                       move again (traffic resumed to both)
  --check weighted     weighted-strategy boot (config.weighted.toml): N non-stream
                       requests → both backends get traffic, the 3-weight backend
                       (openai-compatible, p=0.75) serves >= 8 of 20 (wiring proof —
 the distribution math is unit-pinned, )
  --check failover-500  fake-1 mode-file=500, fake-2 healthy: N non-stream requests
                       all succeed with golden content via the retry walk to fake-2
                       (single client-visible success); fake-2 served >= fake-1's
                       failures (the failover deltas are recorded)
  --check failover-429  same walk with fake-1 mode-file=429 (rate-limit → retryable)
  --check no-retry-400  fake-1 mode-file=400 (NOT retryable): a request landing on
                       fake-1 gets the immediate 400 api_error envelope and fake-2's
                       counter stays FLAT (no retry/failover)
  --check no-retry-401  same with 401 (auth, not retryable) → immediate 401
                       authentication_error envelope, fake-2 flat

Exit 0 = all checks in the group pass; nonzero = first failure (message on stderr).
"""
from __future__ import annotations

import argparse
import sys

from anthropic import Anthropic
from openai import APIStatusError, AuthenticationError, BadRequestError, OpenAI

from harness_common import delta, read_counter, sdk_base_url, snapshot

FIXTURE_CONTENT = "The weather in Paris is 18 degrees with light rain."
MODEL = "deepseek-v4-flash"
DUMMY_KEY = "janus-smoke-dummy-key"
MAX_TOKENS = 1024


def make_openai(base_url: str) -> OpenAI:
    # max_retries=0: the SDK must not mask Janus's error semantics with client retries.
    return OpenAI(base_url=base_url, api_key=DUMMY_KEY, timeout=30.0, max_retries=0)


def make_anthropic(base_url: str) -> Anthropic:
    # sdk_base_url: the anthropic SDK appends /v1/messages to base_url itself.
    return Anthropic(base_url=sdk_base_url(base_url), api_key=DUMMY_KEY, timeout=30.0, max_retries=0)


def log(msg: str) -> None:
    print(msg, flush=True)


def assert_content(actual: str, what: str) -> None:
    assert actual == FIXTURE_CONTENT, f"{what}: content {actual!r} != golden fixture {FIXTURE_CONTENT!r}"


def one_openai_nonstream(client: OpenAI, idx: int) -> None:
    resp = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": f"w24-failover-{idx}"}],
)
    assert resp.object == "chat.completion", f"ChatCompletion.object {resp.object!r} != chat.completion"
    assert_content(resp.choices[0].message.content, "openai non-stream")


def one_openai_stream(client: OpenAI, idx: int) -> None:
    deltas: list[str] = []
    stream = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": f"mode=stream w24-failover-{idx}"}],
        stream=True,
)
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
            deltas.append(chunk.choices[0].delta.content)
    assert len(deltas) >= 2, f"openai stream: expected >= 2 deltas, got {len(deltas)}"
    assert_content("".join(deltas), "openai stream")


def one_anthropic_nonstream(client: Anthropic, idx: int) -> None:
    resp = client.messages.create(
        model=MODEL,
        max_tokens=MAX_TOKENS,
        messages=[{"role": "user", "content": f"w24-failover-a-{idx}"}],
)
    texts = [b.text for b in resp.content if getattr(b, "type", None) == "text"]
    assert_content("".join(texts), "anthropic non-stream")


def one_anthropic_stream(client: Anthropic, idx: int) -> None:
    deltas: list[str] = []
    stopped = False
    stream = client.messages.create(
        model=MODEL,
        max_tokens=MAX_TOKENS,
        messages=[{"role": "user", "content": f"mode=stream w24-failover-a-{idx}"}],
        stream=True,
)
    for event in stream:
        if event.type == "content_block_delta" and getattr(event.delta, "type", None) == "text_delta":
            deltas.append(event.delta.text)
        elif event.type == "message_stop":
            stopped = True
    assert stopped, "anthropic stream must terminate with message_stop"
    assert_content("".join(deltas), "anthropic stream")


def run_mixed(base_url: str, rounds: int) -> None:
    """One round = one request on each of the four cells (oo nonstream / oo stream /
    ao nonstream / ao stream)."""
    oai = make_openai(base_url)
    anth = make_anthropic(base_url)
    for i in range(rounds):
        one_openai_nonstream(oai, i)
        one_openai_stream(oai, i)
        one_anthropic_nonstream(anth, i)
        one_anthropic_stream(anth, i)


def classifier_failover(base_url: str, counter_paths: list[str], rounds: int, what: str) -> None:
    """fake-1 fails (mode-file 500/429 — retryable); every request must still return
    the golden content (single client-visible success via the retry walk to fake-2),
    and fake-2 must have served at least as many requests as fake-1 failed."""
    oai = make_openai(base_url)
    before = snapshot(counter_paths)
    for i in range(rounds):
        one_openai_nonstream(oai, i)
    after = snapshot(counter_paths)
    moves = delta(before, after)
    log(f"PASS failover-{what}: {rounds} requests all golden via the retry walk; deltas {moves}")
    assert moves[counter_paths[1]] >= moves[counter_paths[0]], (
        f"failover-{what}: fake-2 must serve >= fake-1's failures (failover walk), deltas {moves}"
)


def no_retry(base_url: str, counter_paths: list[str], what: str, exc_type, expected_status: int, expected_type: str) -> None:
    """fake-1 fails with a NOT-retryable error (400/401): the first request landing on
    fake-1 gets the immediate envelope and fake-2's counter stays FLAT (no failover)."""
    oai = make_openai(base_url)
    b2 = counter_paths[1]
    for i in range(30):
        c2_before = read_counter(b2)["requests"]
        try:
            resp = oai.chat.completions.create(
                model=MODEL,
                messages=[{"role": "user", "content": f"w24-no-retry-{what}-{i}"}],
)
            # Landed on fake-2 (healthy) — keep trying until RR lands on fake-1.
            assert resp.choices[0].message.content == FIXTURE_CONTENT, "unexpected non-golden success"
            continue
        except exc_type as e:  # noqa: PERF203
            assert e.status_code == expected_status, f"no-retry-{what}: status {e.status_code} != {expected_status}"
            err = e.response.json()["error"]
            assert err["type"] == expected_type, f"no-retry-{what}: envelope type {err['type']!r} != {expected_type!r}"
            assert read_counter(b2)["requests"] == c2_before, (
                f"no-retry-{what}: fake-2 must NOT be touched (no failover for 4xx), "
                f"counter {read_counter(b2)['requests']} != {c2_before}"
)
            log(f"PASS no-retry-{what}: immediate {expected_status} {expected_type} envelope, fake-2 flat")
            return
    raise AssertionError(f"no-retry-{what}: RR never landed on fake-1 within 30 requests")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--counters", required=True, help="comma-separated fake counter-file paths (fake1,fake2)")
    parser.add_argument(
        "--check",
        required=True,
        choices=[
            "happy",
            "post-kill",
            "chaos",
            "resume",
            "weighted",
            "failover-500",
            "failover-429",
            "no-retry-400",
            "no-retry-401",
        ],
)
    parser.add_argument("--rounds", type=int, default=5)
    args = parser.parse_args()

    counter_paths = [p.strip() for p in args.counters.split(",") if p.strip()]
    assert len(counter_paths) == 2, "need exactly two counter paths (fake1,fake2)"

    before = snapshot(counter_paths)

    if args.check == "happy":
        run_mixed(args.base_url, args.rounds)
        after = snapshot(counter_paths)
        moves = delta(before, after)
        log(f"PASS happy: {args.rounds} mixed rounds x4 cells all golden; counter deltas {moves}")
        assert moves[counter_paths[0]] > 0 and moves[counter_paths[1]] > 0, (
            f"happy: both backends must see traffic when healthy, deltas {moves}"
)
    elif args.check == "post-kill":
        run_mixed(args.base_url, args.rounds)
        after = snapshot(counter_paths)
        moves = delta(before, after)
        log(f"PASS post-kill: {args.rounds} mixed rounds all succeeded via the surviving backend; deltas {moves}")
        assert moves[counter_paths[1]] > 0, f"post-kill: fake2 must serve, deltas {moves}"
        assert moves[counter_paths[0]] == 0, f"post-kill: fake1 is dead — its counter must be flat, deltas {moves}"
    elif args.check == "chaos":
        run_mixed(args.base_url, args.rounds)
        after = snapshot(counter_paths)
        moves = delta(before, after)
        log(f"PASS chaos iteration: {args.rounds} mixed rounds all succeeded; deltas {moves}")
        assert moves[counter_paths[1]] > 0, f"chaos: fake2 must serve, deltas {moves}"
    elif args.check == "resume":
        run_mixed(args.base_url, args.rounds)
        after = snapshot(counter_paths)
        moves = delta(before, after)
        log(f"PASS resume: {args.rounds} mixed rounds all succeeded; deltas {moves}")
        assert moves[counter_paths[0]] > 0 and moves[counter_paths[1]] > 0, (
            f"resume: traffic must resume to BOTH backends after restart, deltas {moves}"
)
    elif args.check == "weighted":
        # Weighted boot: only the OpenAI non-stream cell (raw deterministic pick proof —
        # the SDK adds nothing the raw path lacks for the distribution leg).
        oai = make_openai(args.base_url)
        for i in range(args.rounds):
            one_openai_nonstream(oai, i)
        after = snapshot(counter_paths)
        moves = delta(before, after)
        b2 = moves[counter_paths[1]]
        log(f"PASS weighted: {args.rounds} requests, backend-2 (weight 3) served {b2}, deltas {moves}")
        assert moves[counter_paths[0]] > 0 and b2 > 0, f"weighted: both backends must get traffic, deltas {moves}"
        assert b2 >= 8, f"weighted: backend-2 (p=0.75) served only {b2} of {args.rounds} (bound >= 8)"
    elif args.check in ("failover-500", "failover-429"):
        classifier_failover(args.base_url, counter_paths, args.rounds, args.check.removeprefix("failover-"))
    elif args.check in ("no-retry-400", "no-retry-401"):
        what = args.check.removeprefix("no-retry-")
        if what == "400":
            no_retry(args.base_url, counter_paths, what, BadRequestError, 400, "api_error")
        else:
            no_retry(args.base_url, counter_paths, what, AuthenticationError, 401, "authentication_error")
    log(f"ALL PASS ({args.check})")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
