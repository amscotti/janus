"""harness_common.py — shared helpers for the governance gate.

The stage 4 fake upstream writes its request log (--counter-file) as the same small
JSON document the routing gate uses (``{"name": ..., "requests": N, "streams": N,
"errors": N}``), so the throttled-never-dispatched assertions reuse the counter
helpers verbatim. On top of that this module adds the stage 4 HTTP vocabulary every
drill shares: master-keyed admin calls (``/key/generate|delete|list``), raw and SDK
model-route calls on both faces, and the ``/metrics`` scrape parser (the Tier-1
exposition lines the design 2/3 legs assert against).

``sdk_base_url`` is the contract (the ``anthropic`` SDK appends ``/v1/messages``
itself); the OpenAI SDK gets the full ``/v1`` base.

Exit code contract: every drill exits 0 iff all its assertions pass; any failure
prints the reason on stderr and exits nonzero (the runner dies loudly).
"""

from __future__ import annotations

import json
import re
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

FIXTURE_CONTENT = "The weather in Paris is 18 degrees with light rain."
MODEL = "deepseek-v4-flash"
DUMMY_KEY = "janus-phase4-dummy-key"
GOLDEN_IN = 14
GOLDEN_OUT = 12
GOLDEN_MICRO = 5_320  # 14 × 0.14/1000 + 12 × 0.28/1000 = 0.00532 USD — the math


def read_counter(path: str | Path) -> dict:
    """Read the fake's counter document; missing file → all-zero counters.

    A file that exists but never parses (after retries for the fake's atomic-ish
    writes) RAISES — silently returning zeros would make 'counter flat' assertions
    pass spuriously on an unreadable counter file.
    """
    p = Path(path)
    if not p.exists():
        return {"name": p.name, "requests": 0, "streams": 0, "errors": 0}
    last_exc: Exception | None = None
    for _ in range(5):
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
        except (OSError, json.JSONDecodeError) as exc:
            last_exc = exc
            time.sleep(0.05)
    raise RuntimeError(f"counter file unreadable after 5 attempts: {p} ({last_exc})")


def snapshot(counter_paths: list[str]) -> dict[str, dict]:
    return {p: read_counter(p) for p in counter_paths}


def delta(before: dict[str, dict], after: dict[str, dict], key: str = "requests") -> dict[str, int]:
    return {p: after[p].get(key, 0) - before[p].get(key, 0) for p in before}


def wait_for(fn, timeout: float = 20.0, interval: float = 0.2, label: str = "condition") -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if fn():
                return True
        except AssertionError:
            pass
        time.sleep(interval)
    return False


def sdk_base_url(base_url: str) -> str:
    """Strip a trailing ``/v1`` for the anthropic SDK ( sdk_common contract)."""
    root = base_url.rstrip("/")
    if root.endswith("/v1"):
        root = root[: -len("/v1")]
    return root


def admin_base_url(base_url: str) -> str:
    """The admin API lives at the ROOT (``/key/generate|delete|list`` — NOT under
    ``/v1``); drills pass the model-route base (…/v1) and admin calls strip it."""
    root = base_url.rstrip("/")
    if root.endswith("/v1"):
        root = root[: -len("/v1")]
    return root


def platform_threads(pid: int) -> int:
    """OS/platform thread count of process ``pid`` (virtual threads never appear in
 ``ps nlwp`` — the sampling approach)."""
    out = subprocess.run(["ps", "-o", "nlwp=", "-p", str(pid)], capture_output=True, text=True)
    if out.returncode == 0 and out.stdout.strip():
        try:
            return int(out.stdout.strip().split()[0])
        except ValueError:
            pass
    out = subprocess.run(["ps", "-M", str(pid)], capture_output=True, text=True)
    if out.returncode == 0:
        lines = [line for line in out.stdout.splitlines() if line.strip()]
        return max(len(lines) - 1, 0)
    raise RuntimeError(f"cannot measure platform thread count for pid {pid}")


# ------------------------------------------------------------------ raw HTTP

