#!/usr/bin/env python3
"""drill_keys.py — governance 1 key-lifecycle drill ().

Drives a live Janus boot (config.fake.toml — auth ON via JANUS_MASTER_KEY, one alias
``deepseek-v4-flash`` → the OpenAI-format fake serving the committed golden bodies) and
proves the whole key lifecycle over real sockets with the UNMODIFIED pinned SDKs
(fresh venv,  pins):

  1. Admin API: POST /key/generate with the master key returns an ``sk-janus-…`` key
     (shown exactly once — GET /key/list and POST /key/delete never echo the full
     string); /key/delete revokes by key_id (idempotent: unknown id → deleted:false);
     a WRONG master key → 401 authentication_error; a missing master key → 401.
  2. Valid virtual key: requests succeed on BOTH faces (raw + OpenAI SDK + Anthropic
     SDK, streaming + non-streaming) with the golden content.
  3. Unknown key → 401 authentication_error (face envelope).
  4. Revoked key (deleted mid-drill) → 403 permission_error (the  wording
     decision — 403-revoked, the reference-aligned; recorded in RESULTS.md + governance.md).
  5. Scope denial: a key scoped to ``["other-alias"]`` calling ``deepseek-v4-flash`` →
     403 permission_error.

Exit 0 = all assertions pass. Uses the pinned SDKs only for the success legs (the
error legs assert the raw HTTP envelopes — the SDK clients map them identically).

Usage:
  drill_keys.py --base-url http://127.0.0.1:PORT/v1 --master-key <key> [--rounds 2]
"""
from __future__ import annotations

import argparse
import json
import sys

from anthropic import Anthropic
from openai import OpenAI

from harness_common import (
    DUMMY_KEY,
    FIXTURE_CONTENT,
    MODEL,
    admin_base_url,
    delete_key,
    generate_key,
    http_json,
    list_keys,
    sdk_base_url,
)


def log(msg: str) -> None:
    print(msg, flush=True)


def make_openai(base_url: str, api_key: str) -> OpenAI:
    return OpenAI(base_url=base_url, api_key=api_key, timeout=30.0, max_retries=0)


def make_anthropic(base_url: str, api_key: str) -> Anthropic:
    return Anthropic(base_url=sdk_base_url(base_url), api_key=api_key, timeout=30.0, max_retries=0)


def assert_content(actual: str, what: str) -> None:
    assert actual == FIXTURE_CONTENT, f"{what}: content {actual!r} != golden fixture {FIXTURE_CONTENT!r}"


def one_openai_nonstream(client: OpenAI, idx: int) -> None:
    resp = client.chat.completions.create(
        model=MODEL, messages=[{"role": "user", "content": f"w33-keys-o-{idx}"}]
    )
    assert_content(resp.choices[0].message.content, "openai non-stream")


def one_openai_stream(client: OpenAI, idx: int) -> None:
    deltas: list[str] = []
    stream = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": f"mode=stream w33-keys-o-{idx}"}],
        stream=True,
    )
    for chunk in stream:
        if chunk.choices and chunk.choices[0].delta and chunk.choices[0].delta.content:
            deltas.append(chunk.choices[0].delta.content)
    assert len(deltas) >= 2, f"openai stream: expected >= 2 deltas, got {len(deltas)}"
    assert_content("".join(deltas), "openai stream")


def one_anthropic_nonstream(client: Anthropic, idx: int) -> None:
    resp = client.messages.create(
        model=MODEL, max_tokens=1024, messages=[{"role": "user", "content": f"w33-keys-a-{idx}"}]
    )
    texts = [b.text for b in resp.content if getattr(b, "type", None) == "text"]
    assert_content("".join(texts), "anthropic non-stream")


def one_anthropic_stream(client: Anthropic, idx: int) -> None:
    deltas: list[str] = []
    stopped = False
    stream = client.messages.create(
        model=MODEL,
        max_tokens=1024,
        messages=[{"role": "user", "content": f"mode=stream w33-keys-a-{idx}"}],
        stream=True,
    )
    for event in stream:
        if event.type == "content_block_delta" and getattr(event.delta, "type", None) == "text_delta":
            deltas.append(event.delta.text)
        elif event.type == "message_stop":
            stopped = True
    assert stopped, "anthropic stream must terminate with message_stop"
    assert len(deltas) >= 2, f"anthropic stream: expected >= 2 deltas, got {len(deltas)}"
    assert_content("".join(deltas), "anthropic stream")


def assert_openai_error(url: str, key: str, expected_status: int, expected_type: str, what: str) -> None:
    status, _, payload = http_json(
        "POST",
        f"{url}/chat/completions",
        body={"model": MODEL, "messages": [{"role": "user", "content": what}]},
        headers={"x-api-key": key},
    )
    assert status == expected_status, f"{what}: expected {expected_status}, got {status} ({payload})"
    error = (payload or {}).get("error", {})
    assert error.get("type") == expected_type, f"{what}: error.type {error.get('type')!r} != {expected_type!r} ({payload})"


