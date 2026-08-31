#!/usr/bin/env python3
"""Live-API cluster drill through HAProxy.

One short request per face/provider so spend stays small. Requires a
cluster booted with config.cluster.live.toml and real provider keys.

Exit 0 iff every assertion passes.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "store"))
from harness_common import generate_key, http_json, http_text  # noqa: E402

MODELS = {
    "flash": "deepseek-v4-flash",
    "sonnet": "claude-sonnet-5",
    "luna": "gpt-5.6-luna",
    "pro": "deepseek-v4-pro",
    "vision": "deepseek-v4-flash-vision-exp",
    "grok": "grok-4.6",
    "kimi": "moonshotai/kimi-k3",
    "minimax": "minimax/minimax-m3",
    "muse": "muse-spark-1.2",
    "glm": "accounts/fireworks/models/glm-5p3",
    "gptoss": "openai/gpt-oss-120b",
    "sonar": "sonar",
    "gemini": "gemini-3.7-flash",
    "glm53": "z-ai/glm-5.3",
}

# 1×1 red PNG — self-contained; no external image host.
TINY_PNG = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)

PONG = "Reply with the single word pong."


def log(msg: str) -> None:
    print(msg, flush=True)


def stream_post(url: str, body: dict, key: str, timeout: float = 90.0) -> tuple[int, str, str]:
    """POST a streaming request through the LB. Returns (status, content-type, body)."""
    request = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        method="POST",
        headers={
            "x-api-key": key,
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        },
)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        raw = response.read().decode("utf-8", "replace")
        content_type = response.headers.get("Content-Type") or ""
        return response.status, content_type, raw


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lb", required=True)
    parser.add_argument("--master-key", required=True)
    parser.add_argument(
        "--models",
        default="flash,sonnet",
        help="comma-separated keys from flash|sonnet|luna|pro|vision|grok|kimi|minimax|muse|glm|gptoss|sonar|gemini|glm53",
)
    args = parser.parse_args()
    lb = args.lb.rstrip("/")
    wanted = [m.strip() for m in args.models.split(",") if m.strip()]
    aliases = [MODELS[k] for k in wanted]

    status, _, _ = http_text(f"{lb}/health/readiness", timeout=5.0)
    assert status == 200, f"LB /health/readiness → {status}"

    key_id, key = generate_key(
        f"{lb}/v1",
        args.master_key,
        models=aliases,
        name="cluster-live",
        budget_usd=10.0,
        rpm=60,
)
    log(f"minted live key {key_id} scoped to {aliases}")

    if "flash" in wanted:
        status, _, payload = http_json(
            "POST",
            f"{lb}/v1/chat/completions",
            body={
                "model": MODELS["flash"],
                "max_tokens": 16,
                "thinking": {"type": "disabled"},
                "messages": [{"role": "user", "content": "Reply with the single word pong."}],
            },
            headers={"x-api-key": key},
            timeout=90.0,
)
        assert status == 200, f"OpenAI face → flash: {status} ({payload})"
        text = payload["choices"][0]["message"]["content"]
        assert text and text.strip(), "flash reply empty"
        log(f"PASS OpenAI face → deepseek-v4-flash ({text!r:.80})")

        status, ctype, raw = stream_post(
            f"{lb}/v1/chat/completions",
            {
                "model": MODELS["flash"],
                "stream": True,
                "stream_options": {"include_usage": True},
                "max_tokens": 16,
                "thinking": {"type": "disabled"},
                "messages": [{"role": "user", "content": "Reply with the single word pong."}],
            },
            key,
)
        assert status == 200, f"OpenAI stream → flash: {status}"
        assert "text/event-stream" in ctype, f"OpenAI stream content-type={ctype!r}"
        assert "data:" in raw, f"OpenAI stream missing data: frames: {raw[:240]!r}"
        assert "[DONE]" in raw, f"OpenAI stream missing [DONE]: {raw[-200:]!r}"
        log("PASS OpenAI face STREAM → deepseek-v4-flash (SSE + [DONE] through HAProxy)")

    if "sonnet" in wanted:
        status, _, payload = http_json(
            "POST",
            f"{lb}/v1/messages",
            body={
                "model": MODELS["sonnet"],
                "max_tokens": 16,
                "messages": [{"role": "user", "content": "Reply with the single word pong."}],
            },
            headers={"x-api-key": key},
            timeout=90.0,
)
        assert status == 200, f"Anthropic face → sonnet: {status} ({payload})"
        blocks = payload.get("content") or []
        text = "".join(b.get("text", "") for b in blocks if b.get("type") == "text")
        assert text.strip(), f"sonnet reply empty: {payload}"
        log(f"PASS Anthropic face → claude-sonnet-5 ({text!r:.80})")

        # Cross-format: OpenAI SDK shape → Anthropic upstream
        status, _, payload = http_json(
            "POST",
            f"{lb}/v1/chat/completions",
            body={
                "model": MODELS["sonnet"],
                "max_tokens": 16,
                "messages": [{"role": "user", "content": "Reply with the single word pong."}],
            },
            headers={"x-api-key": key},
            timeout=90.0,
)
        assert status == 200, f"cross-format OpenAI→sonnet: {status} ({payload})"
        log("PASS cross-format OpenAI face → claude-sonnet-5")

        status, ctype, raw = stream_post(
            f"{lb}/v1/messages",
            {
                "model": MODELS["sonnet"],
                "stream": True,
                "max_tokens": 16,
                "messages": [{"role": "user", "content": "Reply with the single word pong."}],
            },
            key,
)
        assert status == 200, f"Anthropic stream → sonnet: {status}"
        assert "text/event-stream" in ctype, f"Anthropic stream content-type={ctype!r}"
        assert "event:" in raw or "data:" in raw, f"Anthropic stream empty: {raw[:240]!r}"
        assert "message_stop" in raw, f"Anthropic stream missing message_stop: {raw[-200:]!r}"
        log("PASS Anthropic face STREAM → claude-sonnet-5 (SSE through HAProxy)")

        status, ctype, raw = stream_post(
            f"{lb}/v1/chat/completions",
            {
                "model": MODELS["sonnet"],
                "stream": True,
                "stream_options": {"include_usage": True},
                "max_tokens": 16,
                "messages": [{"role": "user", "content": "Reply with the single word pong."}],
            },
            key,
)
        assert status == 200, f"cross-format stream OpenAI→sonnet: {status}"
        assert "data:" in raw and "[DONE]" in raw, f"cross-format stream malformed: {raw[:240]!r}"
        log("PASS cross-format STREAM OpenAI face → claude-sonnet-5")

    if "luna" in wanted:
        status, _, payload = http_json(
            "POST",
            f"{lb}/v1/responses",
            body={
                "model": MODELS["luna"],
                "store": False,
                "max_output_tokens": 16,
                "input": "Reply with the single word pong.",
            },
            headers={"x-api-key": key},
            timeout=90.0,
)
        assert status == 200, f"Responses face → luna: {status} ({payload})"
        assert payload.get("object") == "response", payload
        log("PASS Responses face → gpt-5.6-luna")

        status, ctype, raw = stream_post(
            f"{lb}/v1/responses",
            {
                "model": MODELS["luna"],
                "stream": True,
                "store": False,
                "max_output_tokens": 16,
                "input": "Reply with the single word pong.",
            },
            key,
)
        assert status == 200, f"Responses stream → luna: {status}"
        assert "event:" in raw or "data:" in raw, f"Responses stream empty: {raw[:240]!r}"
        assert "response.completed" in raw or "response.created" in raw, (
            f"Responses stream missing events: {raw[:240]!r}"
)
        log("PASS Responses face STREAM → gpt-5.6-luna through HAProxy")

    if "pro" in wanted:
        status, _, payload = http_json(
            "POST",
            f"{lb}/v1/messages",
            body={
                "model": MODELS["pro"],
                "max_tokens": 16,
                "thinking": {"type": "disabled"},
                "messages": [{"role": "user", "content": PONG}],
            },
            headers={"x-api-key": key},
            timeout=90.0,
)
        assert status == 200, f"Anthropic face → DeepSeek anthropic-endpoint pro: {status} ({payload})"
        log("PASS Anthropic face → deepseek-v4-pro (api.deepseek.com/anthropic through HAProxy)")

        status, _, payload = http_json(
            "POST",
            f"{lb}/v1/chat/completions",
            body={
                "model": MODELS["pro"],
                "max_tokens": 16,
                "thinking": {"type": "disabled"},
                "messages": [{"role": "user", "content": PONG}],
            },
            headers={"x-api-key": key},
            timeout=90.0,
)
        assert status == 200, f"cross-format OpenAI→pro anthropic adapter: {status} ({payload})"
        log("PASS cross-format OpenAI face → deepseek-v4-pro (Anthropic adapter)")

    if "vision" in wanted:
        status, _, payload = http_json(
            "POST",
            f"{lb}/v1/chat/completions",
            body={
                "model": MODELS["vision"],
                "thinking": {"type": "disabled"},
                "max_tokens": 32,
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "Describe this image in five words or fewer."},
                            {
                                "type": "image_url",
                                "image_url": {"url": f"data:image/png;base64,{TINY_PNG}"},
                            },
                        ],
                    }
                ],
            },
            headers={"x-api-key": key},
            timeout=90.0,
)
        text = (((payload or {}).get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        assert status == 200 and text.strip(), f"vision openai-face: {status} ({payload})"
        log("PASS OpenAI face vision → deepseek-v4-flash-vision-exp")

        status, _, payload = http_json(
            "POST",
            f"{lb}/v1/messages",
            body={
                "model": MODELS["vision"],
                "max_tokens": 32,
                "thinking": {"type": "disabled"},
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": "Describe this image in five words or fewer."},
                            {
                                "type": "image",
                                "source": {
                                    "type": "base64",
                                    "media_type": "image/png",
                                    "data": TINY_PNG,
                                },
                            },
                        ],
                    }
                ],
            },
            headers={"x-api-key": key},
            timeout=90.0,
)
        blocks = (payload or {}).get("content") or []
        text = "".join(b.get("text", "") for b in blocks if b.get("type") == "text")
        assert status == 200 and text.strip(), f"vision anthropic-face: {status} ({payload})"
        log("PASS Anthropic face vision → deepseek-v4-flash-vision-exp")

    def openai_chat(alias: str, extra: dict | None, max_tokens: int, label: str) -> None:
        body = {
            "model": alias,
            "max_tokens": max_tokens,
            "messages": [{"role": "user", "content": PONG}],
        }
        if extra:
            body.update(extra)
        status, _, payload = http_json(
            "POST",
            f"{lb}/v1/chat/completions",
            body=body,
            headers={"x-api-key": key},
            timeout=90.0,
)
        text = (((payload or {}).get("choices") or [{}])[0].get("message") or {}).get("content") or ""
        assert status == 200 and text.strip(), f"{label}: {status} ({payload})"
        log(f"PASS OpenAI face → {alias}")

        stream_body = dict(body)
        stream_body["stream"] = True
        stream_body["stream_options"] = {"include_usage": True}
        status, ctype, raw = stream_post(f"{lb}/v1/chat/completions", stream_body, key)
        assert status == 200, f"{label} stream: {status}"
        assert "data:" in raw and "[DONE]" in raw, f"{label} stream malformed: {raw[:240]!r}"
        log(f"PASS OpenAI face STREAM → {alias}")

    if "grok" in wanted:
        openai_chat(MODELS["grok"], {"reasoning_effort": "low"}, 256, "grok")
    if "kimi" in wanted:
        openai_chat(MODELS["kimi"], {"reasoning_effort": "none"}, 512, "kimi")
    if "minimax" in wanted:
        openai_chat(MODELS["minimax"], None, 256, "minimax")
    if "muse" in wanted:
        openai_chat(MODELS["muse"], {"reasoning_effort": "low"}, 1024, "muse")
    if "glm" in wanted:
        openai_chat(MODELS["glm"], None, 512, "fireworks glm-5p3")
    if "gptoss" in wanted:
        # gpt-oss reasons; headroom per the suite's reasoning-model precedent.
        openai_chat(MODELS["gptoss"], None, 512, "groq gpt-oss-120b")
    if "sonar" in wanted:
        # sonar is search-grounded; non-empty grounded answer satisfies the pin.
        openai_chat(MODELS["sonar"], None, 512, "perplexity sonar")
    if "gemini" in wanted:
        openai_chat(MODELS["gemini"], None, 512, "gemini-3.7-flash (direct OpenAI-compat)")
    if "glm53" in wanted:
        # GLM 5.3 reasoning is mandatory upstream (reasoning_effort=none → 400).
        openai_chat(MODELS["glm53"], None, 512, "z-ai/glm-5.3 (OpenRouter)")

    # Scope denial
    status, _, payload = http_json(
        "POST",
        f"{lb}/v1/chat/completions",
        body={
            "model": "definitely-not-configured-xyz",
            "messages": [{"role": "user", "content": "no"}],
        },
        headers={"x-api-key": key},
)
    assert status in (403, 404), f"unknown model → {status} ({payload})"
    log(f"PASS unknown model denied ({status})")

    log("ALL live cluster drills passed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        raise SystemExit(1)
