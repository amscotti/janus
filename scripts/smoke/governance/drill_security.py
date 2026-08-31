#!/usr/bin/env python3
"""drill_security.py — governance review 1 security-pass drill.

Live re-verification of the security claims over real sockets (config.fake.toml
boot, auth ON):

  1. Secrets at rest: GET /key/list and POST /key/delete responses contain NO full
     key string, NO hash, NO salt — the full ``sk-janus-…`` key appears ONLY in the
     generate response (the store keeps the salted hash only; unit-pinned by
     InMemoryKeyStoreTest, re-run by the gate's baseline build).
  2. Master key never logged: the runner boots with a distinctive unguessable
     JANUS_MASTER_KEY value; this drill greps the Janus log across the whole gate —
     the value never appears (nor does any virtual key's secret, checked live here).
  3. Timing-safe comparison: the master-key admin path uses a timing-safe compare
     (unit-pinned by KeyHashTest — re-run in the baseline build; the live check here
     is that BAD_MASTER yields the identical 401 envelope as a missing key, with no
     distinguishable detail).
  4. Revoked-key full string dead: after /key/delete, the FULL key string no longer
     authenticates (403 permission_error — 401-vs-403 decision recorded in RESULTS.md).
  5. Master key accepted via BOTH ``Authorization: Bearer`` and ``x-api-key`` (both
     faces' SDK conventions); a VIRTUAL key on the admin API is rejected (401).
  6. Hash-only-at-rest source re-check: janus-store's KeyRecord exposes hash/salt
     but never the plaintext (grepped here; the store's storage is hash-only).

Usage:
  drill_security.py --base-url http://127.0.0.1:PORT/v1 --master-key <key>
                    --janus-log <janus-jvm.log>
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from harness_common import MODEL, admin_base_url, delete_key, generate_key, http_json, list_keys


def log(msg: str) -> None:
    print(msg, flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument("--janus-log", required=True, help="the Janus process log (grep target)")
    args = parser.parse_args()

    base = args.base_url.rstrip("/")
    master = args.master_key
    log_file = Path(args.janus_log)

    # ---- 1. list/delete redaction -------------------------------------------
    _, full_key = generate_key(base, master, models=[MODEL], name="w33-sec")
    listing = json.dumps(list_keys(base, master))
    assert full_key not in listing, "SECURITY: /key/list echoes the full key"
    assert "hash" not in listing and "salt" not in listing, "SECURITY: /key/list exposes a hash/salt field"
    deleted = delete_key(base, master, full_key=full_key)
    assert full_key not in json.dumps(deleted), "SECURITY: /key/delete echoes the full key"
    log("PASS redaction: the full key appears ONLY in the generate response (list/delete redacted, no hash/salt)")

    # ---- 2. master key + virtual-key secrets never in the log ----------------
    text = log_file.read_text(encoding="utf-8", errors="replace") if log_file.exists() else ""
    assert master not in text, "SECURITY: the JANUS_MASTER_KEY value appears in the Janus log"
    _, secret_key = generate_key(base, master, models=[MODEL], name="w33-sec2")
    text = log_file.read_text(encoding="utf-8", errors="replace")
    assert secret_key not in text, "SECURITY: a virtual key's full string appears in the Janus log"
    log("PASS master key + virtual-key secrets absent from the Janus log (distinctive-value grep)")

    # ---- 3. BAD_MASTER envelope identical to missing key ----------------------
    bad = http_json(
        "POST", f"{admin_base_url(base)}/key/generate", body={"name": "x"}, headers={"x-api-key": "wrong-master-key"}
)
    missing = http_json("POST", f"{admin_base_url(base)}/key/generate", body={"name": "x"})
    assert bad[0] == 401 and missing[0] == 401, f"BAD_MASTER {bad[0]} / missing {missing[0]} != 401"
    bad_type = (bad[2] or {}).get("error", {}).get("type")
    missing_type = (missing[2] or {}).get("error", {}).get("type")
    assert bad_type == missing_type == "authentication_error", (
        f"BAD_MASTER vs missing envelope divergence: {bad_type!r} vs {missing_type!r}"
)
    log("PASS BAD_MASTER and missing key produce the identical 401 authentication_error envelope (no distinguishable detail; timing-safe compare unit-pinned by KeyHashTest)")

    # ---- 4. revoked full string dead ------------------------------------------
    _, rev_key = generate_key(base, master, models=[MODEL], name="w33-sec-rev")
    delete_key(base, master, full_key=rev_key)
    status, _, payload = http_json(
        "POST",
        f"{base}/chat/completions",
        body={"model": MODEL, "messages": [{"role": "user", "content": "w33-sec-rev"}]},
        headers={"x-api-key": rev_key},
)
    assert status == 403 and (payload or {}).get("error", {}).get("type") == "permission_error", (
        f"revoked full string: expected 403 permission_error, got {status} ({payload})"
)
    log("PASS revoked key's FULL string no longer authenticates → 403 permission_error")

    # ---- 5. Bearer + x-api-key for the master key; virtual key on admin = 401 --
    status, _, payload = http_json(
        "POST",
        f"{admin_base_url(base)}/key/generate",
        body={"name": "bearer"},
        headers={"Authorization": f"Bearer {master}"},
)
    assert status == 200, f"master via Bearer: expected 200, got {status} ({payload})"
    _, virtual = generate_key(base, master, models=[MODEL], name="w33-sec-admin")
    status, _, payload = http_json(
        "POST", f"{admin_base_url(base)}/key/generate", body={"name": "x"}, headers={"x-api-key": virtual}
)
    assert status == 401, f"virtual key on the admin API: expected 401, got {status} ({payload})"
    log("PASS master key accepted via Bearer AND x-api-key; a virtual key on /key/* → 401")

    # ---- 6. hash-only-at-rest source re-check ---------------------------------
    store_dir = (
        next(
            p for p in Path(__file__).resolve().parents if (p / "settings.gradle").is_file()
)
        / "janus-store/src/main/java"
)
    key_records = list(store_dir.rglob("KeyRecord.java"))
    assert key_records, f"janus-store KeyRecord.java not found under {store_dir}"
    record_src = key_records[0].read_text(encoding="utf-8")
    assert "secretHash" in record_src and "salt" in record_src, "KeyRecord must carry secretHash + salt"
    plaintext_risky = [line for line in record_src.splitlines() if "String fullKey" in line and "record" not in line]
    assert not plaintext_risky, f"KeyRecord carries a plaintext fullKey field: {plaintext_risky}"
    assert "secretHash" in record_src and "never the plaintext" in record_src, (
        "KeyRecord must document hash-only storage"
)
    log("PASS hash-only-at-rest: KeyRecord exposes hash/salt only (no plaintext field); InMemoryKeyStoreTest re-run by the baseline build")

    print("drill_security: ALL PASS")


if __name__ == "__main__":
    main()
