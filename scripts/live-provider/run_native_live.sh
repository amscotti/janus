#!/usr/bin/env bash
# =============================================================================
# run_native_live.sh — native-image smoke against *real* provider APIs (opt-in).
#
# Complements JVM LiveProviderIT (EmbeddedServer) and store-smoke drill_native.py
# (native + golden fake). This closes the last shape: Graal binary + live network.
#
# Gating (same spirit as liveTest — no accidental spend):
# 1) JANUS_LIVE=1 required
# 2) At least one of DEEPSEEK_API_KEY / ANTHROPIC_API_KEY
# 3) Not part of./gradlew build / test
#
# Usage (repo root):
# set -a && source.env && set +a
# export JANUS_LIVE=1
# bash scripts/live-provider/run_native_live.sh
# # or skip rebuild if binary is fresh:
# bash scripts/live-provider/run_native_live.sh --skip-build
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"
SCRIPT_DIR="$REPO/scripts/live-provider"
CONFIG="$SCRIPT_DIR/config.native-live.toml"
BIN="$REPO/janus-gateway/build/native/nativeCompile/janus"
PORT="${NATIVE_LIVE_PORT:-18090}"
MASTER="${JANUS_MASTER_KEY:-native-live-master-key-not-for-prod}"
SKIP_BUILD=0
for arg in "$@"; do
  [[ "$arg" == "--skip-build" ]] && SKIP_BUILD=1
done

log() { printf '[native-live] %s\n' "$*" >&2; }
die() { printf '[native-live] FATAL: %s\n' "$*" >&2; exit 1; }

# curl -s wrapper that fails loudly (die) with the HTTP status + response body on a
# non-2xx status or a transfer error. curl -f would exit before the caller's error
# line is reached, hiding which check failed and why.
# Usage: http <label> <curl args...>
http() {
  local label="$1"
  shift
  local tmp body code
  tmp="$(mktemp)"
  if ! code="$(curl -s -o "$tmp" -w '%{http_code}' "$@")"; then
    body="$(cat "$tmp" 2>/dev/null || true)"
    rm -f "$tmp"
    die "curl error for $label — response: ${body:0:500}"
  fi
  body="$(cat "$tmp")"
  rm -f "$tmp"
  if [[ ! "$code" =~ ^2[0-9][0-9]$ ]]; then
    die "HTTP $code from $label — response: ${body:0:500}"
  fi
  printf '%s' "$body"
}

[[ "${JANUS_LIVE:-}" == "1" ]] || die "set JANUS_LIVE=1 to opt in (real provider spend)"
[[ -f "$CONFIG" ]] || die "missing config: $CONFIG"

MODEL=""
if [[ -n "${DEEPSEEK_API_KEY:-}" ]]; then
  MODEL="deepseek-v4-flash"
elif [[ -n "${ANTHROPIC_API_KEY:-}" ]]; then
  MODEL="claude-sonnet-5"
else
  die "need DEEPSEEK_API_KEY or ANTHROPIC_API_KEY"
fi

export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
if [[ "$SKIP_BUILD" -eq 0 ]]; then
  log "nativeCompile (use --skip-build if binary is already current)"
./gradlew :janus-gateway:nativeCompile --no-daemon
fi
[[ -x "$BIN" ]] || die "native binary missing: $BIN (run without --skip-build)"

# Free the port if a previous run left something behind.
if command -v lsof >/dev/null 2>&1; then
  if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    die "port $PORT already in use — free it or set NATIVE_LIVE_PORT"
  fi
fi

NATIVE_PID=""
cleanup() {
  if [[ -n "$NATIVE_PID" ]] && kill -0 "$NATIVE_PID" 2>/dev/null; then
    kill "$NATIVE_PID" 2>/dev/null || true
    wait "$NATIVE_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

log "booting native Janus on 127.0.0.1:$PORT (MICRONAUT_CONFIG_FILES — not --config)"
JANUS_MASTER_KEY="$MASTER" \
  MICRONAUT_CONFIG_FILES="$CONFIG" \
  MICRONAUT_SERVER_HOST=127.0.0.1 \
  MICRONAUT_SERVER_PORT="$PORT" \
  "$BIN" >"$SCRIPT_DIR/.run-native-live.log" 2>&1 &
NATIVE_PID=$!

BASE="http://127.0.0.1:${PORT}"
# macOS can park a freshly linked native-image in dyld for tens of seconds on
# first exec (Gatekeeper / provenance). 15s was enough after that, not before.
for i in $(seq 1 120); do
  if curl -sf "$BASE/health" >/dev/null 2>&1; then
    break
  fi
  if ! kill -0 "$NATIVE_PID" 2>/dev/null; then
    die "native process died during boot — see $SCRIPT_DIR/.run-native-live.log"
  fi
  sleep 0.5
  if [[ "$i" -eq 120 ]]; then
    die "native never reached /health — see $SCRIPT_DIR/.run-native-live.log"
  fi
done
log "health ok"

# Mint virtual key
GEN=$(http "key/generate" -X POST "$BASE/key/generate" \
  -H "Content-Type: application/json" \
  -H "x-api-key: $MASTER" \
  -d "{\"name\":\"native-live\",\"models\":[\"$MODEL\"]}")
KEY=$(printf '%s' "$GEN" | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')
[[ "$KEY" == sk-janus-* ]] || die "key/generate returned an unexpected body: ${GEN:0:200}"
log "minted virtual key for $MODEL"

# Non-stream chat
CHAT=$(http "chat/completions (non-stream)" -X POST "$BASE/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $KEY" \
  -d "{\"model\":\"$MODEL\",\"messages\":[{\"role\":\"user\",\"content\":\"Reply with exactly the word pong.\"}],\"max_tokens\":32}")
printf '%s' "$CHAT" | python3 -c '
import json,sys
p=json.load(sys.stdin)
c=p["choices"][0]["message"].get("content") or ""
assert c.strip(), "empty assistant content: "+json.dumps(p)[:400]
print("PASS non-stream chat: content non-empty")
'

# Stream chat
STREAM=$(http "chat/completions (stream)" -N -X POST "$BASE/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $KEY" \
  -d "{\"model\":\"$MODEL\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Reply with exactly the word pong.\"}],\"max_tokens\":32}")
printf '%s' "$STREAM" | python3 -c '
import sys
s=sys.stdin.read()
assert "data:" in s, "no SSE data: frames"
assert "[DONE]" in s or "message_stop" in s, s[:500]
print("PASS stream chat: SSE payload received")
'

# R5 (Responses plan 5.5): the Responses face on the native binary — a non-stream
# create must return the response object with the stateless store:false echo.
RESPONSES=$(http "responses (non-stream)" -X POST "$BASE/v1/responses" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $KEY" \
  -d "{\"model\":\"$MODEL\",\"input\":\"Reply with exactly: pong\"}")
printf '%s' "$RESPONSES" | python3 -c '
import json, sys
res = json.loads(sys.stdin.read())
assert res.get("object") == "response", str(res)[:300]
assert res.get("store") is False, "the stateless face echoes store:false"
assert res.get("status") == "completed", str(res)[:300]
print("PASS responses non-stream: object=response, store=false, completed")
'

log "all native live checks passed (model=$MODEL)"
