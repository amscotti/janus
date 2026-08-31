#!/usr/bin/env python3
"""fake_upstream.py — stdlib-only fake OpenAI-compatible upstream for the stage 3 e2e
gate  — the ``fake_openai_compat.py`` pattern extended for per-backend drill
control.

Serves ``POST /v1/chat/completions`` (non-stream JSON + SSE stream + two-turn tools) with
scripted bodies read from the committed golden corpus (``janus-core/src/test/
resources/fixtures/openai/`` + the matrix tools bodies), so the deterministic
gate legs run fully offline and the corpus stays the single source of wire truth.
Both stage 3 backends (``--name fake1`` / ``--name fake2``) serve the SAME golden
bodies, so either backend winning a failover is byte-identical to the client.

New for stage 3:
  --name          per-instance name (counters, diagnostics)
  --mode-file     a file the runner writes (nonstream|stream|500|429|hang|close) so
                  ONE backend can be driven to fail while the other stays healthy
                  WITHOUT killing it — the deterministic breaker/health/retry drills
                  (mode-file overrides the body markers while present)
  --counter-file  JSON request log rewritten on every request (per-backend
                  request/stream/error counts — the fairness + breaker-refusal
                  evidence the runner snapshots between drill phases)
  hang / close    accept-then-close modes (transport EOF → the adapter's `network`
                  retryable path): `hang` sends 200 + headers + a partial body/frame
                  then closes (EOF mid-body); `close` closes with no response bytes.

Body-marker modes (fallback when no mode-file): mode=nonstream
(default) / stream / tools / 401 / 400 / 429 / 503; pause=1 (with mode=stream) is the
Deterministic mid-stream close (one complete frame, then a partial frame with
no blank-line terminator, hold on --resume-file → EOF-in-pending-frame → SSE error
frame, never a hang).

Abort-drill recording: every stream whose client connection closes before the final
frame is recorded to --abort-log ().

Usage:
  python3 fake_upstream.py [--port 9877] [--name fake1] [--fixtures DIR]
                           [--frame-delay 0.05] [--mode-file PATH] [--counter-file PATH]
                           [--paused-file PATH] [--resume-file PATH] [--pause-hold 120]
                           [--abort-log PATH]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODE_RE = re.compile(r"mode=(nonstream|stream|tools|401|400|429|503)")
DONE = "[DONE]"

# mode-file values (absent/empty → body markers drive; anything else → error/close).
MODE_FILE_MODES = {"nonstream", "stream", "500", "429", "400", "401", "hang", "close"}


class FakeUpstream:
    """Stateless enough for one process; the HTTP handler calls straight in."""

    def __init__(
        self,
        name: str,
        fixtures: Path,
        frame_delay: float,
        mode_file: Path | None,
        counter_file: Path | None,
        paused_file,
        resume_file,
        pause_hold,
        abort_log,
):
        self.name = name
        self.fixtures = fixtures
        self.frame_delay = frame_delay
        self.mode_file = mode_file
        self.counter_file = counter_file
        self.paused_file = paused_file
        self.resume_file = resume_file
        self.pause_hold = pause_hold
        self.abort_log = abort_log
        self._counter_lock = threading.Lock()
        self._counters = {"name": name, "requests": 0, "streams": 0, "errors": 0}
        self.stream_frames = self._load_stream_frames()
        self.nonstream_body = (fixtures / "chat.response.json").read_bytes()
        matrix_root = fixtures.parent
        self.tools_body = (matrix_root / "matrix" / "oo" / "tools" / "upstream.response.json").read_bytes()
        self.errors = {
            "401": (fixtures / "errors" / "deepseek.401.json").read_bytes(),
            "400": (fixtures / "errors" / "deepseek.400.json").read_bytes(),
            "429": (fixtures / "errors" / "deepseek.429.json").read_bytes(),
            # 503: no committed fixture; shape mirrors the classification table
            # (TYPE_UPSTREAM_5XX → 502 server_error). Reference-only.
            "503": json.dumps(
                {
                    "error": {
                        "message": "upstream temporarily unavailable",
                        "type": "server_error",
                        "param": None,
                        "code": None,
                    }
                }
).encode("utf-8"),
            "500": json.dumps(
                {"error": {"message": "internal error", "type": "server_error", "param": None, "code": None}}
).encode("utf-8"),
            "429": (fixtures / "errors" / "deepseek.429.json").read_bytes(),
        }

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

    # ------------------------------------------------------------- counters

    def _bump(self, stream: bool, error: bool) -> None:
        with self._counter_lock:
            self._counters["requests"] += 1
            if stream:
                self._counters["streams"] += 1
            if error:
                self._counters["errors"] += 1
            if self.counter_file:
                try:
                    self.counter_file.write_text(
                        json.dumps(self._counters, sort_keys=True), encoding="utf-8"
)
                except OSError:
                    pass

    def _file_mode(self) -> str | None:
        if not self.mode_file or not self.mode_file.exists():
            return None
        try:
            value = self.mode_file.read_text(encoding="utf-8").strip()
        except OSError:
            return None
        return value if value in MODE_FILE_MODES else None

    # ------------------------------------------------------------- serving

    def handle_chat(self, handler: BaseHTTPRequestHandler, raw_body: bytes) -> None:
        text = raw_body.decode("utf-8", "replace")
        wants_stream = '"stream":true' in text or '"stream": true' in text
        match = MODE_RE.search(text)
        marker = match.group(1) if match else None
        pause = "pause=1" in text
        file_mode = self._file_mode()

        # mode-file drives the failure/close modes FIRST (deterministic drills).
        if file_mode in ("500", "429", "400", "401"):
            self._bump(stream=False, error=True)
            handler.send_response(int(file_mode))
            handler.send_header("Content-Type", "application/json")
            handler.end_headers()
            handler.wfile.write(self.errors[file_mode])
            return
        if file_mode == "hang":
            # Accept-then-close: 200 + headers + a partial body/frame, then EOF —
            # transport EOF → the adapter's `network` retryable path (fast failover).
            self._bump(stream=wants_stream, error=False)
            if wants_stream:
                handler.send_response(200)
                handler.send_header("Content-Type", "text/event-stream")
                handler.send_header("Cache-Control", "no-cache")
                handler.end_headers()
                handler.wfile.write(f"data: {self.stream_frames[0]}\n".encode("utf-8"))
                handler.wfile.flush()
            else:
                handler.send_response(200)
                handler.send_header("Content-Type", "application/json")
                handler.send_header("Content-Length", str(len(self.nonstream_body) + 1000))
                handler.end_headers()
                handler.wfile.write(self.nonstream_body[:64])
                handler.wfile.flush()
            handler.connection.close()
            return
        if file_mode == "close":
            # Close with no response bytes at all (fresh connection per request).
            self._bump(stream=False, error=True)
            handler.connection.close()
            return

        # Healthy path: body markers with file_mode as the default.
        mode = marker if marker else (file_mode if file_mode in ("stream", "nonstream") else "nonstream")
        is_stream = wants_stream or mode == "stream"

        if mode in ("401", "400", "429", "503"):
            self._bump(stream=False, error=True)
            handler.send_response(int(mode))
            handler.send_header("Content-Type", "application/json")
            handler.end_headers()
            handler.wfile.write(self.errors[mode])
            return

        if mode == "tools":
            has_tool_result = '"role":"tool"' in text or '"role": "tool"' in text
            if has_tool_result:
                if wants_stream or "stream=1" in text:
                    self._stream_plain(handler, pause)
                else:
                    self._bump(stream=False, error=False)
                    handler.send_response(200)
                    handler.send_header("Content-Type", "application/json")
                    handler.end_headers()
                    handler.wfile.write(self.nonstream_body)
            else:
                if wants_stream or "stream=1" in text:
                    self._stream_tool_calls(handler)
                else:
                    self._bump(stream=False, error=False)
                    handler.send_response(200)
                    handler.send_header("Content-Type", "application/json")
                    handler.end_headers()
                    handler.wfile.write(self.tools_body)
            return

        if mode == "stream" or is_stream:
            self._stream_plain(handler, pause)
            return

        # mode=nonstream (default)
        self._bump(stream=False, error=False)
        handler.send_response(200)
        handler.send_header("Content-Type", "application/json")
        handler.end_headers()
        handler.wfile.write(self.nonstream_body)

    # ------------------------------------------------------------- streams

    def _record_early_close(self, frame: int) -> None:
        if self.abort_log:
            try:
                with open(self.abort_log, "a", encoding="utf-8") as f:
                    f.write(
                        json.dumps(
                            {
                                "name": self.name,
                                "path": "/v1/chat/completions",
                                "early_close": True,
                                "closed_at_frame": frame,
                                "at": time.time(),
                            }
)
                        + "\n"
)
            except OSError:
                pass

    def _stream_plain(self, handler: BaseHTTPRequestHandler, pause: bool) -> None:
        self._bump(stream=True, error=False)
        handler.send_response(200)
        handler.send_header("Content-Type", "text/event-stream")
        handler.send_header("Cache-Control", "no-cache")
        handler.end_headers()
        if pause:
            self._stream_with_pause(handler)
            return
        for frame_index, payload in enumerate(self.stream_frames):
            try:
                handler.wfile.write(f"data: {payload}\n\n".encode("utf-8"))
                handler.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                self._record_early_close(frame_index)
                return
            if payload != DONE:
                time.sleep(self.frame_delay)

    def _stream_tool_calls(self, handler: BaseHTTPRequestHandler) -> None:
        """Tool-call turn as an SSE stream — the real OpenAI wire shape ( fix:
        role-only opener, then tool_calls delta fragments, finish_reason, [DONE])."""
        self._bump(stream=True, error=False)
        handler.send_response(200)
        handler.send_header("Content-Type", "text/event-stream")
        handler.send_header("Cache-Control", "no-cache")
        handler.end_headers()
        base = {
            "id": "chatcmpl-tools-1",
            "object": "chat.completion.chunk",
            "created": 1785715200,
            "model": "deepseek-v4-flash",
            "choices": [{"index": 0, "delta": {}, "finish_reason": None}],
        }
        frames = [
            {
                **base,
                "choices": [{"index": 0, "delta": {"role": "assistant"}, "finish_reason": None}],
            },
            {
                **base,
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "tool_calls": [
                                {
                                    "index": 0,
                                    "id": "call_1",
                                    "type": "function",
                                    "function": {"name": "get_weather", "arguments": ""},
                                }
                            ]
                        },
                        "finish_reason": None,
                    }
                ],
            },
            {
                **base,
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "tool_calls": [
                                {"index": 0, "function": {"arguments": '{"city": "Par'}}
                            ]
                        },
                        "finish_reason": None,
                    }
                ],
            },
            {
                **base,
                "choices": [
                    {
                        "index": 0,
                        "delta": {
                            "tool_calls": [
                                {"index": 0, "function": {"arguments": 'is"}'}}
                            ]
                        },
                        "finish_reason": None,
                    }
                ],
            },
            {
                **base,
                "choices": [{"index": 0, "delta": {}, "finish_reason": "tool_calls"}],
            },
        ]
        for frame_index, payload in enumerate(frames):
            try:
                handler.wfile.write(f"data: {json.dumps(payload)}\n\n".encode("utf-8"))
                handler.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                self._record_early_close(frame_index)
                return
            time.sleep(self.frame_delay)
        handler.wfile.write(f"data: {DONE}\n\n".encode("utf-8"))
        handler.wfile.flush()

    def _stream_with_pause(self, handler: BaseHTTPRequestHandler) -> None:
 # The pattern verbatim: one complete frame, then a partial frame with
        # no terminator — the resume-file close is an EOF-in-pending-frame →
        # deterministic SSE error frame, never a hang.
        handler.wfile.write(f"data: {self.stream_frames[0]}\n\n".encode("utf-8"))
        handler.wfile.flush()
        handler.wfile.write(f"data: {self.stream_frames[1]}\n".encode("utf-8"))
        handler.wfile.flush()
        if self.paused_file:
            Path(self.paused_file).touch()
        deadline = time.monotonic() + self.pause_hold
        while time.monotonic() < deadline:
            if self.resume_file and Path(self.resume_file).exists():
                break
            time.sleep(0.2)
        return


class Handler(BaseHTTPRequestHandler):
    fake: FakeUpstream = None  # set in main()

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
            # Client gone mid-stream (fallback: the stream loops record the frame
            # index themselves; this catches the non-stream paths and race cases).
            self.fake._record_early_close(-1)
            return

    def log_message(self, fmt, *args):  # concise logs to stderr
        sys.stderr.write("[fake-upstream] " + fmt % args + "\n")


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
    parser.add_argument("--port", type=int, default=9877)
    parser.add_argument("--name", default="fake")
    parser.add_argument(
        "--fixtures",
        type=Path,
        default=_default_fixtures(),
)
    parser.add_argument("--frame-delay", type=float, default=0.05)
    parser.add_argument("--mode-file", type=Path, default=None)
    parser.add_argument("--counter-file", type=Path, default=None)
    parser.add_argument("--paused-file", type=Path, default=None)
    parser.add_argument("--resume-file", type=Path, default=None)
    parser.add_argument("--pause-hold", type=float, default=120.0)
    parser.add_argument("--abort-log", type=Path, default=None)
    args = parser.parse_args()

    if not args.fixtures.is_dir():
        raise SystemExit(f"fixtures dir not found: {args.fixtures}")

    Handler.fake = FakeUpstream(
        name=args.name,
        fixtures=args.fixtures,
        frame_delay=args.frame_delay,
        mode_file=args.mode_file,
        counter_file=args.counter_file,
        paused_file=args.paused_file,
        resume_file=args.resume_file,
        pause_hold=args.pause_hold,
        abort_log=args.abort_log,
)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(
        f"fake-upstream({args.name}) listening on 127.0.0.1:{args.port} "
        f"(fixtures: {args.fixtures}; mode-file: {args.mode_file})",
        flush=True,
)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
