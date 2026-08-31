#!/usr/bin/env bash
# =============================================================================
# native_codec_smoke.sh — C17: behaviorally exercise the JSON codec path in the
# native binary (the CI native gate previously only curled /health, so a regression
# that breaks decode-request / encode-response would ship with green CI).
#
# Offline, Docker-free: boots the committed golden fake upstream
# (scripts/bench/fake_upstream.py — the committed golden corpus, pinned usage 14/12/26)
# and the native binary pointed at it via a minimal config (auth off) delivered over
# MICRONAUT_CONFIG_FILES — NEVER --config (the image's mainClass is JanusApplication,
# which does not map --config to micronaut.config.files; the env-var channel is the
# contract), then drives:
# POST /v1/chat/completions non-stream → 200 + golden content + usage
# POST /v1/chat/completions (stream) SSE → data: [DONE] + terminal usage frame
# POST /v1/messages Anthropic face non-stream (cross-format to
# the same OpenAI-compatible fake) — exercises the
# Anthropic request/response codecs in native
# POST /v1/messages (stream) Anthropic face SSE → message_stop
# The gateway must DECODE each inbound request to route it and ENCODE the upstream
# body to the client, so a 200 with the golden content proves decode+encode ran in
# the binary (not just /health). No real provider keys, no network.
#
# Usage:
# scripts/bench/native_codec_smoke.sh [BIN]
# BIN defaults to janus-gateway/build/native/nativeCompile/janus
#
# Env:
# FAKE_PORT / PORT free-port substitution (defaults: free)
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"
BENCH_DIR="$REPO/scripts/bench"
RUN_DIR="$BENCH_DIR/.run"
BIN="${1:-$REPO/janus-gateway/build/native/nativeCompile/janus}"
PY_BIN="python3"

log() { printf '[native-smoke] %s\n' "$*" >&2; }
die() { printf '[native-smoke] FATAL: %s\n' "$*" >&2; exit 1; }

[[ -x "$BIN" ]] || die "native binary missing: $BIN (build it with :janus-gateway:nativeCompile)"

free_port() {
  "$PY_BIN" - <<'EOF'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
EOF
}

# free_port closes its probe socket before the consumer binds, so the port can
# be reclaimed in between (TOCTOU). Instead of failing with a misleading "did not
# come up" error, retry each boot on a fresh port a few times.
BOOT_ATTEMPTS=3

# The documented FAKE_PORT / PORT env pins (header: "free-port substitution") are
# captured up front because start_fake/start_janus reassign the variables: each call
# resolves to the pin when set, a FRESH free port when not (an unpinned TOCTOU retry
# must never reuse the port that just failed).
FAKE_PORT_PINNED="${FAKE_PORT:-}"
PORT_PINNED="${PORT:-}"

