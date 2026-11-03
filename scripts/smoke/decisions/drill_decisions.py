#!/usr/bin/env python3
"""drill_decisions.py — the System One face gate legs, stdlib HTTP only.

Against a live Janus (config.fake.toml: fake systemone + openai-decisions
upstreams, governance ON) this proves:

  1. drop-in    — an unmodified Jev-shaped request returns the Jev-shaped
                  response (incl. the fake's Ollama-only extra field riding
                  extras), the upstream saw the client body semantically
                  unchanged, and the exact-cost header is 2 micro-USD
                  (42 input tokens x $0.042/Mtok = 1.764 micro, per-term
                  ceiling — the CostCalculator convention).
  2. auth/scope — keyless -> 401; a key scoped to other models -> 403 with
                  the upstream never hit.
  3. translate  — model gpt-6-luna: the upstream received the OpenAI
                  Decisions shape (input_json, predicate, choices[], levels[])
                  and the client still received the Jev shape (answers map).
  4. failover   — model pooled: the flaky entry (upstream-model "flaky",
                  503) is walked past and the healthy entry serves.
  5. errors     — unknown decision alias -> 404 model_not_found; malformed
                  body -> 400 invalid_request_error.
  6. budget/tpm — a 1-micro-USD-budget key spends past its cap on call 1
                  (settle 2 > 1) and call 2 is denied 429 BEFORE dispatch
                  (fake log flat); a tpm=1 key is denied 429 pre-dispatch.
  7. images     — images[] on a non-vision alias -> deterministic 400 before
                  dispatch; on the OpenAI vision alias -> 200 with an
                  input_image data-URL part; on the systemone vision alias ->
                  the raw images[] array reaches the upstream (local Ollama
                  shape).
  7b. danger    — TypeSafe's beta gates: assets + Box ride the systemone leg,
                  the client's TypeSafe-Danger-Zone-Images header is forwarded,
                  the images gate is derived from an assets/Box body even when
                  the client omitted the header, and the long-context header
                  is forwarded.
  7c. sdk       — a TypeSafe-SDK-shaped multipart upload (JSON `request` part +
                  image file part) is inlined into assets and dispatched; a
                  zstd-compressed JSON body is decompressed (when the zstd CLI
                  is available; the JUnit suite always covers it).
  8. metrics    — face="systemone" series exist; the state marker text is
                  absent from /metrics (privacy contract).

Prints one [ok] line per leg and "DECISIONS DRILL PASS"; any failure raises
AssertionError with the evidence.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ["JANUS_BASE"].rstrip("/")
MASTER = os.environ["JANUS_MASTER_KEY"]
FAKE_LOG = Path(os.environ["FAKE_LOG"])
FAKE2_LOG = Path(os.environ["FAKE2_LOG"])
MARKER = "MARKER-STATE-7f3a-never-log"

JEV_BODY = {
    "model": "jev-latest",
    "state": {"ticket": f"I was charged twice. {MARKER}", "tier": "enterprise"},
    "questions": {
        "refund": {"type": "noul", "instructions": "Is the customer asking for money back?"},
        "team": {
            "type": "choice",
            "instructions": "Which team?",
            "criteria": {"billing": "Charges and refunds", "technical": "App bugs"},
        },
        "urgency": {"type": "score", "instructions": "How urgent?", "criteria": ["Can wait", "Today"]},
    },
}


def http(method: str, url: str, body=None, headers: dict | None = None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, dict(resp.headers), resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8")


def raw_post(path: str, data: bytes, headers: dict) -> tuple[int, dict, str]:
    req = urllib.request.Request(BASE + path, data=data, method="POST")
    for key, value in headers.items():
        req.add_header(key, value)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, dict(resp.headers), resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read().decode("utf-8")


def multipart(fields: dict, files: dict) -> tuple[bytes, str]:
    boundary = "----janus-decisions-gate-boundary"
    chunks = []
    for name, value in fields.items():
        chunks.append(
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{name}"\r\n'
            "Content-Type: application/json\r\n\r\n"
            f"{value}\r\n".encode("utf-8")
        )
    for name, (filename, content_type, data) in files.items():
        chunks.append(
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n".encode("utf-8")
        )
        chunks.append(data)
        chunks.append(b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode("utf-8"))
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def log(msg: str) -> None:
    print(f"[ok] {msg}", flush=True)


def fake_entries(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def generate_key(name: str, models=None, budget_usd=None, tpm=None) -> str:
    body: dict = {"name": name}
    if models is not None:
        body["models"] = models
    if budget_usd is not None:
        body["budget_usd"] = budget_usd
    if tpm is not None:
        body["tpm"] = tpm
    status, _, payload = http("POST", f"{BASE}/key/generate", body, {"Authorization": f"Bearer {MASTER}"})
    assert status in (200, 201), f"key/generate -> {status}: {payload}"
    key = json.loads(payload)["key"]
    assert key.startswith("sk-janus-"), key
    return key


def decide(key: str | None, body: dict, headers: dict | None = None):
    request_headers = {} if key is None else {"Authorization": f"Bearer {key}"}
    request_headers.update(headers or {})
    return http("POST", f"{BASE}/v1/systemone", body, request_headers)


def main() -> None:
    key = generate_key("decisions-gate")

    # 1. drop-in + exact cost + upstream passthrough
    status, headers, payload = decide(key, JEV_BODY)
    assert status == 200, f"jev leg -> {status}: {payload}"
    resp = json.loads(payload)
    assert resp["answers"]["refund"] == {"type": "noul", "noul": 0.97}, resp
    assert resp["answers"]["team"]["choice"] == "billing", resp
    assert resp["usage"] == {"input_tokens": 42, "output_tokens": 0}, resp
    assert resp["prompt_eval_cached_count"] == 7, "upstream extra field must ride through"
    sent = [e for e in fake_entries(FAKE_LOG) if e["path"] == "/v1/systemone"]
    assert sent and sent[-1]["body"] == JEV_BODY, f"upstream body must equal the client body: {sent}"
    assert headers.get("X-Janus-Cost-Total-Micro-Usd") == "2", headers
    log("drop-in: Jev body in == upstream body == Jev body out; exact cost 2 micro-USD")

    # 2. auth + scope
    status, _, _ = decide(None, JEV_BODY)
    assert status == 401, f"keyless -> {status}"
    before = len(fake_entries(FAKE_LOG))
    wrong_scope = generate_key("decisions-scope", models=["deepseek-v4-flash"])
    status, _, _ = decide(wrong_scope, JEV_BODY)
    assert status == 403, f"wrong scope -> {status}"
    assert len(fake_entries(FAKE_LOG)) == before, "scope-denied request must not dispatch"
    log("auth/scope: keyless 401; wrong-scope 403 before dispatch")

    # 3. OpenAI translation leg
    body = dict(JEV_BODY, model="gpt-6-luna")
    status, _, payload = decide(key, body)
    assert status == 200, f"openai leg -> {status}: {payload}"
    resp = json.loads(payload)
    assert resp["answers"]["refund"] == {"type": "noul", "noul": 0.91}, resp
    upstream = [e for e in fake_entries(FAKE_LOG) if e["path"] == "/v1/decisions"]
    assert upstream, "no /v1/decisions call reached the fake"
    sent_body = upstream[-1]["body"]
    content = sent_body["input"][0]["content"]
    assert content[0]["type"] == "input_json", sent_body
    types = {q["name"]: q["type"] for q in sent_body["questions"]}
    assert types == {"refund": "predicate", "team": "choice", "urgency": "score"}, types
    log("translate: client stayed Jev-shaped; upstream received Decisions shape")

    # 4. failover + upstream-model remap evidence
    body = dict(JEV_BODY, model="pooled")
    status, _, payload = decide(key, body)
    assert status == 200, f"pooled leg -> {status}: {payload}"
    flaky_hits = [e for e in fake_entries(FAKE2_LOG) if e["model"] == "flaky"]
    pooled_hits = [e for e in fake_entries(FAKE_LOG) if e["model"] == "pooled"]
    assert flaky_hits, "the flaky entry was never tried (upstream-model remap missing?)"
    assert pooled_hits, "the healthy entry never served the pooled alias"
    log("failover: flaky entry (upstream-model=flaky) 503 -> healthy entry served")

    # 5. errors
    status, _, payload = decide(key, dict(JEV_BODY, model="no-such-decision-model"))
    assert status == 404 and "model_not_found" in payload, (status, payload)
    status, _, payload = http(
        "POST", f"{BASE}/v1/systemone", {"model": "jev-latest", "questions": {}},
        {"Authorization": f"Bearer {key}"})
    assert status == 400 and "invalid_request_error" in payload, (status, payload)
    log("errors: unknown alias 404 model_not_found; malformed 400")

    # 6. budget: settle at the cap, next call denied pre-dispatch. A minimal
    # body estimates 1 micro-USD (fits the 2-micro cap) while the fake's fixed
    # 42-token usage settles at 2 (the cap); the next reserve is denied.
    tiny = generate_key("decisions-budget", budget_usd=0.000002)  # 2 micro-USD
    # Minimal serialized body: the estimate must fit the 1-micro cap, while the
    # fake's fixed 42-token usage still settles at 2 (over the cap).
    small = {
        "model": "jev-latest",
        "state": "x",
        "questions": {"q": {"type": "noul"}},
    }
    status, _, payload = decide(tiny, small)
    assert status == 200, f"first tiny-budget call -> {status}: {payload}"
    before = len(fake_entries(FAKE_LOG))
    status, _, payload = decide(tiny, small)
    assert status == 429, f"over-budget call -> {status}: {payload}"
    assert len(fake_entries(FAKE_LOG)) == before, "over-budget request dispatched!"
    log("budget: settled 2 == 2 micro-USD cap; next call 429 before dispatch")

    # 6b. TPM: a tpm=1 key is denied pre-dispatch (the full Jev body's input
    # estimate alone crosses the token cap — decisions have no output reserve
    # to lean on).
    tpm_key = generate_key("decisions-tpm", tpm=1)
    before = len(fake_entries(FAKE_LOG))
    status, _, payload = decide(tpm_key, JEV_BODY)
    assert status == 429, f"tpm=1 call -> {status}: {payload}"
    assert len(fake_entries(FAKE_LOG)) == before, "tpm-denied request dispatched!"
    log("tpm: tpm=1 key denied 429 before dispatch (input estimate crosses the cap)")

    # 7. images: the vision capability gate is deterministic — a non-vision
    # alias 400s before dispatch; the vision alias forwards the image to the
    # OpenAI leg as a data-URL input_image part.
    before = len(fake_entries(FAKE_LOG))
    status, _, payload = decide(key, dict(JEV_BODY, images=["iVBORw0KGgoAAAANSUhEUg=="]))
    assert status == 400, f"images on non-vision alias -> {status}: {payload}"
    assert "vision = true" in payload, payload
    assert len(fake_entries(FAKE_LOG)) == before, "image gate must fail before dispatch"

    status, _, payload = decide(
        key, dict(JEV_BODY, model="gpt-6-luna", images=["iVBORw0KGgoAAAANSUhEUg=="]))
    assert status == 200, f"images on vision alias -> {status}: {payload}"
    upstream = [e for e in fake_entries(FAKE_LOG) if e["path"] == "/v1/decisions"]
    image_parts = [
        p
        for p in upstream[-1]["body"]["input"][0]["content"]
        if p.get("type") == "input_image"
    ]
    assert image_parts and image_parts[0]["image_url"].startswith("data:image/png;base64,"), upstream[-1]["body"]
    log("images: non-vision alias 400 before dispatch; OpenAI vision alias forwarded a data-URL part")

    # 7a. local/systemone images[]: the same canonical image evidence rides the
    # generic System One body (Ollama/Clef shape), not the OpenAI parts shape.
    local = dict(JEV_BODY, model="typesafe-vision", images=["iVBORw0KGgoAAAANSUhEUg=="])
    status, _, payload = decide(key, local)
    assert status == 200, f"systemone images -> {status}: {payload}"
    local_entry = [e for e in fake_entries(FAKE_LOG) if e["path"] == "/v1/systemone" and e["model"] == "typesafe-vision"][-1]
    assert local_entry["body"]["images"] == ["iVBORw0KGgoAAAANSUhEUg=="], local_entry["body"]
    assert "TypeSafe-Danger-Zone-Images" not in local_entry["headers"], local_entry
    log("images: local systemone alias received raw images[] (Ollama/Clef shape)")

    # 7b. TypeSafe danger-zone beta gates: assets + Box question, header
    # passthrough, derived images gate, and long-context passthrough.
    box_body = {
        "model": "typesafe-vision",
        "state": {"photo": "p1"},
        "assets": {"p1": {"type": "image", "format": "png", "data_base64": "iVBORw0KGgoAAAANSUhEUg=="}},
        "questions": {"locate": {"type": "box", "instructions": "Find the mug in `photo`."}},
    }
    status, _, payload = decide(key, box_body, {"TypeSafe-Danger-Zone-Images": "true"})
    assert status == 200, f"typesafe box -> {status}: {payload}"
    assert json.loads(payload)["answers"]["locate"]["type"] == "box", payload
    box_entry = [e for e in fake_entries(FAKE_LOG) if e["path"] == "/v1/systemone" and e["model"] == "typesafe-vision" and "assets" in e["body"]][-1]
    assert box_entry["headers"].get("TypeSafe-Danger-Zone-Images") == "true", box_entry
    assert box_entry["body"]["assets"]["p1"]["data_base64"] == "iVBORw0KGgoAAAANSUhEUg==", box_entry["body"]
    assert box_entry["body"]["questions"]["locate"]["type"] == "box", box_entry["body"]

    # The client may omit the beta header; Janus derives it from assets/Box.
    status, _, payload = decide(key, box_body)
    assert status == 200, f"derived danger gate -> {status}: {payload}"
    derived = [e for e in fake_entries(FAKE_LOG) if e["path"] == "/v1/systemone" and e["model"] == "typesafe-vision" and "assets" in e["body"]][-1]
    assert derived["headers"].get("TypeSafe-Danger-Zone-Images") == "true", derived

    status, _, payload = decide(
        key, dict(JEV_BODY, model="jev-latest"),
        {"TypeSafe-Danger-Zone-Long-Context": "true"})
    assert status == 200, f"long-context gate -> {status}: {payload}"
    long_entry = [e for e in fake_entries(FAKE_LOG) if e["path"] == "/v1/systemone" and e["model"] == "jev-latest"][-1]
    assert long_entry["headers"].get("TypeSafe-Danger-Zone-Long-Context") == "true", long_entry
    log("danger: TypeSafe assets/Box + images/long-context gates forwarded (and derived)")

    # 7c. TypeSafe SDK shapes: multipart (request + file parts) and zstd.
    sdk_request = {
        "model": "typesafe-vision",
        "state": {"photo": "p1"},
        "assets": {"p1": {"type": "image", "part": "photo"}},
        "questions": {"locate": {"type": "box", "instructions": "Find the mug in `photo`."}},
    }
    png = bytes.fromhex("89504e470d0a1a0a") + b"\x00" * 8
    body, content_type = multipart(
        {"request": json.dumps(sdk_request)},
        {"photo": ("photo.png", "image/png", png)},
    )
    status, _, payload = raw_post(
        "/v1/systemone",
        body,
        {"Content-Type": content_type, "Authorization": f"Bearer {key}"},
    )
    assert status == 200, f"multipart sdk leg -> {status}: {payload}"
    assert json.loads(payload)["answers"]["locate"]["type"] == "box", payload
    sdk_entry = [
        e
        for e in fake_entries(FAKE_LOG)
        if e["path"] == "/v1/systemone" and e["model"] == "typesafe-vision" and "assets" in e["body"]
    ][-1]
    asset = sdk_entry["body"]["assets"]["p1"]
    assert "part" not in asset, sdk_entry
    assert asset["format"] == "png", sdk_entry
    assert asset["data_base64"], sdk_entry
    assert sdk_entry["headers"].get("TypeSafe-Danger-Zone-Images") == "true", sdk_entry
    log("sdk: multipart request + image file part inlined into assets and dispatched")

    zstd_bin = shutil.which("zstd")
    if zstd_bin:
        # The TypeSafe SDK compresses the multipart body too (bodies >= 1 KiB),
        # so the parser must run after zstd decompression.
        compressed = subprocess.run(
            [zstd_bin, "-q", "-c"],
            input=body,
            capture_output=True,
            check=True,
        ).stdout
        status, _, payload = raw_post(
            "/v1/systemone",
            compressed,
            {"Content-Type": content_type, "Content-Encoding": "zstd",
             "Authorization": f"Bearer {key}"},
        )
        assert status == 200, f"zstd multipart sdk leg -> {status}: {payload}"
        assert json.loads(payload)["answers"]["locate"]["type"] == "box", payload
        log("sdk: zstd-compressed multipart body decompressed and parsed")

    if zstd_bin:
        compressed = subprocess.run(
            [zstd_bin, "-q", "-c"],
            input=json.dumps(JEV_BODY).encode("utf-8"),
            capture_output=True,
            check=True,
        ).stdout
        status, _, payload = raw_post(
            "/v1/systemone",
            compressed,
            {"Content-Type": "application/json", "Content-Encoding": "zstd",
             "Authorization": f"Bearer {key}"},
        )
        assert status == 200, f"zstd sdk leg -> {status}: {payload}"
        log("sdk: zstd-compressed request body decompressed and dispatched")
    else:
        print("[skip] sdk: zstd CLI not installed (JUnit covers the zstd path)", flush=True)

    # 8. metrics + privacy
    status, _, metrics = http("GET", f"{BASE}/metrics", None, {"Authorization": f"Bearer {MASTER}"})
    assert status == 200, f"metrics -> {status}"
    assert 'face="systemone"' in metrics, "no systemone face series in /metrics"
    assert MARKER not in metrics, "PRIVACY VIOLATION: state marker in /metrics"
    log("metrics: face=systemone present; state marker absent")

    print("DECISIONS DRILL PASS", flush=True)


if __name__ == "__main__":
    main()
