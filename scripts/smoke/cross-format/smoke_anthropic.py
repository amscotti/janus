#!/usr/bin/env python3
"""smoke_anthropic.py — cross-format SDK harness around the UNMODIFIED ``anthropic``
package ().

The harness drives a fresh-venv, pinned ``anthropic`` client (base_url + dummy key
only — exactly what a drop-in Anthropic endpoint must serve) against a live Janus
boot and asserts the cross-format surface for the Anthropic face:

  gate 1  non-streaming + streaming chat through Janus to the OpenAI-compatible
         upstream (fake DeepSeek): content-block deltas aggregate, ``message_stop``
         terminates — no ``[DONE]`` anywhere
  gate 3  tool-call round-trip: the SDK emits a ``tool_use`` block; the harness feeds
         the ``tool_result``; the final answer arrives (non-stream + stream variants)
  gate 3  Anthropic-shaped errors surfaced as the SDK's typed exceptions, on BOTH
         upstream directions (ao: fake OpenAI-compat — 401/400/429/503; aa: fake
         Anthropic — 401/400/429/529):
         401 → AuthenticationError / authentication_error
         400 → BadRequestError / api_error
         429 → RateLimitError / rate_limit_error
         5xx (ao 503 / aa 529) → APIStatusError status=502 / api_error
         (Janus maps upstream 5xx into its 502 api_error envelope —  contract;
         envelope message text is Janus-synthesized, never upstream-verbatim)

Check groups (``--check``):
  all           models-less face: nonstream + stream + tools + errors (fake upstream)
  happy         nonstream + stream (content pinned to the  corpus fixture)
  tools         tool round-trips, non-stream + stream
  errors        typed-exception checks on both upstream directions (ao:
                401/400/429/503 → fake OpenAI-compat; aa: 401/400/429/529 → fake
                Anthropic) + unknown-model 404
  real-happy    nonstream + stream with relaxed content assertions (real DeepSeek
                returns arbitrary text) — requires --models
  eager-kill    upstream dead before request → api_error envelope within the bound
                (the abort drill is a separate script: abort_drill.py)

Usage:
  smoke_anthropic.py --base-url http://127.0.0.1:8080/v1 --model deepseek-v4-flash --check all
  smoke_anthropic.py --base-url http://127.0.0.1:8080/v1 --check real-happy --models deepseek-v4-flash
  smoke_anthropic.py --base-url http://127.0.0.1:8080/v1 --check eager-kill --bound 5

Exit 0 = all checks in the group pass; nonzero = first failure (message on stderr).
"""
from __future__ import annotations

import argparse
import http.client
import json
import sys
import time
import urllib.parse
from pathlib import Path

from anthropic import (
    APIStatusError,
    Anthropic,
    AuthenticationError,
    BadRequestError,
    NotFoundError,
    RateLimitError,
)

from sdk_common import sdk_base_url

# Wire truth from the  golden fixtures (janus-core/src/test/resources/fixtures):
# anthropic/chat.response.json content + the text deltas of anthropic/chat.stream.sse.
# The fake serves these bodies; the SDK client must see the same bytes through Janus.
FIXTURE_CONTENT = "The weather in Paris is 18 degrees with light rain."

DEFAULT_MODEL = "deepseek-v4-flash"  # ao leg: Anthropic SDK → Janus → fake OpenAI-compat
AA_MODEL = "claude-3-5-sonnet"  # aa leg: Anthropic SDK → Janus → fake Anthropic
UNKNOWN_MODEL = "no-such-model-janus-smoke"
DUMMY_KEY = "janus-smoke-dummy-key"
MAX_TOKENS = 1024

WEATHER_TOOL = {
    "name": "get_weather",
    "description": "current weather in a city",
    "input_schema": {
        "type": "object",
        "properties": {"city": {"type": "string"}},
        "required": ["city"],
    },
}


