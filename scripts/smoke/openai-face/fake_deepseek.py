#!/usr/bin/env python3
"""fake_deepseek.py — stdlib-only fake DeepSeek upstream for the stage 1 e2e gate.

Serves ``POST /v1/chat/completions`` with scripted bodies derived from the golden
fixtures (``janus-core/src/test/resources/fixtures/openai/``), so the deterministic
gate legs run fully offline and the committed corpus stays the single source of wire
truth. No ``openai`` package, no
third-party imports.

Modes — selected by a marker in the request body (the smoke client puts it in the
user message content; Janus passes the body through unchanged, so the marker reaches
the fake verbatim):

  mode=nonstream   (default) 200 JSON from ``chat.response.json``
  mode=stream                SSE stream replayed from ``chat.stream.sse`` at a cadence
  mode=401 / 400 / 429 / 503 non-2xx error bodies (``errors/deepseek.<code>.json``
                             fixtures; 503 is synthesized from the 5xx classification
                             shape — no committed 503 fixture exists)

Drill hooks (kill-upstream drills, deterministic):
  pause=1  (with mode=stream) — after the first complete data frame AND a partial
           frame are flushed, write ``--paused-file`` and hold (polling
           ``--resume-file``) until the killer fires. Killing the process mid-hold
           lands the upstream connection drop deterministically mid-frame: the
           adapter's ``SseFrameParser`` sees EOF in a pending frame →
           ``SseParseException`` → Janus emits an SSE error frame → clean
           completion, never a hang.

Usage:
  python3 fake_deepseek.py [--port 9876] [--fixtures DIR] [--frame-delay 0.05]
                           [--paused-file PATH] [--resume-file PATH] [--pause-hold 120]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODE_RE = re.compile(r"mode=(nonstream|stream|401|400|429|503)")
DONE = "[DONE]"


class FakeDeepSeek:
    """Stateless enough for one process; the HTTP handler calls straight in."""

    def __init__(self, fixtures: Path, frame_delay: float, paused_file, resume_file, pause_hold: float):
        self.fixtures = fixtures
        self.frame_delay = frame_delay
        self.paused_file = paused_file
        self.resume_file = resume_file
        self.pause_hold = pause_hold
        self.stream_frames = self._load_stream_frames()

    # ------------------------------------------------------------- fixtures

    def _load_stream_frames(self) -> list[str]:
        text = (self.fixtures / "chat.stream.sse").read_text(encoding="utf-8")
        frames = []
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("data:"):
                frames.append(line[len("data:") :].strip())
        if not frames or frames[-1] != DONE:
            raise SystemExit("chat.stream.sse must end with data: [DONE]")
        return frames

    def _nonstream_body(self) -> bytes:
        return (self.fixtures / "chat.response.json").read_bytes()

    def _error_body(self, mode: str) -> bytes:
        if mode in ("401", "400", "429"):
            return (self.fixtures / "errors" / f"deepseek.{mode}.json").read_bytes()
        # 503: no committed fixture; shape mirrors the classification table
        # (TYPE_UPSTREAM_5XX → 502 server_error). The fake's 503 body is reference-only.
        return json.dumps(
            {
                "error": {
                    "message": "upstream temporarily unavailable",
                    "type": "server_error",
                    "param": None,
                    "code": None,
                }
            }
).encode("utf-8")

    # ------------------------------------------------------------- serving

    def handle_chat(self, handler: BaseHTTPRequestHandler, raw_body: bytes) -> None:
        text = raw_body.decode("utf-8", "replace")
        match = MODE_RE.search(text)
        mode = match.group(1) if match else "nonstream"
        pause = "pause=1" in text

        if mode not in ("nonstream", "stream"):
            handler.send_response(int(mode))
            handler.send_header("Content-Type", "application/json")
            handler.end_headers()
            handler.wfile.write(self._error_body(mode))
            return

        if mode == "nonstream":
            handler.send_response(200)
            handler.send_header("Content-Type", "application/json")
            handler.end_headers()
            handler.wfile.write(self._nonstream_body())
            return

        # mode=stream
        handler.send_response(200)
        handler.send_header("Content-Type", "text/event-stream")
        handler.send_header("Cache-Control", "no-cache")
        handler.end_headers()
        if pause:
            self._stream_with_pause(handler)
        else:
            for payload in self.stream_frames:
                handler.wfile.write(f"data: {payload}\n\n".encode("utf-8"))
                handler.wfile.flush()
                if payload != DONE:
                    time.sleep(self.frame_delay)

    def _stream_with_pause(self, handler: BaseHTTPRequestHandler) -> None:
        # Frame 1 in full (the client proves a partial token arrived), then a partial
        # frame with no terminator: the adapter blocks in readLine mid-frame, so the
        # drill's resume-file → clean close is an EOF-in-pending-frame — a deterministic
        # SSE error frame. (A process kill is racy: RST vs half-open socket can block
        # the adapter until the 60s idle watchdog — longer than the drill's bound.)
        handler.wfile.write(f"data: {self.stream_frames[0]}\n\n".encode("utf-8"))
        handler.wfile.flush()
        # A COMPLETE data line WITHOUT its blank-line terminator: the parser sees a
        # pending frame (sawData), so the close below is an EOF-in-pending-frame →
        # SseParseException → SSE error frame. (A partial line with no newline is
        # indistinguishable from a clean close and would end with [DONE], not an error.)
        handler.wfile.write(f"data: {self.stream_frames[1]}\n".encode("utf-8"))
        handler.wfile.flush()
        if self.paused_file:
            Path(self.paused_file).touch()
        deadline = time.monotonic() + self.pause_hold
        while time.monotonic() < deadline:
            if self.resume_file and Path(self.resume_file).exists():
                break
            time.sleep(0.2)
        # Resume-file appeared → close the connection mid-frame (EOF) instead of
        # resuming: the adapter must surface the truncated frame as an SSE error frame,
        # never hang. Best-effort: the connection may already be gone.
        return


class Handler(BaseHTTPRequestHandler):
    fake: FakeDeepSeek = None  # set in main()

    # pylint: disable=invalid-name
    def do_GET(self) -> None:  # readiness probe (runner polls this)
        body = b"ok"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:
        if self.path != "/v1/chat/completions":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            self.fake.handle_chat(self, raw)
        except (BrokenPipeError, ConnectionResetError):
            pass  # client gone mid-stream — expected during kill drills

    def log_message(self, fmt, *args):  # concise logs to stderr
        sys.stderr.write("[fake-deepseek] " + fmt % args + "\n")


def _default_fixtures() -> Path:
    """Repo-root-relative default for --fixtures.

    Walks up to the repo marker (robust to the harness scripts living at any
    depth under the repo); falls back to the legacy parent-count path when no
    marker exists — e.g. inside the compose fake-upstream container, where the
    golden fixtures arrive via an explicit --fixtures mount and this default is
    never used (argparse evaluates defaults eagerly, so it must not raise).
    """
    here = Path(__file__).resolve()
    for p in here.parents:
        if (p / "settings.gradle").is_file():
            return p / "janus-core/src/test/resources/fixtures/openai"
    return here.parent.parent.parent.parent / "janus-core/src/test/resources/fixtures/openai"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=9876)
    parser.add_argument(
        "--fixtures",
        type=Path,
        default=_default_fixtures(),
)
    parser.add_argument("--frame-delay", type=float, default=0.05)
    parser.add_argument("--paused-file", type=Path, default=None)
    parser.add_argument("--resume-file", type=Path, default=None)
    parser.add_argument("--pause-hold", type=float, default=120.0)
    args = parser.parse_args()

    if not args.fixtures.is_dir():
        raise SystemExit(f"fixtures dir not found: {args.fixtures}")

    Handler.fake = FakeDeepSeek(
        fixtures=args.fixtures,
        frame_delay=args.frame_delay,
        paused_file=args.paused_file,
        resume_file=args.resume_file,
        pause_hold=args.pause_hold,
)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"fake-deepseek listening on 127.0.0.1:{args.port} (fixtures: {args.fixtures})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
