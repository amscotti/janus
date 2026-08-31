#!/usr/bin/env python3
"""smoke_sdk.py — cross-format SDK harness around the UNMODIFIED ``openai`` package.

The harness drives a fresh-venv, pinned ``openai`` client (base_url + dummy key only)
against a live Janus boot and asserts the cross-format surface for the OpenAI face:
the OpenAI-face regression legs (oo: OpenAI SDK → Janus → fake OpenAI-compatible
upstream) PLUS the stage 2 cross-format legs (oa: OpenAI SDK → Janus → fake Anthropic
upstream) and tool-call round-trips in the oa direction via ``tool_calls`` +
``role="tool"`` results (stream and non-stream).

  stage 1 regression (oo, model deepseek-v4-flash):
    non-stream + stream chat (partial deltas, ``data: [DONE]`` — byte-level via a raw
    check), ``GET /v1/models`` in config order, typed-exception error checks
    (401/400/429/503 → AuthenticationError/BadRequestError/RateLimitError/502
    server_error), eager-kill drill
  stage 2 legs (oa, model claude-3-5-sonnet — the fake Anthropic upstream):
    non-stream + stream chat through the cross-format path (Anthropic wire →
    canonical → OpenAI face), tool round-trip (``tool_calls`` → ``role="tool"`` →
    final answer; stream + non-stream), errors via the fake Anthropic's modes
    (401/400/429/529 → the same OpenAI-envelope mapping)

Check groups (``--check``):
  all          oo models+nonstream+stream+raw-done+errors + oa nonstream/stream/raw/
               tools + oa errors (fake-upstream legs)
  oo           stage 1 regression: models + nonstream + stream + raw-done + errors
  oa           stage 2 cross-format: oa nonstream + stream + raw-done + tools + errors
  errors-oo    oo error envelopes (401/400/429/503)
  errors-oa    oa error envelopes (401/400/429/529)
  eager-kill   upstream dead before request → 502 api_error within the bound

Usage:
  smoke_sdk.py --base-url http://127.0.0.1:8080/v1 --check all
  smoke_sdk.py --base-url http://127.0.0.1:8080/v1 --check eager-kill --bound 5

Exit 0 = all checks in the group pass; nonzero = first failure (message on stderr).
"""
from __future__ import annotations

import argparse
import http.client
import json
import sys
import time
import urllib.parse

from openai import APIStatusError, AuthenticationError, BadRequestError, NotFoundError, RateLimitError, OpenAI

# Wire truth from the golden fixtures: the corpus conversation's content bytes,
# shared by both upstreams (openai/chat.response.json and anthropic/chat.response.json
# carry the same text; the matrix pins the cross-format equality).
FIXTURE_CONTENT = "The weather in Paris is 18 degrees with light rain."

DEFAULT_MODEL = "deepseek-v4-flash"  # oo leg: fake OpenAI-compatible upstream
OA_MODEL = "claude-3-5-sonnet"  # oa leg: fake Anthropic upstream
UNKNOWN_MODEL = "no-such-model-janus-smoke"
DUMMY_KEY = "janus-smoke-dummy-key"

WEATHER_TOOL = {
    "type": "function",
    "function": {
        "name": "get_weather",
        "description": "current weather in a city",
        "parameters": {
            "type": "object",
            "properties": {"city": {"type": "string"}},
            "required": ["city"],
        },
    },
}


def make_client(base_url: str, api_key: str = DUMMY_KEY, timeout: float = 30.0) -> OpenAI:
    # max_retries=0: the SDK must not mask Janus's error semantics with client retries.
    return OpenAI(base_url=base_url, api_key=api_key, timeout=timeout, max_retries=0)


def log(msg: str) -> None:
    print(msg, flush=True)


# ---------------------------------------------------------------- models

def check_models(client: OpenAI, expected: list[str]) -> None:
    data = client.models.list().data
    names = [m.id for m in data]
    assert names == expected, f"models.list returned {names}, expected config order {expected}"
    for m in data:
        # owned_by is the provider name (ModelListFactory — the config has
        # deepseek + anthropic in the phase2 gate config).
        if m.id == "deepseek-v4-flash":
            assert m.owned_by == "deepseek", f"model {m.id} owned_by={m.owned_by!r}, expected 'deepseek'"
        elif m.id == "claude-3-5-sonnet":
            assert m.owned_by == "anthropic", f"model {m.id} owned_by={m.owned_by!r}, expected 'anthropic'"
    log(f"PASS models.list -> {names} (config order, owned_by per provider)")


# ---------------------------------------------------------------- non-stream

