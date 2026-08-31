#!/usr/bin/env python3
"""smoke_sdk.py — OpenAI-face SDK harness around the UNMODIFIED ``openai`` package.

The harness drives a fresh-venv, pinned ``openai`` client (base_url + dummy key only —
exactly what a drop-in OpenAI endpoint must serve) against a live Janus boot and
asserts the OpenAI-face surface:

  gate 1  non-streaming + streaming chat (partial deltas, aggregated content,
         terminal ``data: [DONE]`` — byte-level via one raw check)
  gate 2  ``GET /v1/models`` lists the configured model(s) in config order with
         ``owned_by`` = the backend name
  gate 3  OpenAI-shaped errors surfaced as the SDK's typed exceptions:
         401 → AuthenticationError / authentication_error
         404 (unknown model) → NotFoundError / model_not_found
         400 (upstream 4xx) → BadRequestError / api_error
         (envelope message is Janus's SYNTHESIZED status message, NOT the upstream
         body verbatim — the verbatim-passthrough contract)
         429 → RateLimitError / rate_limit_error
         503 (upstream 5xx) → 502 / server_error
  R&F    kill-upstream drills (eager + mid-stream) — no hang within hard bounds
         (the runner orchestrates the kills; this script runs one drill per call)

Check groups (``--check``):
  all          models + nonstream + stream + raw-done + errors (fake-upstream legs)
 happy models + nonstream + stream + raw-done (content pinned to the fixture)
  errors       unknown-model + 401 + 400 + 429 + 503
  real-happy   models + nonstream + stream + raw-done with relaxed content assertions
               (real DeepSeek returns arbitrary text; requires --models)
  bad-key      auth failure with a deliberately bad key against a real upstream (401)
  eager-kill   upstream dead before request → 502 api_error within the bound
  kill-midstream  raw SSE stream + mid-frame upstream kill → error frame, no hang
               (run in background; touches --ready-file after the first frame so the
               runner knows the kill will land mid-stream)

Usage:
  smoke_sdk.py --base-url http://127.0.0.1:8080/v1 --check all
  smoke_sdk.py --base-url http://127.0.0.1:8080/v1 --check real-happy --models deepseek-v4-flash,deepseek-v4-pro
  smoke_sdk.py --base-url http://127.0.0.1:8080/v1 --check eager-kill --bound 5
  smoke_sdk.py --base-url http://127.0.0.1:8080/v1 --check kill-midstream --ready-file /tmp/ready

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

from openai import APIStatusError, AuthenticationError, BadRequestError, NotFoundError, RateLimitError, OpenAI

# Wire truth from the golden fixtures (janus-core/src/test/resources/fixtures/openai):
# chat.response.json content + the content deltas of chat.stream.sse. The fake serves
# these bodies; the SDK client must see the same bytes through Janus.
FIXTURE_CONTENT = "The weather in Paris is 18 degrees with light rain."
FIXTURE_DELTAS = ["The", " weather", " in Paris", " is 18", " degrees with light rain."]

DEFAULT_MODEL = "deepseek-v4-flash"
UNKNOWN_MODEL = "no-such-model-janus-smoke"
DUMMY_KEY = "janus-smoke-dummy-key"


def make_client(base_url: str, api_key: str = DUMMY_KEY, timeout: float = 30.0) -> OpenAI:
    # max_retries=0: the SDK must not mask Janus's error semantics with client retries
    # (the SDK retries 408/409/429/5xx by default — that would break the error checks).
    return OpenAI(base_url=base_url, api_key=api_key, timeout=timeout, max_retries=0)


def log(msg: str) -> None:
    print(msg, flush=True)


# ---------------------------------------------------------------- gate 2

def check_models(client: OpenAI, expected: list[str]) -> None:
    data = client.models.list().data
    names = [m.id for m in data]
    assert names == expected, f"models.list returned {names}, expected config order {expected}"
    for m in data:
        assert m.owned_by == "deepseek", f"model {m.id} owned_by={m.owned_by!r}, expected 'deepseek'"
    log(f"PASS models.list -> {names} (config order, owned_by=deepseek)")


# ---------------------------------------------------------------- gate 1 (SDK)

def check_nonstream(client: OpenAI, model: str, relaxed: bool = False) -> None:
    resp = client.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": "What is the weather in Paris?"}],
)
    content = resp.choices[0].message.content
    if relaxed:
        assert content and content.strip(), "non-stream response content is blank"
        log(f"PASS non-stream chat ({model}): content non-blank ({len(content)} chars)")
    else:
        assert content == FIXTURE_CONTENT, (
            f"non-stream content {content!r} != golden fixture {FIXTURE_CONTENT!r}"
)
        log(f"PASS non-stream chat ({model}): content byte-matches the golden fixture")


def check_stream(client: OpenAI, model: str, relaxed: bool = False) -> None:
    deltas: list[str] = []
    # "mode=stream" is the fake-upstream marker (Janus passes message content through
    # unchanged); without it the fake serves the non-stream JSON and Janus sees an
    # empty SSE stream. gate finding — the streaming leg must hit the SSE mode.
    stream = client.chat.completions.create(
        model=model,
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


# ------------------------------------------------------ gate 1 (raw [DONE])

def check_raw_done(base_url: str, model: str) -> None:
    """Byte-level proof that the live stream terminates with ``data: [DONE]``."""
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
    log(f"PASS raw SSE: {len(frames)} frames, terminal data: [DONE] (text/event-stream)")


# ---------------------------------------------------------------- gate 3

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
            f"PASS {marker}: {type(e).__name__} status={expected_status} envelope "
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


def check_kill_midstream(base_url: str, model: str, ready_file: str | None, bound: float) -> None:
    """Raw SSE client for the mid-stream kill drill: first frame → ready-file, then
    read until EOF. The runner kills the fake after ready-file appears; the connection
    drop mid-frame must surface as an SSE error frame + clean completion — never a
    hang, never an HTTP error mid-stream."""
    url = urllib.parse.urlsplit(base_url)
    conn = http.client.HTTPConnection(url.hostname, url.port, timeout=max(bound * 4, 30.0))
    body = json.dumps(
        {"model": model, "messages": [{"role": "user", "content": "mode=stream&pause=1"}], "stream": True}
)
    start = time.monotonic()
    conn.request("POST", "/v1/chat/completions", body=body, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    assert resp.status == 200, f"kill-midstream: HTTP {resp.status} (must be 200 — errors go in SSE frames)"
    frames: list[str] = []
    first_at: float | None = None
    error_frame: dict | None = None
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if not line.startswith("data:"):
            continue
        payload = line[len("data:") :].strip()
        frames.append(payload)
        if first_at is None:
            first_at = time.monotonic()
            if ready_file:
                Path(ready_file).touch()
        if payload.startswith("{"):
            try:
                obj = json.loads(payload)
                if isinstance(obj, dict) and "error" in obj:
                    error_frame = obj["error"]
            except json.JSONDecodeError:
                pass
    elapsed = time.monotonic() - start
    conn.close()
    assert first_at is not None, "kill-midstream: no data frame arrived before the stream ended"
 # Deterministic drill (): the kill lands mid-frame, so an SSE error frame MUST
    # arrive before the stream completes — not merely logged when present.
    assert error_frame is not None, "kill-midstream: expected an SSE error frame after the upstream kill"
    assert error_frame.get("type") == "api_error", (
        f"kill-midstream: SSE error frame type {error_frame.get('type')!r} != api_error"
)
    log(
        f"PASS kill-midstream: {len(frames)} frames, first delta at {first_at - start:.2f}s, "
        f"error frame: {error_frame is not None}, stream terminated in {elapsed:.2f}s (no hang)"
)


def check_bad_key(client: OpenAI, model: str) -> None:
    check_error(
        client,
        model,
        "bad-key",
        AuthenticationError,
        401,
        "authentication_error",
        message_contains="401",
)


# ---------------------------------------------------------------- main

CHECKS = {
    "models": lambda a: check_models(make_client(a.base_url), a.models),
    "nonstream": lambda a: check_nonstream(make_client(a.base_url), a.model),
    "stream": lambda a: check_stream(make_client(a.base_url), a.model),
    "raw-done": lambda a: check_raw_done(a.base_url, a.model),
    "unknown-model": lambda a: check_unknown_model(make_client(a.base_url)),
    "401": lambda a: check_error(make_client(a.base_url), a.model, "mode=401", AuthenticationError, 401, "authentication_error", message_contains="401"),
    "400": lambda a: check_error(make_client(a.base_url), a.model, "mode=400", BadRequestError, 400, "api_error", message_contains="400"),
    "429": lambda a: check_error(make_client(a.base_url), a.model, "mode=429", RateLimitError, 429, "rate_limit_error", message_contains="429"),
    "503": lambda a: check_error(make_client(a.base_url), a.model, "mode=503", APIStatusError, 502, "server_error", message_contains="503"),
    "eager-kill": lambda a: check_eager_kill(make_client(a.base_url), a.model, a.bound),
    "kill-midstream": lambda a: check_kill_midstream(a.base_url, a.model, a.ready_file, a.bound),
    "bad-key": lambda a: check_bad_key(make_client(a.base_url, api_key=DUMMY_KEY), a.model),
}

GROUPS = {
    "all": ["models", "nonstream", "stream", "raw-done", "unknown-model", "401", "400", "429", "503"],
    "happy": ["models", "nonstream", "stream", "raw-done"],
    "errors": ["unknown-model", "401", "400", "429", "503"],
    "real-happy": ["models", "nonstream", "stream", "raw-done"],
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080/v1")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--models", default="deepseek-v4-flash", help="expected models.list order (comma-separated)")
    parser.add_argument("--check", default="all")
    parser.add_argument("--bound", type=float, default=5.0, help="drill wall-clock bound in seconds")
    parser.add_argument("--ready-file", default=None, help="kill-midstream: touched after the first frame")
    parser.add_argument("--real", action="store_true", help="relax content assertions (real upstream)")
    args = parser.parse_args()

    args.models = [m.strip() for m in args.models.split(",") if m.strip()]

    if args.check in GROUPS:
        names = GROUPS[args.check]
    elif args.check in CHECKS:
        names = [args.check]
    else:
        raise SystemExit(f"unknown --check {args.check!r}; expected one of {sorted(GROUPS)} or {sorted(CHECKS)}")

    client = make_client(args.base_url)
    for name in names:
        fn = CHECKS[name]
        if name == "models":
            check_models(client, args.models)
        elif name in ("nonstream", "stream"):
            check_nonstream(client, args.model, relaxed=args.real) if name == "nonstream" else check_stream(
                client, args.model, relaxed=args.real
)
        else:
            fn(args)
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