def http_json(method: str, url: str, body=None, headers=None, timeout: float = 30.0):
    """Request returning (status, response-headers-dict, parsed-json-or-None).

    The janus JSON envelopes parse with tools.jackson; SDK-shaped bodies pass through.
    A non-JSON body (e.g. a 404 from /prometheus) yields ``None`` for the payload.
    """
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method=method)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
            payload = json.loads(raw) if raw.strip() else None
            return response.status, dict(response.headers), payload
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", "replace")
        payload = json.loads(raw) if raw.strip() else None
        return error.code, dict(error.headers), payload


def http_text(url: str, headers=None, timeout: float = 30.0):
    """GET returning (status, headers-dict, body-string)."""
    request = urllib.request.Request(url, method="GET")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, dict(response.headers), response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read().decode("utf-8", "replace")


# ------------------------------------------------------------------ admin API

KEY_ID_SHAPE = re.compile(r"[0-9a-f]{32}")


def generate_key(base_url: str, master_key: str, **caps) -> tuple[str, str]:
    """POST /key/generate with the master key; returns (key_id, full_key).

 ``caps`` maps to the optional fields: ``models`` (list), ``name``, ``budget_usd``,
    ``rpm``, ``tpm`` — absent = null caps.
    """
    body = {key: value for key, value in caps.items() if value is not None}
    status, _, payload = http_json(
        "POST", f"{admin_base_url(base_url)}/key/generate", body=body, headers={"x-api-key": master_key}
)
    assert status == 200, f"/key/generate: expected 200, got {status} ({payload})"
    assert payload and payload.get("key", "").startswith("sk-janus-"), f"key shape wrong: {payload}"
    key_id = payload["key_id"]
    # Hygiene: every drill interpolates key_id into psql SQL text (docker exec). The
    # ids are server-generated 32-hex; refusing anything else keeps a non-hex id from
    # ever flowing unescaped into a query.
    assert KEY_ID_SHAPE.fullmatch(key_id), f"key_id must be exactly 32 lowercase hex chars, got {key_id!r}"
    return key_id, payload["key"]


def delete_key(base_url: str, master_key: str, key_id: str | None = None, full_key: str | None = None) -> dict:
    body = {"key_id": key_id} if key_id else {"key": full_key}
    status, _, payload = http_json(
        "POST", f"{admin_base_url(base_url)}/key/delete", body=body, headers={"x-api-key": master_key}
)
    assert status == 200, f"/key/delete: expected 200, got {status} ({payload})"
    return payload or {}


def list_keys(base_url: str, master_key: str) -> list[dict]:
    status, _, payload = http_json("GET", f"{admin_base_url(base_url)}/key/list", headers={"x-api-key": master_key})
    assert status == 200, f"/key/list: expected 200, got {status} ({payload})"
    return (payload or {}).get("keys", [])


# ------------------------------------------------------------------ /metrics

def scrape_metrics(base_url: str) -> str:
    """GET /metrics — served at the ROOT path (not /v1)."""
    status, _, body = http_text(f"{admin_base_url(base_url)}/metrics", timeout=30.0)
    assert status == 200, f"/metrics: expected 200, got {status}"
    return body


def _is_series_line(line: str, name: str) -> bool:
    """True iff ``line`` is an exposition sample for exactly ``name`` — the name
    immediately followed by '{' (labeled), whitespace (unlabeled), or end-of-line —
    never a sibling series that merely starts with the name (e.g. Micrometer's
    ``<name>_created`` timestamp samples, whose ~1.7e9 value would silently corrupt
    an absolute-value sum)."""
    if not line.startswith(name):
        return False
    rest = line[len(name):]
    return rest == "" or rest[0] in "{\t "


def series_value(body: str, name: str, labels: dict[str, str] | None = None) -> float:
    """Sum the values of every exposition line for ``name`` (optionally label-filtered).

    A missing series yields 0.0 (deltas stay arithmetic; the drills' "non-zero"
    assertions then fail loudly for absent series).
    """
    total = 0.0
    for line in body.splitlines():
        if not _is_series_line(line, name):
            continue
        if labels:
            matched = True
            for key, value in labels.items():
                if f'{key}="{value}"' not in line:
                    matched = False
                    break
            if not matched:
                continue
        match = re.search(r"(\d+(?:\.\d+)?)$", line)
        if match:
            total += float(match.group(1))
    return total


def series_present(body: str, name: str) -> bool:
    return any(_is_series_line(line, name) for line in body.splitlines())