def check_nonstream(client: OpenAI, model: str, relaxed: bool = False) -> None:
    resp = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "What is the weather in Paris?"}],
)
    # D2 red/green pin: the pinned OpenAI SDK types object as Literal["chat.completion"];
    # an Anthropic-derived "message" leaks the format field and fails SDK validation.
    assert resp.object == "chat.completion", f"ChatCompletion.object {resp.object!r} != chat.completion (D2)"
    content = resp.choices[0].message.content
    if relaxed:
        assert content and content.strip(), "non-stream response content is blank"
        log(f"PASS non-stream chat ({model}): content non-blank ({len(content)} chars)")
    else:
        assert content == FIXTURE_CONTENT, (
            f"non-stream content {content!r} != golden fixture {FIXTURE_CONTENT!r}"
)
        log(f"PASS non-stream chat ({model}): content byte-matches the golden fixture, object=chat.completion")


def check_stream(client: OpenAI, model: str, relaxed: bool = False) -> None:
    deltas: list[str] = []
    stream = client.chat.completions.create(
        model=model,
        # "mode=stream" is the fake-upstream marker (each fake serves its SSE body only
        # for it); without it the fake serves the non-stream JSON and Janus sees an
        # empty SSE stream.
        messages=[{"role": "user", "content": "mode=stream What is the weather in Paris?"}],
        stream=True,
        stream_options={"include_usage": True},
)
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
            deltas.append(chunk.choices[0].delta.content)
    assert len(deltas) >= 2, f"expected >= 2 content deltas (partial tokens), got {len(deltas)}: {deltas}"
    aggregated = "".join(deltas)
    if relaxed:
        assert aggregated.strip(), "streaming content aggregated to blank"
        log(f"PASS stream chat ({model}): {len(deltas)} deltas, content non-blank ({len(aggregated)} chars)")
    else:
        assert aggregated == FIXTURE_CONTENT, (
            f"aggregated stream content {aggregated!r} != golden fixture {FIXTURE_CONTENT!r}"
)
        log(f"PASS stream chat ({model}): {len(deltas)} partial deltas aggregate to the golden content")


# ------------------------------------------------------ raw [DONE]

def check_raw_done(base_url: str, model: str) -> None:
    """Byte-level proof that the live OpenAI-face stream terminates with ``data: [DONE]``."""
    url = urllib.parse.urlsplit(base_url)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=30.0)
    body = json.dumps(
        {
            "model": model,
            "messages": [{"role": "user", "content": "mode=stream What is the weather in Paris?"}],
            "stream": True,
        }
)
    conn.request("POST", "/v1/chat/completions", body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    assert resp.status == 200, f"raw stream: HTTP {resp.status}"
    ctype = resp.getheader("Content-Type", "")
    assert ctype.startswith("text/event-stream"), f"raw stream: Content-Type {ctype!r}"
    frames: list[str] = []
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if line.startswith("data:"):
            frames.append(line[len("data:") :].strip())
    conn.close()
    assert frames and frames[-1] == "[DONE]", (
        f"stream must terminate with data: [DONE]; got {len(frames)} frames, last={frames[-1] if frames else '<none>'}"
)
    assert all(not f.startswith("event:") for f in frames), "OpenAI face must not carry event: lines"
    log(f"PASS raw SSE ({model}): {len(frames)} frames, terminal data: [DONE]")


# ---------------------------------------------------------------- tools (oa)

def check_tools_nonstream(client: OpenAI, model: str) -> None:
    # Turn 1: trigger prompt → Janus translates the fake Anthropic's tool_use into
    # OpenAI tool_calls.
    resp = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "mode=tools What is the weather in Paris?"}],
        tools=[WEATHER_TOOL],
)
    message = resp.choices[0].message
    assert message.tool_calls, f"expected tool_calls, got content={message.content!r}"
    call = message.tool_calls[0]
    assert call.type == "function", f"tool call type {call.type!r} != function"
    assert call.function.name == "get_weather", f"tool name {call.function.name!r} != get_weather"
    assert json.loads(call.function.arguments) == {"city": "Paris"}, (
        f"tool arguments {call.function.arguments!r} != {{\"city\": \"Paris\"}}"
)
    assert resp.choices[0].finish_reason == "tool_calls", (
        f"finish_reason {resp.choices[0].finish_reason!r} != tool_calls"
)
    call_id = call.id

    # Turn 2: feed the tool result (role="tool"), the fake serves the final answer.
    final = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "user", "content": "mode=tools What is the weather in Paris?"},
            {"role": "assistant", "content": message.content, "tool_calls": [c.model_dump() for c in message.tool_calls]},
            {"role": "tool", "tool_call_id": call_id, "content": '{"temp":18}'},
        ],
        tools=[WEATHER_TOOL],
)
    content = final.choices[0].message.content
    assert content == FIXTURE_CONTENT, f"tool round-trip final content {content!r} != {FIXTURE_CONTENT!r}"
    log(f"PASS tools non-stream ({model}): tool_calls id={call_id} name/arguments survive; final answer arrives")


