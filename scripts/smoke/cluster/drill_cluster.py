#!/usr/bin/env python3
"""Three-node cluster drill through HAProxy.

Hits the load-balancer listener for data/admin traffic and scrapes each
node's /metrics to prove exact shared-DB governance. Offline: golden fake
upstream (14/12 tokens → 5320 µUSD at the fixture 0.14/0.28 rates).

Exit 0 iff every assertion passes.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "store"))
from harness_common import (  # noqa: E402
    FIXTURE_CONTENT,
    GOLDEN_MICRO,
    MODEL,
    delete_key,
    generate_key,
    http_json,
    http_text,
    list_keys,
    scrape_metrics,
    series_value,
)

MARKER = "cluster-privacy-marker-7f3a9c"
RPM_WINDOW_SECONDS = 60  # the shared fixed-window RPM window (PgRateLimiter)


def log(msg: str) -> None:
    print(msg, flush=True)


def psql(container: str, sql: str, **vars_: str) -> str:
    for name, value in vars_.items():
        assert re.fullmatch(r"[0-9a-fA-F_-]+", value), f"refusing to interpolate {name}={value!r}"
        sql = sql.replace(f":'{name}'", f"'{value}'")
    cmd = ["docker", "exec", container, "psql", "-U", "janus", "-d", "janus", "-tAc", sql]
    out = subprocess.run(cmd, capture_output=True, text=True)
    assert out.returncode == 0, f"psql failed: {out.stderr.strip()}"
    return out.stdout.strip()


def chat(base: str, key: str, content: str, max_tokens: int | None = 16):
    body = {
        "model": MODEL,
        "messages": [{"role": "user", "content": content}],
    }
    if max_tokens is not None:
        body["max_tokens"] = max_tokens
    return http_json(
        "POST",
        f"{base}/chat/completions",
        body=body,
        headers={"x-api-key": key},
)


def current_aligned_window() -> int:
    """The epoch-aligned fixed-window start (seconds) — mirrors PgRateLimiter.windowStart
    (store/drill_multi_node.current_aligned_window)."""
    now = int(time.time())
    return now - (now % RPM_WINDOW_SECONDS)


def assert_rpm_rollover_exact(container: str, key_id: str, consumed: int) -> None:
    """A 60s fixed window rolled during the RPM leg: the shared DB must prove the
    rollover was EXACT — every consumed request accounted across the two touched
    windows and no single window ever over the rpm cap (the 'no flake on the
    epoch-aligned boundary' branch store/drill_multi_node.assert_counter_exact
    applies to its own 3-request leg)."""
    total = 0
    peak = 0
    for window in (current_aligned_window() - RPM_WINDOW_SECONDS, current_aligned_window()):
        value = int(
            psql(
                container,
                "SELECT COALESCE(SUM(count), 0) FROM rate_limits WHERE key_id = :'kid'"
                f" AND dimension = 'requests' AND window_start = {window}",
                kid=key_id,
)
            or "0"
)
        total += value
        peak = max(peak, value)
    assert total == consumed, f"rpm rollover: DB total consumed {total} != {consumed}"
    assert peak <= 2, f"rpm rollover: a single window exceeded the rpm:2 cap ({peak})"


def sum_node_cost(node_bases: list[str], key_id: str) -> float:
    total = 0.0
    for node in node_bases:
        body = scrape_metrics(node)
        total += series_value(body, "janus_key_cost_micro_usd_total", {"key_id": key_id})
    return total


def docker_logs(container: str) -> str:
    out = subprocess.run(["docker", "logs", container], capture_output=True, text=True)
    return (out.stdout or "") + (out.stderr or "")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lb", required=True, help="HAProxy origin, e.g. http://127.0.0.1:8080")
    parser.add_argument("--nodes", required=True, help="comma-separated per-node origins")
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--pg-container", required=True)
    parser.add_argument("--counter", default="")
    parser.add_argument("--janus-containers", default="janus-cluster-1,janus-cluster-2,janus-cluster-3")
    args = parser.parse_args()

    lb = args.lb.rstrip("/")
    lb_v1 = f"{lb}/v1"
    nodes = [n.rstrip("/") for n in args.nodes.split(",") if n.strip()]
    assert len(nodes) == 3, f"expected 3 node origins, got {nodes}"
    containers = [c.strip() for c in args.janus_containers.split(",") if c.strip()]
    master = args.master_key

    # --- health through LB and every node ---------------------------------
    for origin, label in [(lb, "lb"), *[(n, n) for n in nodes]]:
        status, _, _ = http_text(f"{origin}/health/readiness", timeout=5.0)
        assert status == 200, f"{label} /health/readiness → {status}"
    log("PASS health: LB + 3 nodes /health/readiness 200")

    status, _, _ = http_json(
        "POST",
        f"{lb_v1}/chat/completions",
        body={"model": MODEL, "messages": [{"role": "user", "content": "tripwire"}]},
        headers={"x-api-key": "no-key"},
)
    assert status == 401, f"keyless chat via LB → {status} (auth not on?)"
    log("PASS tripwire: keyless 401 through the LB")

    # --- key generated through LB authenticates (any node) ----------------
    key_id, full_key = generate_key(lb_v1, master, models=[MODEL], name="cluster-chat")
    listed = list_keys(lb_v1, master)
    assert any(item.get("id") == key_id for item in listed), "generated key missing from /key/list"
    for face, path, body in (
        (
            "openai",
            f"{lb_v1}/chat/completions",
            {"model": MODEL, "messages": [{"role": "user", "content": "hello"}], "max_tokens": 1024},
),
        (
            "anthropic",
            f"{lb}/v1/messages",
            {
                "model": MODEL,
                "max_tokens": 1024,
                "messages": [{"role": "user", "content": "hello"}],
            },
),
        (
            "responses",
            f"{lb_v1}/responses",
            {"model": MODEL, "input": "hello", "store": False},
),
):
        status, _, payload = http_json("POST", path, body=body, headers={"x-api-key": full_key})
        assert status == 200, f"{face} via LB → {status} ({payload})"
    stream_body = json.dumps(
        {
            "model": MODEL,
            "stream": True,
            "stream_options": {"include_usage": True},
            "messages": [{"role": "user", "content": "hello"}],
        }
).encode()
    stream_req = urllib.request.Request(
        f"{lb_v1}/chat/completions",
        data=stream_body,
        method="POST",
        headers={"x-api-key": full_key, "Content-Type": "application/json", "Accept": "text/event-stream"},
)
    with urllib.request.urlopen(stream_req, timeout=30.0) as stream_resp:
        stream_raw = stream_resp.read().decode("utf-8", "replace")
        assert stream_resp.status == 200, f"stream via LB → {stream_resp.status}"
        assert "data:" in stream_raw, f"stream body missing data: frames: {stream_raw[:200]!r}"
    log("PASS faces: OpenAI + Anthropic + Responses + stream through the LB")

    # --- RPM exact across nodes -------------------------------------------
    rpm_id, rpm_key = generate_key(lb_v1, master, models=[MODEL], name="cluster-rpm", rpm=2)
    ok = 0
    denied = None
    for i in range(4):
        status, hdrs, payload = chat(lb_v1, rpm_key, f"rpm-{i}")
        if status == 200:
            ok += 1
        elif status == 429:
            denied = (hdrs, payload)
            break
        else:
            raise AssertionError(f"rpm loop: unexpected {status} ({payload})")
    # Window-pinned: either the expected 429 (same-window exactness) OR the 60s
    # fixed window rolled during the leg — the DB proves the rollover was exact
    # either way (no spurious gate failure on the epoch-aligned boundary). A
    # rollover after request 2 fills both windows exactly (2 + 2), so the 4 loop
    # requests may all be 200s; the NEXT request must then still 429.
    if denied is None:
        status, hdrs, payload = chat(lb_v1, rpm_key, "rpm-next")
        if status == 429:
            denied = (hdrs, payload)
        else:
            raise AssertionError(f"rpm:2 admitted a 5th request after {ok} 200s ({status}) — window over cap?")
    if ok == 2:
        rpm_outcome = "2 admitted, 3rd 429"
    else:
        assert ok in (3, 4), f"rpm:2 admitted {ok}, expected 2 (or 3-4 across an exact fixed-window rollover)"
        assert_rpm_rollover_exact(args.pg_container, rpm_id, ok)
        rpm_outcome = f"{ok} admitted across an exact fixed-window rollover (DB-proven: no window over cap)"
    hdrs, payload = denied
    retry = hdrs.get("Retry-After") or hdrs.get("retry-after")
    assert retry is not None, f"RPM 429 missing Retry-After: {hdrs}"
    code = (payload or {}).get("error", {}).get("code")
    assert code == "rate_limit_exceeded", f"RPM envelope code={code}"
    rpm_ok = int(
        psql(
            args.pg_container,
            "SELECT count(*) FROM calls WHERE key_id = :'kid' AND status = 'OK'",
            kid=rpm_id,
)
        or "0"
)
    assert rpm_ok == ok, f"rpm OK CallRecords={rpm_ok}, expected {ok}"
    log(f"PASS rpm: {rpm_outcome} Retry-After={retry} (OK CallRecords={rpm_ok})")

    # --- TPM conservative preflight ---------------------------------------
    tpm_id, tpm_key = generate_key(lb_v1, master, models=[MODEL], name="cluster-tpm", tpm=100)
    status, hdrs, payload = chat(lb_v1, tpm_key, "tpm-preflight", max_tokens=None)  # estimate 4096 > 100
    assert status == 429, f"tpm:100 omitted max_tokens should 429, got {status} ({payload})"
    assert (hdrs.get("Retry-After") or hdrs.get("retry-after")), "TPM 429 missing Retry-After"
    log("PASS tpm: conservative preflight 429 (estimate 4096 > cap 100)")

    # --- hard budget + exact spend ----------------------------------------
    # 2 × 5320 µUSD = 0.01064; 3rd request denied pre-dispatch
    bud_id, bud_key = generate_key(
        lb_v1, master, models=[MODEL], name="cluster-budget", budget_usd=0.01064
)
    settled = 0
    budget_denied = False
    for i in range(4):
        status, hdrs, payload = chat(lb_v1, bud_key, f"budget-{i}", max_tokens=1)
        if status == 200:
            settled += 1
        elif status == 429:
            code = (payload or {}).get("error", {}).get("code")
            assert code == "insufficient_quota", f"budget 429 code={code}"
            assert not (hdrs.get("Retry-After") or hdrs.get("retry-after")), "budget 429 must not carry Retry-After"
            budget_denied = True
            break
        else:
            raise AssertionError(f"budget loop: unexpected {status} ({payload})")
    assert settled == 2, f"budget admitted {settled}, expected 2"
    assert budget_denied, "budget never 429'd"
    bud_ok = int(
        psql(
            args.pg_container,
            "SELECT count(*) FROM calls WHERE key_id = :'kid' AND status = 'OK'",
            kid=bud_id,
)
        or "0"
)
    assert bud_ok == 2, f"budget OK CallRecords={bud_ok}, expected 2"
    spend = int(psql(args.pg_container, "SELECT settled FROM spend WHERE key_id = :'kid'", kid=bud_id) or "0")
    pending = int(psql(args.pg_container, "SELECT pending FROM spend WHERE key_id = :'kid'", kid=bud_id) or "0")
    assert spend == 2 * GOLDEN_MICRO, f"spend.settled={spend}, expected {2 * GOLDEN_MICRO}"
    assert pending == 0, f"spend.pending={pending}, expected 0"
    node_sum = sum_node_cost(nodes, bud_id)
    assert abs(node_sum - spend) < 1, f"node metrics sum {node_sum} != DB spend {spend}"
    log(f"PASS budget: 2 settled, 3rd 429 pre-dispatch; DB={spend} == node-sum={node_sum:.0f}")

    # --- soft-cap warning (reserve-time flag, non-stream header) -----------
    # Cap 6000 µUSD; max_tokens=20 ⇒ output reserve 5600 > 0.8×cap, still < cap
    # so the request is admitted and the warning headers ride the 200.
    soft_id, soft_key = generate_key(
        lb_v1, master, models=[MODEL], name="cluster-soft", budget_usd=0.006
)
    status, hdrs, payload = chat(lb_v1, soft_key, "soft-cap", max_tokens=20)
    assert status == 200, f"soft-cap chat → {status} ({payload})"
    warning = hdrs.get("X-Janus-Budget-Warning") or hdrs.get("x-janus-budget-warning")
    used = hdrs.get("X-Janus-Budget-Used-Micro-Usd") or hdrs.get("x-janus-budget-used-micro-usd")
    assert warning == "soft", f"expected X-Janus-Budget-Warning: soft, got {warning!r} / {hdrs}"
    assert used is not None, "missing X-Janus-Budget-Used-Micro-Usd"
    log(f"PASS soft-cap: warning=soft used={used} µUSD")

    # --- revoke is immediate on every node --------------------------------
    rev_id, rev_key = generate_key(lb_v1, master, models=[MODEL], name="cluster-revoke")
    status, _, _ = chat(lb_v1, rev_key, "pre-revoke")
    assert status == 200, f"pre-revoke chat → {status}"
    delete_key(lb_v1, master, key_id=rev_id)
    status, _, payload = chat(lb_v1, rev_key, "post-revoke")
    assert status == 403, f"revoked key via LB → {status} ({payload}), expected 403"
    log("PASS revoke: 403 on the next request through the LB")

    # --- privacy: prompt marker never appears in /metrics or node logs ----
    _, priv_key = generate_key(lb_v1, master, models=[MODEL], name="cluster-privacy")
    status, _, payload = chat(lb_v1, priv_key, MARKER)
    assert status == 200, f"privacy chat → {status}"
    # fixture content is the response; still plant the marker in the request
    for node in nodes:
        body = scrape_metrics(node)
        assert MARKER not in body, f"prompt marker leaked into /metrics on {node}"
    for container in containers:
        logs = docker_logs(container)
        assert MARKER not in logs, f"prompt marker leaked into {container} logs"
        assert full_key not in logs, f"virtual key leaked into {container} logs"
        assert master not in logs, f"master key leaked into {container} logs"
    log("PASS privacy: marker + key material absent from /metrics and node logs")

    # --- spend / calls rows exist for the chat key ------------------------
    calls = int(
        psql(
            args.pg_container,
            "SELECT count(*) FROM calls WHERE key_id = :'kid'",
            kid=key_id,
)
        or "0"
)
    assert calls >= 3, f"expected ≥3 CallRecords for chat key, got {calls}"
    log(f"PASS calls table: {calls} records for the chat key (shared DB)")

    # --- kill node 2: LB still serves -------------------------------------
    n2 = containers[1]
    subprocess.run(["docker", "stop", n2], check=True, capture_output=True)
    # HAProxy fall 2 × (inter 2s + timeout check 3s) ≈ 10s before n2 is DOWN.
    time.sleep(12)
    try:
        deadline = time.monotonic() + 25
        served = False
        last = None
        while time.monotonic() < deadline:
            try:
                status, _, payload = http_json(
                    "POST",
                    f"{lb_v1}/chat/completions",
                    body={
                        "model": MODEL,
                        "messages": [{"role": "user", "content": "after-kill"}],
                        "max_tokens": 16,
                    },
                    headers={"x-api-key": full_key},
                    timeout=5.0,
)
                last = status
                if status == 200:
                    served = True
                    break
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                last = exc
            time.sleep(0.5)
        assert served, f"LB did not serve after stopping {n2} (last {last})"
        lb_up = False
        for _ in range(8):
            try:
                status, _, _ = http_text(f"{lb}/health/readiness", timeout=3.0)
                if status == 200:
                    lb_up = True
                    break
            except (urllib.error.URLError, TimeoutError, OSError):
                pass
            time.sleep(0.5)
        assert lb_up, "LB /health after kill never returned 200"
        n2_down = False
        try:
            n2_status, _, _ = http_text(f"{nodes[1]}/health/readiness", timeout=2.0)
            n2_down = n2_status != 200
        except (urllib.error.URLError, TimeoutError, OSError):
            n2_down = True
        assert n2_down, f"stopped node still serving health"
        log(f"PASS failover: stopped {n2}, LB still chats + /health 200")
    finally:
        subprocess.run(["docker", "start", n2], check=True, capture_output=True)
        deadline = time.monotonic() + 60
        recovered = False
        while time.monotonic() < deadline:
            try:
                status, _, _ = http_text(f"{nodes[1]}/health/readiness", timeout=3.0)
                if status == 200:
                    recovered = True
                    break
            except (urllib.error.URLError, TimeoutError, OSError):
                pass
            time.sleep(0.5)
        if not recovered:
            raise AssertionError(f"{n2} did not recover /health after start")
        log(f"PASS recovery: {n2} healthy again")

    # --- golden content on a clean request --------------------------------
    status, _, payload = chat(lb_v1, full_key, "golden")
    assert status == 200, f"golden chat → {status} ({payload})"
    content = payload["choices"][0]["message"]["content"]
    assert content == FIXTURE_CONTENT, f"golden content mismatch: {content!r}"
    log("PASS golden body through the LB")

    log("ALL cluster drills passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