def make_client(base_url: str, api_key: str = DUMMY_KEY, timeout: float = 30.0) -> Anthropic:
    # max_retries=0: the SDK must not mask Janus's error semantics with client retries.
    # sdk_base_url() (from sdk_common): the anthropic SDK appends /v1/messages to
    # base_url itself, so the SDK must see the ROOT — a /v1-prefixed base_url would
    # hit /v1/v1/messages (404).
    return Anthropic(base_url=sdk_base_url(base_url), api_key=api_key, timeout=timeout, max_retries=0)


def log(msg: str) -> None:
    print(msg, flush=True)


def aggregate_stream(stream) -> tuple[list[str], bool]:
    """Iterate a raw ``messages.create(stream=True)`` event stream; aggregate text
    deltas; report whether ``message_stop`` terminated it. Raises on any event the
    unmodified SDK does not model (the SDK's own validation is part of the proof)."""
    deltas: list[str] = []
    stopped = False
    for event in stream:
        if event.type == "content_block_delta":
            delta = event.delta
            if getattr(delta, "type", None) == "text_delta":
                deltas.append(delta.text)
        elif event.type == "message_stop":
            stopped = True
    return deltas, stopped


# ---------------------------------------------------------------- gate 1

def check_nonstream(client: Anthropic, model: str, relaxed: bool = False) -> None:
    resp = client.messages.create(
        model=model,
        max_tokens=MAX_TOKENS,
        messages=[{"role": "user", "content": "What is the weather in Paris?"}],
    )
    assert resp.type == "message", f"response type {resp.type!r} != message"
    texts = [block.text for block in resp.content if getattr(block, "type", None) == "text"]
    content = "".join(texts)
    if relaxed:
        assert content.strip(), "non-stream response content is blank"
        log(f"PASS non-stream chat ({model}): content non-blank ({len(content)} chars)")
    else:
        assert content == FIXTURE_CONTENT, (
            f"non-stream content {content!r} != golden fixture {FIXTURE_CONTENT!r}"
        )
        log(f"PASS non-stream chat ({model}): content byte-matches the golden fixture")


def check_stream(client: Anthropic, model: str, relaxed: bool = False) -> None:
    stream = client.messages.create(
        model=model,
        max_tokens=MAX_TOKENS,
        # "mode=stream" is the fake-upstream marker (the fake serves the SSE body only
        # for it); without it the fake serves the non-stream JSON and the SDK sees a
        # single malformed "data:" line — the stream leg must hit the SSE mode.
        messages=[{"role": "user", "content": "mode=stream What is the weather in Paris?"}],
        stream=True,
    )
    deltas, stopped = aggregate_stream(stream)
    assert stopped, "stream must terminate with a message_stop event"
    assert len(deltas) >= 2, f"expected >= 2 text deltas, got {len(deltas)}: {deltas}"
    aggregated = "".join(deltas)
    if relaxed:
        assert aggregated.strip(), "streaming content aggregated to blank"
        log(f"PASS stream chat ({model}): {len(deltas)} deltas, content non-blank ({len(aggregated)} chars)")
    else:
        assert aggregated == FIXTURE_CONTENT, (
            f"aggregated stream content {aggregated!r} != golden fixture {FIXTURE_CONTENT!r}"
        )
        log(f"PASS stream chat ({model}): {len(deltas)} deltas aggregate to the golden content, message_stop terminates")