PIDS=()
cleanup() {
  # Kill ONLY the processes this run spawned and tracks: PIDS holds every boot
  # attempt's fake/binary PID, so a retried boot's earlier fake is covered too.
  # A pattern pkill -9 -f "$BENCH_DIR/fake_upstream.py" would kill the fake upstream
  # of a concurrently running run_bench.sh / run_native_regression.sh (same script
  # path) — the tracked-PID-only policy those scripts document.
  for pid in ${PIDS[@]+"${PIDS[@]}"}; do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$RUN_DIR"

# ---------------------------------------------------------------- fake upstream
start_fake() {
  FAKE_PORT="${FAKE_PORT_PINNED:-$(free_port)}"
  nohup "$PY_BIN" "$BENCH_DIR/fake_upstream.py" --port "$FAKE_PORT" --name native-smoke \
    > "$RUN_DIR/native-smoke.fake.log" 2>&1 &
  PIDS+=("$!")
  local i
  for i in $(seq 1 50); do
    curl -sf "http://127.0.0.1:$FAKE_PORT/" >/dev/null 2>&1 && return 0
    sleep 0.1
  done
  return 1
}

if [ -n "$FAKE_PORT_PINNED" ]; then
  log "booting golden fake upstream on fixed :$FAKE_PORT"
  start_fake || die "fake upstream did not come up (see $RUN_DIR/native-smoke.fake.log)"
else
  FAKE_OK=0
  for attempt in $(seq 1 "$BOOT_ATTEMPTS"); do
    if start_fake; then FAKE_OK=1; break; fi
    log "fake upstream did not come up on :$FAKE_PORT (attempt $attempt/$BOOT_ATTEMPTS) — retrying on a fresh port"
  done
  [ "$FAKE_OK" = 1 ] || die "fake upstream did not come up (see $RUN_DIR/native-smoke.fake.log)"
fi
log "golden fake upstream up on :$FAKE_PORT"

# ---------------------------------------------------------------- config (auth off)
# Delivered via MICRONAUT_CONFIG_FILES (the env-var channel — never --config, which the
# native image's mainClass JanusApplication ignores; see the header comment).
# Regenerated per boot attempt (the port may change on a TOCTOU retry).
write_config() {
  cat > "$CFG" <<EOF
[micronaut.application]
name = "janus"

[micronaut.server]
port = $PORT

[endpoints.all]
enabled = true
sensitive = true

[endpoints.stop]
enabled = false

[endpoints.refresh]
enabled = false

[endpoints.health]
enabled = true
details-visible = "ANONYMOUS"
sensitive = false

[janus]
name = "janus"
version = "1.0.0"

[janus.keys]
auth = "off"

[[janus.model-list]]
name = "deepseek-v4-flash"
provider = "openai-compatible"
base-url = "http://127.0.0.1:$FAKE_PORT"
EOF
}
CFG="$RUN_DIR/config.native-codec-smoke.toml"

# ---------------------------------------------------------------- boot the binary
start_janus() {
  PORT="${PORT_PINNED:-$(free_port)}"
  write_config
  log "booting native binary on :$PORT ($BIN)"
  MICRONAUT_CONFIG_FILES="$CFG" nohup "$BIN" > "$RUN_DIR/native-smoke.janus.log" 2>&1 &
  PIDS+=("$!")
  local i
  for i in $(seq 1 300); do
    curl -sf "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && return 0
    sleep 0.1
  done
  return 1
}

if [ -n "$PORT_PINNED" ]; then
  start_janus || die "native binary did not reach /health on fixed :$PORT (see $RUN_DIR/native-smoke.janus.log)"
else
  JANUS_OK=0
  for attempt in $(seq 1 "$BOOT_ATTEMPTS"); do
    if start_janus; then JANUS_OK=1; break; fi
    log "native binary did not reach /health on :$PORT (attempt $attempt/$BOOT_ATTEMPTS) — retrying on a fresh port"
  done
  [ "$JANUS_OK" = 1 ] || die "native binary did not reach /health (see $RUN_DIR/native-smoke.janus.log)"
fi

# Boot-misconfig tripwire: /v1/models must list deepseek-v4-flash EXACTLY ONCE — a config
# that fails to apply (e.g. a wrong config channel) would boot the packaged empty
# model list and fail here instead of silently passing a wrong-config boot.
wait_models_once() {
  local port="$1" bound="${2:-30}" i
  for i in $(seq 1 "$((bound * 10))"); do
    local models count
    models="$(curl -sf "http://127.0.0.1:$port/v1/models" 2>/dev/null || true)"
    count="$(printf '%s' "$models" | grep -o '"deepseek-v4-flash"' | wc -l | tr -d ' ')"
    if [[ "$count" == "1" ]]; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}
wait_models_once "$PORT" || die "boot-misconfig tripwire: /v1/models must list deepseek-v4-flash exactly once (config did not apply)"

# ---------------------------------------------------------------- non-stream chat
log "POST /v1/chat/completions (non-stream)"
NONSTREAM="$RUN_DIR/native-smoke.nonstream.json"
curl -s "http://127.0.0.1:$PORT/v1/chat/completions" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"What is the weather in Paris?"}]}' \
  > "$NONSTREAM"
grep -q "The weather in Paris is 18 degrees with light rain." "$NONSTREAM" \
  || die "non-stream response lacks the golden content (decode/encode path broken)"
grep -q '"usage"' "$NONSTREAM" || die "non-stream response lacks a usage object"

# ---------------------------------------------------------------- stream chat
log "POST /v1/chat/completions (stream, include_usage)"
STREAM="$RUN_DIR/native-smoke.stream.sse"
curl -sN "http://127.0.0.1:$PORT/v1/chat/completions" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"What is the weather in Paris?"}]}' \
  > "$STREAM"
grep -q 'data: \[DONE\]' "$STREAM" || die "stream lacks the [DONE] terminator"
grep -q '"usage"' "$STREAM" || die "stream lacks the terminal usage frame (include_usage)"

# ---------------------------------------------------------------- Anthropic face (cross-format → same fake)
# Exercises Anthropic wire encode/decode in the native binary without a second upstream:
# /v1/messages → AnthropicMessageCodec → openai-compatible adapter → golden fake.
log "POST /v1/messages (non-stream, Anthropic face)"
AMSG="$RUN_DIR/native-smoke.messages.json"
curl -s "http://127.0.0.1:$PORT/v1/messages" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","max_tokens":64,"messages":[{"role":"user","content":"What is the weather in Paris?"}]}' \
  > "$AMSG"
grep -q "The weather in Paris is 18 degrees with light rain." "$AMSG" \
  || die "Anthropic-face non-stream lacks the golden content (Anthropic codec path broken)"
grep -q '"usage"' "$AMSG" || die "Anthropic-face non-stream lacks a usage object"

log "POST /v1/messages (stream, Anthropic face)"
ASTREAM="$RUN_DIR/native-smoke.messages.sse"
curl -sN "http://127.0.0.1:$PORT/v1/messages" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","max_tokens":64,"stream":true,"messages":[{"role":"user","content":"What is the weather in Paris?"}]}' \
  > "$ASTREAM"
grep -q 'message_stop' "$ASTREAM" || die "Anthropic-face stream lacks message_stop"
grep -q 'content_block_delta\|text_delta\|"type":"content_block' "$ASTREAM" \
  || die "Anthropic-face stream lacks content deltas"

log "NATIVE CODEC SMOKE PASS — OpenAI + Anthropic faces (stream + non-stream) exercised decode→encode in the binary"