def check_tools_stream(client: OpenAI, model: str) -> None:
    # Turn 1: streaming tool-call turn — delta tool_calls fragments (id + name on the
    # first fragment, partial arguments across two) aggregate to the committed call.
    stream = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "mode=tools&stream=1 What is the weather in Paris?"}],
        tools=[WEATHER_TOOL],
        stream=True,
)
    calls: dict[int, dict] = {}
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.tool_calls:
            for tc in chunk.choices[0].delta.tool_calls:
                slot = calls.setdefault(tc.index, {"id": None, "name": None, "arguments": ""})
                if tc.id:
                    slot["id"] = tc.id
                if tc.function and tc.function.name:
                    slot["name"] = tc.function.name
                if tc.function and tc.function.arguments:
                    slot["arguments"] += tc.function.arguments
    assert calls, "expected streamed tool_calls deltas"
    assert len(calls) == 1, f"expected exactly one tool call, got {len(calls)}"
    call = calls[0]
    assert call["id"], "streamed tool call missing id"
    assert call["name"] == "get_weather", f"streamed tool name {call['name']!r} != get_weather"
    assert json.loads(call["arguments"]) == {"city": "Paris"}, (
        f"streamed partial arguments {call['arguments']!r} must aggregate to {{\"city\": \"Paris\"}}"
)

    # Turn 2: feed the tool result, stream the final answer.
    final_stream = client.chat.completions.create(
        model=model,
        messages=[
            {"role": "user", "content": "mode=tools&stream=1 What is the weather in Paris?"},
            {"role": "assistant", "content": "checking", "tool_calls": [
                {"id": call["id"], "type": "function", "function": {"name": "get_weather", "arguments": call["arguments"]}}
            ]},
            {"role": "tool", "tool_call_id": call["id"], "content": '{"temp":18}'},
        ],
        tools=[WEATHER_TOOL],
        stream=True,
)
    deltas: list[str] = []
    for chunk in final_stream:
        if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
            deltas.append(chunk.choices[0].delta.content)
    final_content = "".join(deltas)
    assert final_content == FIXTURE_CONTENT, (
        f"tools stream final content {final_content!r} != {FIXTURE_CONTENT!r}"
)
    log(f"PASS tools stream ({model}): streamed tool_calls id={call['id']} survive; final stream aggregates")


# ---------------------------------------------------------------- errors

def check_error(
    client: OpenAI,
    model: str,
    marker: str,
    exc_type,
    expected_status: int,
    expected_type: str,
    expected_code: str | None = None,
    message_contains: str | None = None,
) -> None:
    """One OpenAI-shaped error assertion: SDK typed exception + envelope JSON."""
    try:
        client.chat.completions.create(model=model, messages=[{"role": "user", "content": marker}])
    except exc_type as e:  # noqa: PERF203 — the catch order IS the assertion
        assert e.status_code == expected_status, (
            f"{type(e).__name__} status {e.status_code} != expected {expected_status}"
)
        err = e.response.json()["error"]
        assert err["type"] == expected_type, f"error.type {err['type']!r} != {expected_type!r}"
        if expected_code is not None:
            assert err.get("code") == expected_code, f"error.code {err.get('code')!r} != {expected_code!r}"
        if message_contains is not None:
            assert message_contains in (err.get("message") or ""), (
                f"error.message {err.get('message')!r} lacks {message_contains!r}"
)
        log(
            f"PASS {marker} ({model}): {type(e).__name__} status={expected_status} envelope "
            f"type={expected_type}{f' code={expected_code}' if expected_code else ''}"
)
        return
    except APIStatusError as e:
        raise AssertionError(
            f"expected {exc_type.__name__}, got {type(e).__name__} status={e.status_code}: {e}"
) from e
    raise AssertionError(f"expected {exc_type.__name__}, got a successful response")


def check_unknown_model(client: OpenAI) -> None:
    check_error(
        client,
        UNKNOWN_MODEL,
        "unknown-model",
        NotFoundError,
        404,
        "invalid_request_error",
        expected_code="model_not_found",
        message_contains=UNKNOWN_MODEL,
)


def check_errors_oo(client: OpenAI) -> None:
    check_error(client, DEFAULT_MODEL, "mode=401", AuthenticationError, 401, "authentication_error", message_contains="401")
    check_error(client, DEFAULT_MODEL, "mode=400", BadRequestError, 400, "api_error", message_contains="400")
    check_error(client, DEFAULT_MODEL, "mode=429", RateLimitError, 429, "rate_limit_error", message_contains="429")
    check_error(client, DEFAULT_MODEL, "mode=503", APIStatusError, 502, "server_error", message_contains="503")