def check_raw_stream_termination(base_url: str, model: str) -> None:
    """Byte-level proof that the live Anthropic-face stream has NO ``[DONE]`` and
    carries ``event:`` lines terminating with ``message_stop`` ( discipline)."""
    url = urllib.parse.urlsplit(base_url)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=30.0)
    body = json.dumps(
        {
            "model": model,
            "max_tokens": MAX_TOKENS,
            "messages": [{"role": "user", "content": "mode=stream What is the weather in Paris?"}],
            "stream": True,
        }
    )
    conn.request("POST", "/v1/messages", body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    assert resp.status == 200, f"raw stream: HTTP {resp.status}"
    ctype = resp.getheader("Content-Type", "")
    assert ctype.startswith("text/event-stream"), f"raw stream: Content-Type {ctype!r}"
    raw = resp.read().decode("utf-8", "replace")
    conn.close()
    assert "[DONE]" not in raw, "Anthropic-face stream must not carry [DONE] ( discipline)"
    assert "event: message_stop" in raw, "Anthropic-face stream must terminate with event: message_stop"
    assert "event: message_start" in raw, "Anthropic-face stream must open with event: message_start"
    log("PASS raw Anthropic SSE: event-named frames, message_stop terminal, no [DONE]")


# ---------------------------------------------------------------- gate 3 (tools)

def check_tools_nonstream(client: Anthropic, model: str) -> None:
    # Turn 1: trigger prompt → the fake serves the committed tool_use response.
    resp = client.messages.create(
        model=model,
        max_tokens=MAX_TOKENS,
        tools=[WEATHER_TOOL],
        messages=[{"role": "user", "content": "mode=tools What is the weather in Paris?"}],
    )
    tool_blocks = [b for b in resp.content if getattr(b, "type", None) == "tool_use"]
    assert len(tool_blocks) == 1, f"expected one tool_use block, got {len(tool_blocks)}"
    tool = tool_blocks[0]
    assert tool.name == "get_weather", f"tool name {tool.name!r} != get_weather"
    assert tool.input == {"city": "Paris"}, f"tool input {tool.input!r} != {{'city': 'Paris'}}"
    assert resp.stop_reason == "tool_use", f"stop_reason {resp.stop_reason!r} != tool_use"
    tool_id = tool.id

    # Turn 2: feed the tool_result (Anthropic user message with a tool_result block).
    final = client.messages.create(
        model=model,
        max_tokens=MAX_TOKENS,
        tools=[WEATHER_TOOL],
        messages=[
            {"role": "user", "content": "mode=tools What is the weather in Paris?"},
            {
                "role": "assistant",
                "content": [{"type": "text", "text": "checking"}, {"type": "tool_use", "id": tool_id, "name": "get_weather", "input": {"city": "Paris"}}],
            },
            {
                "role": "user",
                "content": [{"type": "tool_result", "tool_use_id": tool_id, "content": '{"temp":18}'}],
            },
        ],
    )
    texts = "".join(b.text for b in final.content if getattr(b, "type", None) == "text")
    assert texts == FIXTURE_CONTENT, f"tool round-trip final content {texts!r} != {FIXTURE_CONTENT!r}"
    log(f"PASS tools non-stream: tool_use id={tool_id} name/input survive; final answer arrives")


def check_tools_stream(client: Anthropic, model: str) -> None:
    # Turn 1: streaming tool-call turn — the SDK's stream yields a tool_use block.
    stream = client.messages.create(
        model=model,
        max_tokens=MAX_TOKENS,
        tools=[WEATHER_TOOL],
        messages=[{"role": "user", "content": "mode=tools&stream=1 What is the weather in Paris?"}],
        stream=True,
    )
    tool_blocks: list[tuple[str, str]] = []
    partial_json = ""
    stopped = False
    for event in stream:
        if event.type == "content_block_start" and getattr(event.content_block, "type", None) == "tool_use":
            tool_blocks.append((event.content_block.id, event.content_block.name))
        elif event.type == "content_block_delta" and getattr(event.delta, "type", None) == "input_json_delta":
            # Partial-JSON arguments arrive as raw fragments — the delta-shape corner
            # case; they must concatenate to the committed input.
            partial_json += event.delta.partial_json
        elif event.type == "message_stop":
            stopped = True
    assert stopped, "tools stream must terminate with message_stop"
    assert len(tool_blocks) == 1, f"expected one tool_use block in the stream, got {len(tool_blocks)}"
    tool_id, name = tool_blocks[0]
    assert name == "get_weather", f"streamed tool name {name!r} != get_weather"
    assert json.loads(partial_json) == {"city": "Paris"}, (
        f"streamed partial tool arguments {partial_json!r} must concatenate to {{'city': 'Paris'}}"
    )

    # Turn 2: feed the tool_result, stream the final answer.
    final_stream = client.messages.create(
        model=model,
        max_tokens=MAX_TOKENS,
        tools=[WEATHER_TOOL],
        messages=[
            {"role": "user", "content": "mode=tools&stream=1 What is the weather in Paris?"},
            {
                "role": "assistant",
                "content": [{"type": "text", "text": "checking"}, {"type": "tool_use", "id": tool_id, "name": "get_weather", "input": {"city": "Paris"}}],
            },
            {
                "role": "user",
                "content": [{"type": "tool_result", "tool_use_id": tool_id, "content": '{"temp":18}'}],
            },
        ],
        stream=True,
    )
    final_deltas, final_stopped = aggregate_stream(final_stream)
    assert final_stopped, "tools final stream must terminate with message_stop"
    final_content = "".join(final_deltas)
    assert final_content == FIXTURE_CONTENT, (
        f"tools stream final content {final_content!r} != {FIXTURE_CONTENT!r}"
    )
    log(f"PASS tools stream: streamed tool_use id={tool_id} name/input survive; final stream terminates")


# ---------------------------------------------------------------- gate 3 (errors)

def check_error(
    client: Anthropic,
    model: str,
    marker: str,
    exc_type,
    expected_status: int,
    expected_type: str,
    message_contains: str | None = None,
) -> None:
    """One Anthropic-shaped error assertion: SDK typed exception + envelope JSON."""
    try:
        client.messages.create(model=model, max_tokens=MAX_TOKENS, messages=[{"role": "user", "content": marker}])
    except exc_type as e:  # noqa: PERF203 — the catch order IS the assertion
        assert e.status_code == expected_status, (
            f"{type(e).__name__} status {e.status_code} != expected {expected_status}"
        )
        err = e.response.json()["error"]
        assert err["type"] == expected_type, f"error.type {err['type']!r} != {expected_type!r}"
        if message_contains is not None:
            assert message_contains in (err.get("message") or ""), (
                f"error.message {err.get('message')!r} lacks {message_contains!r}"
            )
        log(
            f"PASS {marker}: {type(e).__name__} status={expected_status} envelope "
            f"type={expected_type}"
        )
        return
    except APIStatusError as e:
        raise AssertionError(
            f"expected {exc_type.__name__}, got {type(e).__name__} status={e.status_code}: {e}"
        ) from e
    raise AssertionError(f"expected {exc_type.__name__}, got a successful response")


def check_unknown_model(client: Anthropic, model: str) -> None:
    try:
        client.messages.create(
            model=UNKNOWN_MODEL, max_tokens=MAX_TOKENS, messages=[{"role": "user", "content": "hi"}]
        )
        raise AssertionError("expected NotFoundError (unknown model)")
    except NotFoundError as e:
        assert e.status_code == 404, f"unknown-model: status {e.status_code} != 404"
        log("PASS unknown-model: NotFoundError status=404")
    except APIStatusError as e:
        raise AssertionError(f"expected NotFoundError, got {type(e).__name__} status={e.status_code}: {e}") from e


# ---------------------------------------------------------------- drills

def check_eager_kill(client: Anthropic, model: str, bound: float) -> None:
    """Upstream dead before the request → api_error envelope, within the bound, no hang."""
    start = time.monotonic()
    try:
        client.messages.create(model=model, max_tokens=MAX_TOKENS, messages=[{"role": "user", "content": "hello"}])
        raise AssertionError("expected APIStatusError (upstream is dead)")
    except APIStatusError as e:
        elapsed = time.monotonic() - start
        assert e.status_code == 502, f"eager-kill: status {e.status_code} != 502"
        err = e.response.json()["error"]
        assert err["type"] == "api_error", f"eager-kill: envelope type {err['type']!r} != api_error"
        assert elapsed <= bound, f"eager-kill took {elapsed:.2f}s (> {bound}s bound)"
        log(f"PASS eager-kill: 502 api_error envelope in {elapsed:.2f}s (bound {bound}s)")


# ---------------------------------------------------------------- main

CHECKS = {
    "nonstream": lambda a: check_nonstream(make_client(a.base_url), a.model),
    "stream": lambda a: check_stream(make_client(a.base_url), a.model),
    "raw-term": lambda a: check_raw_stream_termination(a.base_url, a.model),
    "tools": lambda a: (check_tools_nonstream(make_client(a.base_url), a.model), check_tools_stream(make_client(a.base_url), a.model)),
    "unknown-model": lambda a: check_unknown_model(make_client(a.base_url), a.model),
    # ao direction (default model → fake OpenAI-compat upstream): the fake serves
    # 401/400/429/503; Janus surfaces each as an Anthropic-shaped typed error.
    # 5xx maps to Janus's 502 api_error envelope.
    "401-ao": lambda a: check_error(make_client(a.base_url), DEFAULT_MODEL, "mode=401", AuthenticationError, 401, "authentication_error", message_contains="401"),
    "400-ao": lambda a: check_error(make_client(a.base_url), DEFAULT_MODEL, "mode=400", BadRequestError, 400, "api_error", message_contains="400"),
    "429-ao": lambda a: check_error(make_client(a.base_url), DEFAULT_MODEL, "mode=429", RateLimitError, 429, "rate_limit_error", message_contains="429"),
    "503-ao": lambda a: check_error(make_client(a.base_url), DEFAULT_MODEL, "mode=503", APIStatusError, 502, "api_error", message_contains="503"),
    # aa direction (fake Anthropic upstream): 401/400/429/529, same Anthropic-face
    # mapping; 529 (upstream 5xx) → 502 api_error — NOT OverloadedError status=529
    # (Janus's upstream-5xx classification, pinned in the  matrix).
    "401-aa": lambda a: check_error(make_client(a.base_url), AA_MODEL, "mode=401", AuthenticationError, 401, "authentication_error", message_contains="401"),
    "400-aa": lambda a: check_error(make_client(a.base_url), AA_MODEL, "mode=400", BadRequestError, 400, "api_error", message_contains="400"),
    "429-aa": lambda a: check_error(make_client(a.base_url), AA_MODEL, "mode=429", RateLimitError, 429, "rate_limit_error", message_contains="429"),
    "529-aa": lambda a: check_error(make_client(a.base_url), AA_MODEL, "mode=529", APIStatusError, 502, "api_error", message_contains="529"),
    "eager-kill": lambda a: check_eager_kill(make_client(a.base_url), a.model, a.bound),
}

GROUPS = {
    "all": ["nonstream", "stream", "raw-term", "tools", "unknown-model", "401-ao", "400-ao", "429-ao", "503-ao", "401-aa", "400-aa", "429-aa", "529-aa"],
    "happy": ["nonstream", "stream", "raw-term"],
    "tools": ["tools"],
    "errors": ["unknown-model", "401-ao", "400-ao", "429-ao", "503-ao", "401-aa", "400-aa", "429-aa", "529-aa"],
    "real-happy": ["nonstream", "stream", "raw-term"],
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--check", default="all")
    parser.add_argument("--bound", type=float, default=5.0, help="drill wall-clock bound in seconds")
    parser.add_argument("--real", action="store_true", help="relax content assertions (real upstream)")
    args = parser.parse_args()

    if args.check in GROUPS:
        names = GROUPS[args.check]
    elif args.check in CHECKS:
        names = [args.check]
    else:
        raise SystemExit(f"unknown --check {args.check!r}; expected one of {sorted(GROUPS)} or {sorted(CHECKS)}")

    client = make_client(args.base_url)
    for name in names:
        if name == "nonstream":
            check_nonstream(client, args.model, relaxed=args.real)
        elif name == "stream":
            check_stream(client, args.model, relaxed=args.real)
        else:
            CHECKS[name](args)
    log(f"ALL PASS ({args.check}): {', '.join(names)} against {args.base_url}")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
    except APIStatusError as e:
        print(f"FAIL: unexpected {type(e).__name__} status={e.status_code}: {e}", file=sys.stderr)
        sys.exit(1)
