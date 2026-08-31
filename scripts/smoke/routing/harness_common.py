"""harness_common.py — shared counter/thread helpers for the routing gate.

Every stage 3 fake upstream writes its request log (--counter-file) as a small JSON
document: ``{"name": ..., "requests": N, "streams": N, "errors": N}``, rewritten under
a lock on every request. The drills snapshot these files between phases — the
fairness-split, breaker-refusal and failover-served evidence — so the helpers here
must tolerate a missing file (fake just booted / not yet hit) and a concurrent rewrite
(atomic rename via write_text on a fresh path is not guaranteed, so reads retry).

``platform_threads`` is the sampling approach (``ps nlwp`` — virtual threads never
appear in platform-thread counts); the fairness drill measures the *Janus* process.
"""

from __future__ import annotations

import json
import subprocess
import time
from pathlib import Path


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
    """Snapshot every fake's counters: path → counter document."""
    return {p: read_counter(p) for p in counter_paths}


def delta(before: dict[str, dict], after: dict[str, dict], key: str = "requests") -> dict[str, int]:
    """Per-counter-path request/stream/error delta between two snapshots."""
    return {p: after[p].get(key, 0) - before[p].get(key, 0) for p in before}


def wait_for(
    fn,
    timeout: float = 20.0,
    interval: float = 0.2,
    label: str = "condition",
) -> bool:
    """Poll ``fn`` (throwing on success → return truthy) until truthy or timeout."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if fn():
                return True
        except AssertionError:
            pass
        time.sleep(interval)
    return False


def platform_threads(pid: int) -> int:
    """OS/platform thread count of process ``pid`` (never virtual threads)."""
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


def sdk_base_url(base_url: str) -> str:
    """Normalize a Janus gateway base URL for the ``anthropic`` SDK: strip a trailing
 ``/v1`` (the SDK appends ``/v1/messages`` itself) — the sdk_common contract."""
    root = base_url.rstrip("/")
    if root.endswith("/v1"):
        root = root[: -len("/v1")]
    return root
