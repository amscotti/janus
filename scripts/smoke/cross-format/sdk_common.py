"""sdk_common.py — shared URL normalization for the stage 2 SDK harnesses.

The pinned ``anthropic`` SDK appends ``/v1/messages`` to ``base_url`` itself, so any
Anthropic client construction must see the ROOT (``http://host:port``) — never the
``/v1`` prefix the runner passes. The pinned ``openai`` SDK, by contrast, takes the
base URL verbatim and appends ``/chat/completions`` — no normalization needed there.

The runner passes ``--base-url http://127.0.0.1:PORT/v1``; every harness file that
constructs an Anthropic client routes it through :func:`sdk_base_url` so a
double-prefixed ``/v1/v1/messages`` (404) can never happen on any leg
(smoke_anthropic.py, abort_drill.py follow-ups, stress_streams.py workers).
"""

from __future__ import annotations


def sdk_base_url(base_url: str) -> str:
    """Normalize a Janus gateway base URL for the ``anthropic`` SDK: strip a trailing
    ``/v1`` (the SDK appends ``/v1/messages`` itself). Accept both conventions
    defensively (the runner passes ``http://host:port/v1``; callers may pass the
    root directly)."""
    root = base_url.rstrip("/")
    if root.endswith("/v1"):
        root = root[: -len("/v1")]
    return root
