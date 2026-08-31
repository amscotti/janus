#!/usr/bin/env python3
"""fake_anthropic.py — stdlib-only fake Anthropic upstream for the stage 2 e2e gate ().

Serves ``POST /v1/messages`` with scripted bodies derived from the  golden
fixtures (``janus-core/src/test/resources/fixtures/anthropic/``) and the  matrix
tools legs (``matrix/oa/tools/upstream.response.json``), so the deterministic gate
legs run fully offline and the committed corpus stays the single source of wire
truth. No ``anthropic`` package, no third-party imports.

Modes — selected by a marker in the request body (the smoke client puts it in the
user message content; Janus passes the body through unchanged, so the marker reaches
the fake verbatim):

  mode=nonstream   (default) 200 JSON from ``chat.response.json``
  mode=stream                SSE stream replayed from ``chat.stream.sse`` at a cadence
                             (event-named frames, NO ``[DONE]`` —  discipline;
                             terminates on ``message_stop``)
  mode=tools                 two-turn tool conversation: turn 1 serves the committed
                             tools response (tool_use block, ``stop_reason: tool_use``);
                             turn 2 (a ``tool_result`` block is present in the request)
                             serves the plain final-answer response. ``mode=tools&stream=1``
                             serves the tool-call as an SSE stream instead.
  mode=401 / 400 / 429 / 529 non-2xx error bodies (``anthropic.401.json`` /
                             ``anthropic.429.json`` committed; 400 + 529 synthesized
                             from the documented Anthropic envelope shapes)

**Strict mode (always on):** unknown top-level request fields are rejected with a
400 ``invalid_request_error`` — mirroring real Anthropic's schema validation, so a
leaked OpenAI-idiomatic field (e.g. ``stream_options``) surfaces deterministically
instead of as a flaky live-API surprise. The rejection is an *exercised* assertion:
the runner's strict-fake tripwire raw-POSTs a leaked ``stream_options`` and asserts
the 400 (D1 red surface), and the live ``oa``/``aa`` stream legs are the green side
(Janus strips the field before it reaches the fake).

Drill hooks (kill-upstream drills, deterministic — the  pause pattern):
  pause=1  (with mode=stream) — after the first complete data frame AND a partial
           frame are flushed, write ``--paused-file`` and hold (polling
           ``--resume-file``) until the killer fires; the close lands mid-frame.

Abort-drill recording: every request whose connection closes before the stream
finished is recorded to ``--abort-log`` (one JSON line per observed early close) —
the abort_drill asserts the upstream observed the client disconnect.

Usage:
  python3 fake_anthropic.py [--port 9878] [--fixtures DIR] [--frame-delay 0.05]
                            [--paused-file PATH] [--resume-file PATH] [--pause-hold 120]
                            [--abort-log PATH]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MODE_RE = re.compile(r"mode=(nonstream|stream|tools|401|400|429|529)")
KNOWN_FIELDS = {
    "model",
    "messages",
    "system",
    "max_tokens",
    "temperature",
    "top_p",
    "top_k",
    "stop_sequences",
    "stream",
    "tools",
    "tool_choice",
    "thinking",
    "cache_control",
    "metadata",
    "service_tier",
}
# Fields real Anthropic rejects (schema violations the strict mode must surface).
# stream_options is the  D1 hand-off: OpenAI-idiomatic, no Anthropic wire home.
KNOWN_FIELD_ERRORS = {
    "stream_options": "Extra inputs are not permitted",
    "frequency_penalty": "Extra inputs are not permitted",
    "presence_penalty": "Extra inputs are not permitted",
    "logit_bias": "Extra inputs are not permitted",
    "seed": "Extra inputs are not permitted",
    "n": "Extra inputs are not permitted",
    "response_format": "Extra inputs are not permitted",
    "user": "Extra inputs are not permitted",
}


class FakeAnthropic:
    """Stateless enough for one process; the HTTP handler calls straight in."""

    def __init__(self, fixtures: Path, frame_delay: float, paused_file, resume_file, pause_hold, abort_log):
        self.fixtures = fixtures
        self.frame_delay = frame_delay
        self.paused_file = paused_file
        self.resume_file = resume_file
        self.pause_hold = pause_hold
        self.abort_log = abort_log
        self.stream_frames = self._load_stream_frames()
        self.nonstream_body = (fixtures / "chat.response.json").read_bytes()
        # The tools two-turn body lives in the matrix corpus (fixtures ROOT, one level
        # above the anthropic/ corpus dir the --fixtures flag points at).
        matrix_root = fixtures.parent
        self.tools_body = (matrix_root / "matrix" / "oa" / "tools" / "upstream.response.json").read_bytes()
        self.errors = {
            "401": (fixtures / "errors" / "anthropic.401.json").read_bytes(),
            "429": (fixtures / "errors" / "anthropic.429.json").read_bytes(),
            # Synthesized from the documented Anthropic envelope shape (the README's
            # reference-level equality; classification lives in the provider loopback
            # tests — the fake's bodies are reference-only for the SDK exception path).
            "400": json.dumps(
                {
                    "type": "error",
                    "error": {
                        "type": "invalid_request_error",
                        "message": "invalid request: unknown field",
                    },
                }
            ).encode("utf-8"),
            "529": json.dumps(
                {
                    "type": "error",
                    "error": {
                        "type": "overloaded_error",
                        "message": "Overloaded",
                    },
                }
            ).encode("utf-8"),
        }

    # ------------------------------------------------------------- fixtures

    def _load_stream_frames(self) -> list[tuple[str, str]]:
        """Parse the committed event-named SSE into (event, data) frames."""
        text = (self.fixtures / "chat.stream.sse").read_text(encoding="utf-8")
        frames: list[tuple[str, str]] = []
        event = "message"  # SSE default event name
        for line in text.splitlines():
            line = line.strip()
            if line.startswith("event:"):
                event = line[len("event:") :].strip()
            elif line.startswith("data:"):
                frames.append((event, line[len("data:") :].strip()))
        if not frames:
            raise SystemExit("chat.stream.sse must contain data frames")
        if any(payload == "[DONE]" for _, payload in frames):
            raise SystemExit("chat.stream.sse must NOT contain [DONE] ( discipline)")
        return frames

    # ------------------------------------------------------------- serving

    def handle_messages(self, handler: BaseHTTPRequestHandler, raw_body: bytes) -> None:
        text = raw_body.decode("utf-8", "replace")
        try:
            request = json.loads(text)
        except json.JSONDecodeError:
            handler.send_response(400)
            handler.send_header("Content-Type", "application/json")
            handler.end_headers()
            handler.wfile.write(self.errors["400"])
            return

        # Strict mode is ALWAYS on (the  D1 red surface — see the module
        # docstring): unknown top-level fields are rejected with a 400
        # invalid_request_error, mirroring real Anthropic's schema validation.
        unknown = sorted(set(request) - KNOWN_FIELDS)
        if unknown:
            field = unknown[0]
            detail = KNOWN_FIELD_ERRORS.get(field, "Extra inputs are not permitted")
            body = json.dumps(
                {
                    "type": "error",
                    "error": {
                        "type": "invalid_request_error",
                        "message": detail,
                        "param": field,
                    },
                }
            ).encode("utf-8")
            handler.send_response(400)
            handler.send_header("Content-Type", "application/json")
            handler.end_headers()
            handler.wfile.write(body)
            return

        match = MODE_RE.search(text)
        mode = match.group(1) if match else "nonstream"
        pause = "pause=1" in text
        has_tool_result = self._has_tool_result(request)

        if mode in ("401", "400", "429", "529"):
            handler.send_response(int(mode))
            handler.send_header("Content-Type", "application/json")
            handler.end_headers()
            handler.wfile.write(self.errors[mode])
            return

        if mode == "tools":
            if has_tool_result:
                # Turn 2: the tool result arrived — serve the plain final answer.
                if request.get("stream"):
                    self._stream_plain(handler, pause)
                else:
                    handler.send_response(200)
                    handler.send_header("Content-Type", "application/json")
                    handler.end_headers()
                    handler.wfile.write(self.nonstream_body)
            else:
                # Turn 1: serve the committed tool_use response (or its stream form).
                if request.get("stream"):
                    self._stream_tool_use(handler)
                else:
                    handler.send_response(200)
                    handler.send_header("Content-Type", "application/json")
                    handler.end_headers()
                    handler.wfile.write(self.tools_body)
            return

        if mode == "stream":
            self._stream_plain(handler, pause)
            return

        # mode=nonstream (default)
        handler.send_response(200)
        handler.send_header("Content-Type", "application/json")
        handler.end_headers()
        handler.wfile.write(self.nonstream_body)

    @staticmethod
    def _has_tool_result(request: dict) -> bool:
        for message in request.get("messages") or []:
            content = message.get("content")
            if isinstance(content, list):
                for block in content:
                    if isinstance(block, dict) and block.get("type") == "tool_result":
                        return True
            elif isinstance(content, str) and "tool_result" in content:
                return True
        return False

    # ------------------------------------------------------------- streams

    def _record_early_close(self, frame: int) -> None:
        """The abort drill's cancellation proof: the upstream observed the client
        disconnect before the stream completed (mid-stream, at ``frame``)."""
        if self.abort_log:
            try:
                with open(self.abort_log, "a", encoding="utf-8") as f:
                    f.write(
                        json.dumps(
                            {
                                "path": "/v1/messages",
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
        handler.send_response(200)
        handler.send_header("Content-Type", "text/event-stream")
        handler.send_header("Cache-Control", "no-cache")
        handler.end_headers()
        if pause:
            self._stream_with_pause(handler)
            return
        for frame_index, (event, payload) in enumerate(self.stream_frames):
            try:
                handler.wfile.write(f"event: {event}\ndata: {payload}\n\n".encode("utf-8"))
                handler.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                self._record_early_close(frame_index)
                return
            if event != "message_stop":
                time.sleep(self.frame_delay)

    def _stream_tool_use(self, handler: BaseHTTPRequestHandler) -> None:
        """Tool-call turn as an SSE stream: tool_use content_block + input_json_delta
        fragments (partial JSON arguments — the delta-shape corner case the  matrix
        documents), then stop_reason tool_use + message_stop (no [DONE])."""
        handler.send_response(200)
        handler.send_header("Content-Type", "text/event-stream")
        handler.send_header("Cache-Control", "no-cache")
        handler.end_headers()
        events = [
            (
                "message_start",
                {"type": "message_start", "message": {
                    "id": "msg_tool_stream_1",
                    "type": "message",
                    "role": "assistant",
                    "model": "deepseek-v4-flash",
                    "content": [],
                    "usage": {"input_tokens": 10, "output_tokens": 0},
                }},
            ),
            (
                "content_block_start",
                {"type": "content_block_start", "index": 0, "content_block": {
                    "type": "tool_use", "id": "call_1", "name": "get_weather", "input": {},
                }},
            ),
            (
                "content_block_delta",
                {"type": "content_block_delta", "index": 0, "delta": {
                    "type": "input_json_delta", "partial_json": '{"city": "Par',
                }},
            ),
            (
                "content_block_delta",
                {"type": "content_block_delta", "index": 0, "delta": {
                    "type": "input_json_delta", "partial_json": 'is"}',
                }},
            ),
            ("content_block_stop", {"type": "content_block_stop", "index": 0}),
            (
                "message_delta",
                {"type": "message_delta", "delta": {"stop_reason": "tool_use"},
                 "usage": {"input_tokens": 10, "output_tokens": 5}},
            ),
            ("message_stop", {"type": "message_stop"}),
        ]
        for frame_index, (event, payload) in enumerate(events):
            try:
                handler.wfile.write(f"event: {event}\ndata: {json.dumps(payload)}\n\n".encode("utf-8"))
                handler.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                self._record_early_close(frame_index)
                return
            time.sleep(self.frame_delay)

    def _stream_with_pause(self, handler: BaseHTTPRequestHandler) -> None:
        # The  pause pattern, ported to event-named frames: one complete frame, then
        # a partial frame with no terminator — the resume-file close is an
        # EOF-in-pending-frame → deterministic SSE error frame, never a hang.
        event, payload = self.stream_frames[0]
        handler.wfile.write(f"event: {event}\ndata: {payload}\n\n".encode("utf-8"))
        handler.wfile.flush()
        handler.wfile.write(f"data: {self.stream_frames[1][1]}\n".encode("utf-8"))
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
    fake: FakeAnthropic = None  # set in main()

    # pylint: disable=invalid-name
    def do_GET(self) -> None:  # readiness probe (runner polls this)
        body = b"ok"
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:
        if self.path != "/v1/messages":
            self.send_error(404)
            return
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length) if length else b""
        try:
            self.fake.handle_messages(self, raw)
        except (BrokenPipeError, ConnectionResetError):
            # Client gone mid-stream (fallback: the stream loops record the frame
            # index themselves; this catches the non-stream paths and race cases).
            self.fake._record_early_close(-1)
            return

    def log_message(self, fmt, *args):  # concise logs to stderr
        sys.stderr.write("[fake-anthropic] " + fmt % args + "\n")


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
            return p / "janus-core/src/test/resources/fixtures"
    return here.parent.parent.parent.parent / "janus-core/src/test/resources/fixtures"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=9878)
    parser.add_argument(
        "--fixtures",
        type=Path,
        default=_default_fixtures(),
    )
    parser.add_argument("--frame-delay", type=float, default=0.05)
    parser.add_argument("--paused-file", type=Path, default=None)
    parser.add_argument("--resume-file", type=Path, default=None)
    parser.add_argument("--pause-hold", type=float, default=120.0)
    parser.add_argument("--abort-log", type=Path, default=None)
    args = parser.parse_args()

    anthropic_dir = args.fixtures / "anthropic"
    if not anthropic_dir.is_dir():
        raise SystemExit(f"fixtures dir not found: {anthropic_dir}")

    Handler.fake = FakeAnthropic(
        fixtures=anthropic_dir,
        frame_delay=args.frame_delay,
        paused_file=args.paused_file,
        resume_file=args.resume_file,
        pause_hold=args.pause_hold,
        abort_log=args.abort_log,
    )
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"fake-anthropic listening on 127.0.0.1:{args.port} (fixtures: {anthropic_dir})", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