def check_errors_oa(client: OpenAI) -> None:
    # The oa leg hits the fake Anthropic upstream: 401/400/429/529 map through the
    # same OpenAI envelope taxonomy (auth/upstream-4xx/rate-limit/upstream-5xx).
    check_error(client, OA_MODEL, "mode=401", AuthenticationError, 401, "authentication_error", message_contains="401")
    check_error(client, OA_MODEL, "mode=400", BadRequestError, 400, "api_error", message_contains="400")
    check_error(client, OA_MODEL, "mode=429", RateLimitError, 429, "rate_limit_error", message_contains="429")
    check_error(client, OA_MODEL, "mode=529", APIStatusError, 502, "server_error", message_contains="529")


# ---------------------------------------------------------------- drills

def check_eager_kill(client: OpenAI, model: str, bound: float) -> None:
    """Upstream dead before the request → 502 api_error, within the bound, no hang."""
    start = time.monotonic()
    try:
        client.chat.completions.create(model=model, messages=[{"role": "user", "content": "hello"}])
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
    "models": lambda a: check_models(make_client(a.base_url), a.models),
    "nonstream": lambda a: check_nonstream(make_client(a.base_url), a.model),
    "stream": lambda a: check_stream(make_client(a.base_url), a.model),
    "raw-done": lambda a: check_raw_done(a.base_url, a.model),
    "unknown-model": lambda a: check_unknown_model(make_client(a.base_url)),
    "eager-kill": lambda a: check_eager_kill(make_client(a.base_url), a.model, a.bound),
}

GROUPS = {
    # stage 1 regression on the oo leg + stage 2 oa legs + tools + both error sets.
    "all": [
        "models",
        "nonstream", "stream", "raw-done",  # oo (default model)
        "oa-nonstream", "oa-stream", "oa-raw-done", "oa-tools",  # oa cross-format
        "errors-oo", "errors-oa",
    ],
    "oo": ["models", "nonstream", "stream", "raw-done", "errors-oo"],
    "oa": ["oa-nonstream", "oa-stream", "oa-raw-done", "oa-tools", "errors-oa"],
    "errors-oo": ["errors-oo"],
    "errors-oa": ["errors-oa"],
    "eager-kill": ["eager-kill"],  # runner's oo kill-upstream drill
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--models", default="deepseek-v4-flash,claude-3-5-sonnet", help="expected models.list order")
    parser.add_argument("--check", default="all")
    parser.add_argument("--bound", type=float, default=5.0, help="drill wall-clock bound in seconds")
    parser.add_argument("--real", action="store_true", help="relax content assertions (real upstream)")
    args = parser.parse_args()

    args.models = [m.strip() for m in args.models.split(",") if m.strip()]
    client = make_client(args.base_url)
    oa_client = make_client(args.base_url)

    if args.check not in GROUPS:
        raise SystemExit(f"unknown --check {args.check!r}; expected one of {sorted(GROUPS)}")

    for name in GROUPS[args.check]:
        if name == "models":
            check_models(client, args.models)
        elif name == "nonstream":
            check_nonstream(client, args.model, relaxed=args.real)
        elif name == "stream":
            check_stream(client, args.model, relaxed=args.real)
        elif name == "raw-done":
            check_raw_done(args.base_url, args.model)
        elif name == "unknown-model":
            check_unknown_model(client)
        elif name == "eager-kill":
            check_eager_kill(client, args.model, args.bound)
        elif name == "oa-nonstream":
            check_nonstream(oa_client, OA_MODEL, relaxed=args.real)
        elif name == "oa-stream":
            check_stream(oa_client, OA_MODEL, relaxed=args.real)
        elif name == "oa-raw-done":
            check_raw_done(args.base_url, OA_MODEL)
        elif name == "oa-tools":
            check_tools_nonstream(oa_client, OA_MODEL)
            check_tools_stream(oa_client, OA_MODEL)
        elif name == "errors-oo":
            check_errors_oo(client)
        elif name == "errors-oa":
            check_errors_oa(oa_client)
        else:
            CHECKS[name](args)
    log(f"ALL PASS ({args.check}): {', '.join(GROUPS[args.check])} against {args.base_url}")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"FAIL: {e}", file=sys.stderr)
        sys.exit(1)
    except APIStatusError as e:
        print(f"FAIL: unexpected {type(e).__name__} status={e.status_code}: {e}", file=sys.stderr)
        sys.exit(1)
