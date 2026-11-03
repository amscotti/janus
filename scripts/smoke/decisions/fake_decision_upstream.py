#!/usr/bin/env python3
"""fake_decision_upstream.py — deterministic System One / OpenAI-Decisions fake.

Serves canned decision answers on BOTH upstream dialects so the decisions
gate can prove, offline and byte-exactly, that Janus re-encodes the Jev
shape and translates the OpenAI Decisions shape:

  POST /v1/systemone   -> Jev-shaped answers (echoes the request's questions
                          with fixed probabilities; usage input_tokens=42)
  POST /v1/decisions   -> OpenAI-shaped answers[] (predicate/choice/score)
  GET  /v1/models      -> {} (never used by Janus; harmless)

Behavior by request model:
  - model == "flaky"  -> 503 plain body (failover leg: pair this fake with a
    second instance under a different entry so the alias walks to it)
  - anything else     -> 200 canned answers keyed by the request's questions

Every request is logged as one JSON line to --log-file:
  {"path": ..., "model": ..., "body": {...}}
so the gate can assert the EXACT body Janus sent upstream (the translation
evidence). Stdlib only; binds 127.0.0.1.
"""

from __future__ import annotations

import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

LOCK = threading.Lock()


def systemone_answers(questions: dict) -> dict:
    answers = {}
    for name, q in questions.items():
        qtype = q.get("type")
        if qtype == "noul":
            answers[name] = {"type": "noul", "noul": 0.97}
        elif qtype == "choice":
            options = list((q.get("criteria") or {}).keys())
            probs = {opt: (0.9 if i == 0 else 0.1 / max(len(options) - 1, 1)) for i, opt in enumerate(options)}
            answers[name] = {"type": "choice", "choice": options[0], "probabilities": probs, "confidence": 0.8}
        elif qtype == "score":
            levels = q.get("criteria") or []
            answers[name] = {
                "type": "score",
                "score": 1.0,
                "legend": {str(i): level for i, level in enumerate(levels)},
                "probabilities": {str(i): (1.0 if i == 1 else 0.0) for i in range(len(levels))},
                "confidence": 0.5,
            }
        elif qtype == "box":
            answers[name] = {
                "type": "box",
                "box": {"top": 0.1, "bottom": 0.2, "left": 0.3, "right": 0.4},
                "confidence": 0.7,
            }
    return answers


def openai_answers(questions: list) -> list:
    answers = []
    for q in questions:
        qtype = q.get("type")
        name = q.get("name")
        if qtype == "predicate":
            answers.append({"type": "predicate", "name": name, "probability": 0.91})
        elif qtype == "choice":
            choices = q.get("choices") or []
            answers.append({
                "type": "choice",
                "name": name,
                "choice": choices[0]["value"],
                "confidence": 0.7,
                "probabilities": [
                    {"value": c["value"], "probability": 0.85 if i == 0 else 0.15}
                    for i, c in enumerate(choices)
                ],
            })
        elif qtype == "score":
            levels = q.get("levels") or []
            answers.append({
                "type": "score",
                "name": name,
                "score": 1.0,
                "confidence": 0.4,
                "probabilities": [
                    {"label": level["label"], "value": i, "probability": 1.0 if i == 1 else 0.0}
                    for i, level in enumerate(levels)
                ],
            })
    return answers


class Handler(BaseHTTPRequestHandler):
    log_path: Path = Path("/dev/null")

    def log_message(self, fmt, *args):  # keep the gate log clean
        pass

    def _send(self, status: int, body: bytes, content_type: str = "application/json") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):  # noqa: N802
        self._send(200, b"{}")

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            body = {}
        model = body.get("model")
        danger_headers = {
            key: value
            for key, value in self.headers.items()
            if key.lower().startswith("typesafe-danger-zone-")
        }
        with LOCK:
            with self.log_path.open("a", encoding="utf-8") as fh:
                fh.write(json.dumps({
                    "path": self.path,
                    "model": model,
                    "body": body,
                    "headers": danger_headers,
                }) + "\n")
        if model == "flaky":
            self._send(503, b"flaky upstream is down", "text/plain")
            return
        if self.path.endswith("/v1/systemone"):
            payload = {
                "model": model,
                "answers": systemone_answers(body.get("questions") or {}),
                "usage": {"input_tokens": 42, "output_tokens": 0},
                "prompt_eval_cached_count": 7,
            }
            self._send(200, json.dumps(payload).encode("utf-8"))
        elif self.path.endswith("/v1/decisions"):
            payload = {
                "answers": openai_answers(body.get("questions") or []),
                "model": model,
                "usage": {
                    "input_tokens": 30,
                    "input_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 0},
                    "output_tokens": 3,
                    "total_tokens": 33,
                },
            }
            self._send(200, json.dumps(payload).encode("utf-8"))
        else:
            self._send(404, b'{"error":"not found"}')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--log-file", required=True)
    args = parser.parse_args()
    Handler.log_path = Path(args.log_file)
    Handler.log_path.parent.mkdir(parents=True, exist_ok=True)
    Handler.log_path.write_text("", encoding="utf-8")
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"fake decision upstream on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
