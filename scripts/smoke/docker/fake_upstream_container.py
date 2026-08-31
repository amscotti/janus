#!/usr/bin/env python3
"""fake_upstream_container.py — docker container adapter (test-only).

Runs the COMMITTED phase5 fake upstream (scripts/smoke/store/fake_upstream.py,
mounted read-only — no copy/duplication; the golden corpus stays in
janus-core/src/test/resources/fixtures, also mounted read-only) inside the
compose `fake-upstream` service.

The only delta: the store-smoke fake binds 127.0.0.1 (the host-runner convention —
the phase5 drills run it on the host), which is unreachable from other compose
containers. This wrapper rebinds the server to 0.0.0.0 before importing the
fake: `from http.server import ThreadingHTTPServer` resolves the name at import
time, so the patch lands before the server is constructed. All fake CLI args
(--port, --name, --fixtures, ...) pass through untouched.
"""
from __future__ import annotations

import http.server
import sys

_original = http.server.ThreadingHTTPServer


class _BindAll(_original):
    def __init__(self, addr, *args, **kwargs):
        super().__init__(("0.0.0.0", addr[1]), *args, **kwargs)


http.server.ThreadingHTTPServer = _BindAll
sys.path.insert(0, "/app")

import fake_upstream  # noqa: E402  (must follow the patch — import-time name binding)

if __name__ == "__main__":
    fake_upstream.main()