def assert_anthropic_error(url: str, key: str, expected_status: int, expected_type: str, what: str) -> None:
    status, _, payload = http_json(
        "POST",
        f"{url}/messages",
        body={
            "model": MODEL,
            "max_tokens": 1024,
            "messages": [{"role": "user", "content": what}],
        },
        headers={"x-api-key": key},
    )
    assert status == expected_status, f"{what}: expected {expected_status}, got {status} ({payload})"
    error = (payload or {}).get("error") or {}  # anthropic envelope: {"type":"error","error":{...}}
    wire_type = error.get("type")
    assert wire_type == expected_type, f"{what}: anthropic error.type {wire_type!r} != {expected_type!r} ({payload})"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="gateway base URL incl. /v1")
    parser.add_argument("--master-key", required=True, help="JANUS_MASTER_KEY value")
    parser.add_argument("--rounds", type=int, default=2)
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    master = args.master_key

    # ---- 1. admin API ----------------------------------------------------
    key_id, full_key = generate_key(base, master, models=[MODEL], name="w33-keys")
    assert full_key.startswith("sk-janus-"), f"key prefix wrong: {full_key[:20]}..."
    log(f"PASS /key/generate: sk-janus key created once (id={key_id})")

    listing = list_keys(base, master)
    ids = [item.get("id") for item in listing]
    assert key_id in ids, f"/key/list missing the new key (ids={ids})"
    joined = json.dumps(listing)
    assert full_key not in joined, "/key/list LEAKS the full key string"
    assert "hash" not in joined and "salt" not in joined, "/key/list leaks a hash/salt field"
    log("PASS /key/list: redacted records (no full key, no hash, no salt)")

    status, _, payload = http_json(
        "POST", f"{admin_base_url(base)}/key/generate", body={"name": "wrong"}, headers={"x-api-key": "wrong-master-key"}
    )
    assert status == 401 and (payload or {}).get("error", {}).get("type") == "authentication_error", (
        f"wrong master key: expected 401 authentication_error, got {status} ({payload})"
    )
    log("PASS /key/generate with a WRONG master key → 401 authentication_error")

    status, _, payload = http_json("POST", f"{admin_base_url(base)}/key/generate", body={"name": "none"})
    assert status == 401, f"missing master key: expected 401, got {status} ({payload})"
    log("PASS /key/generate with NO master key → 401")

    # ---- 2. valid virtual key — both faces, raw + SDKs -------------------
    openai = make_openai(base, full_key)
    anthropic = make_anthropic(base, full_key)
    for i in range(args.rounds):
        one_openai_nonstream(openai, i)
        one_openai_stream(openai, i)
        one_anthropic_nonstream(anthropic, i)
        one_anthropic_stream(anthropic, i)
    log(f"PASS valid key: {args.rounds} rounds × 4 cells (oo/ao non-stream+stream) golden content")

    # ---- 3. unknown key → 401 -------------------------------------------
    assert_openai_error(base, "sk-janus-unknown-00000000", 401, "authentication_error", "unknown key (oo)")
    assert_anthropic_error(base, "sk-janus-unknown-00000000", 401, "authentication_error", "unknown key (ao)")
    log("PASS unknown key → 401 authentication_error (both faces)")

    # ---- 4. revoked key → 403 --------------------------------------------
    revoke_id, revoke_key = generate_key(base, master, models=[MODEL], name="w33-revoke")
    deleted = delete_key(base, master, key_id=revoke_id)
    assert deleted.get("deleted") is True, f"revoke: deleted flag {deleted}"
    assert_openai_error(base, revoke_key, 403, "permission_error", "revoked key (oo)")
    assert_anthropic_error(base, revoke_key, 403, "permission_error", "revoked key (ao)")
    log("PASS revoked key → 403 permission_error (both faces;  decision: 403-revoked, not literal-401)")

    # ---- 5. scope denial → 403 -------------------------------------------
    scoped_id, scoped_key = generate_key(base, master, models=["other-alias"], name="w33-scope")
    assert scoped_id
    assert_openai_error(base, scoped_key, 403, "permission_error", "scope-denied (oo)")
    assert_anthropic_error(base, scoped_key, 403, "permission_error", "scope-denied (ao)")
    log("PASS scope-denied key → 403 permission_error (both faces)")

    # ---- 6. delete by full key + unknown-id semantics -----------------------
    del2_id, del2_key = generate_key(base, master, models=[MODEL], name="w33-del2")
    deleted = delete_key(base, master, full_key=del2_key)
    assert deleted.get("deleted") is True, f"delete-by-key: {deleted}"
    assert del2_key not in json.dumps(deleted), "/key/delete response echoes the full key"
    deleted = delete_key(base, master, key_id=del2_id)  # idempotent: existing id revokes again
    assert deleted.get("deleted") is True, f"re-revoke of an existing id should stay deleted:true ({deleted})"
    deleted = delete_key(base, master, key_id="00000000000000000000000000000000")  # unknown id
    assert deleted.get("deleted") is False, f"unknown id should report deleted:false ({deleted})"
    log("PASS delete-by-key (never echoed) + idempotent re-revoke + unknown-id deleted:false")

    print("drill_keys: ALL PASS")


if __name__ == "__main__":
    main()
