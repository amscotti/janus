#!/usr/bin/env bash
# =============================================================================
# run.sh — executes the README Quickstart VERBATIM
#
# This harness is the executable proof for README.md's Quickstart section: it
# runs the exact command lines from the README (build → configure → JVM run →
# /health → /key/generate → chat non-stream → chat stream → /key/list →
# /metrics), with ONE harness-only difference: JANUS_CONFIG points at
# scripts/smoke/readme/config.walkthrough.toml, which routes the deepseek-v4-flash
# alias to the committed fake upstream (scripts/smoke/store/fake_upstream.py,
# reused read-only — the /golden corpus, pinned usage 14/12), so the
# whole sequence runs fully offline. No real provider keys, no network.
# Backgrounding/redirection of the gateway process is harness-only — the
# command text is the README's.
#
# Fresh-machine caveat (recorded, honest): this host already has the
# JDK/toolchain; the true clone-on-a-clean-box test is the Review & Fix
# gate, which re-runs this identical sequence. The native-image and Docker
# legs are /verified artifacts that the README cites by number — they
# are NOT re-run here.
#
# Usage:
# scripts/smoke/readme/run.sh
#
# Env:
# JANUS_MASTER_KEY master key (default: distinctive per-run value)
# FAKE_PORT fake upstream port (default 9877)
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO"
DOCS_DIR="$REPO/scripts/smoke/readme"
RUN_DIR="$DOCS_DIR/.run"
RESULTS="$DOCS_DIR/RESULTS.md"
GATEWAY_PORT=8080
FAKE_PORT="${FAKE_PORT:-9877}"
PY_BIN="python3"

export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

log() { printf '[walkthrough] %s\n' "$*" >&2; }
die() { printf '[walkthrough] FATAL: %s\n' "$*" >&2; exit 1; }

# The README Quickstart's config step — verbatim, with JANUS_CONFIG overridden
# to the walkthrough config (the README documents this override).
export JANUS_MASTER_KEY="${JANUS_MASTER_KEY:-$(openssl rand -hex 24)}"
export JANUS_CONFIG="$DOCS_DIR/config.walkthrough.toml"

PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do kill "$pid" 2>/dev/null || true; done
  wait 2>/dev/null || true
}
trap cleanup EXIT

mkdir -p "$RUN_DIR"
: > "$RESULTS"

step() { # step <n> <title> — records a PASS line with a fenced output block
  local n="$1" title="$2" out="$3"
  {
    printf -- '- **PASS** step %s — %s\n\n```\n' "$n" "$title"
    sed 's/^/  /' "$out"
    printf '```\n\n'
  } >> "$RESULTS"
}
fail() { # fail <n> <title> <out> <reason>
  local n="$1" title="$2" out="$3" reason="$4"
  {
    printf -- '- **FAIL** step %s — %s\n\n```\n' "$n" "$title"
    sed 's/^/  /' "$out"
    printf '```\n\n**Reason:** %s\n' "$reason"
  } >> "$RESULTS"
  die "step $n failed ($title): $reason"
}

wait_health() { # wait_health <url> <label> — poll until 200 (or timeout)
  local url="$1" label="$2"
  for _ in $(seq 1 300); do
    if curl -sf "$url" >/dev/null 2>&1; then return 0; fi
    sleep 0.2
  done
  die "$label did not come up in time (see $RUN_DIR/$label.log)"
}

# ---------------------------------------------------------------- preamble
log "README walkthrough start — gateway :$GATEWAY_PORT · fake :$FAKE_PORT · master key ON"
GATE_START="$(date +%s%N)"
{
  printf '# — README quickstart walkthrough results\n\n'
  printf -- '- **Date:** %s\n' "$(date -u '+%Y-%m-%d %H:%M UTC')"
  printf -- '- **Commit:** %s\n' "$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  printf -- '- **Java:** %s\n' "$(java -version 2>&1 | head -1)"
  printf -- '- **Gradle:** %s\n' "$(./gradlew --version 2>/dev/null | awk '/^Gradle/{print $2}')"
  printf -- '- **Master key:** distinctive per-run value (ON — never logged)\n'
  printf -- '- **Config under test:** `scripts/smoke/readme/config.walkthrough.toml` (auth ON; deepseek-v4-flash → fake :%s)\n' "$FAKE_PORT"
  printf -- '- **Fake upstream:** `scripts/smoke/store/fake_upstream.py` (read-only; golden 14/12 corpus, committed fixtures)\n'
  printf -- '- **Fresh-machine caveat:** native/Docker legs are verified separately and cited by number in the README, not re-run here; for a clone-on-a-clean-box proof, re-run this script there.\n\n'
  printf '## Steps (README Quickstart, verbatim)\n\n'
} >> "$RESULTS"

# ---------------------------------------------------------------- ports free
if curl -sf "http://127.0.0.1:$FAKE_PORT/" >/dev/null 2>&1; then
  die "port $FAKE_PORT is busy (something already serves there) — set FAKE_PORT to a free port and edit config.walkthrough.toml base-url to match"
fi
if curl -sf "http://127.0.0.1:$GATEWAY_PORT/health" >/dev/null 2>&1; then
  die "port $GATEWAY_PORT is busy — the README quickstart assumes the default 8080"
fi

# ---------------------------------------------------------------- step 1: build
log "step 1/9 — ./gradlew build (README Quickstart §1)"
if ! ./gradlew build > "$RUN_DIR/build.log" 2>&1; then
  fail 1 "build — ./gradlew build" "$RUN_DIR/build.log" "gradle build exited nonzero (spotless/-Werror/tests)"
fi
tail -3 "$RUN_DIR/build.log" | grep -q "BUILD SUCCESSFUL" || fail 1 "build — ./gradlew build" "$RUN_DIR/build.log" "no BUILD SUCCESSFUL in output"
step 1 "build — \`./gradlew build\` (README Quickstart §1)" <(tail -5 "$RUN_DIR/build.log")

# ---------------------------------------------------------------- step 2: fake upstream
log "step 2/9 — boot the committed fake upstream (offline)"
rm -f "$RUN_DIR/fake.counters.json" "$RUN_DIR/fake.abort.log"
nohup "$PY_BIN" "$REPO/scripts/smoke/store/fake_upstream.py" --port "$FAKE_PORT" \
  --name docs-fake --counter-file "$RUN_DIR/fake.counters.json" \
  --abort-log "$RUN_DIR/fake.abort.log" > "$RUN_DIR/fake.log" 2>&1 &
PIDS+=("$!")
wait_health "http://127.0.0.1:$FAKE_PORT/" "fake upstream"
step 2 "boot the committed fake upstream on :$FAKE_PORT (README Quickstart §2 — the offline provider)" <(printf 'fake_upstream.py --port %s --name docs-fake (golden 14/12 corpus)\n' "$FAKE_PORT")

# ---------------------------------------------------------------- step 3: run the gateway (JVM)
log "step 3/9 — start Janus (JVM) with the README run command"
rm -f "$RUN_DIR/janus.log"
nohup ./gradlew :janus-cli:run --args="--config $JANUS_CONFIG" > "$RUN_DIR/janus.log" 2>&1 &
PIDS+=("$!")
wait_health "http://127.0.0.1:$GATEWAY_PORT/health/readiness" "janus"
step 3 'run — `./gradlew :janus-cli:run --args="--config $JANUS_CONFIG"` (README Quickstart §3)' <(printf 'gateway booted on :%s (readiness 200)\n' "$GATEWAY_PORT")

# ---------------------------------------------------------------- step 4: health
log "step 4/9 — README Quickstart §4: /health"
curl -s "http://127.0.0.1:$GATEWAY_PORT/health" > "$RUN_DIR/health.json"
grep -q '"status":"UP"' "$RUN_DIR/health.json" || fail 4 "health — \`curl -s http://127.0.0.1:8080/health\`" "$RUN_DIR/health.json" "no \"status\":\"UP\" in body"
step 4 "health — \`curl -s http://127.0.0.1:8080/health\` (README Quickstart §4)" "$RUN_DIR/health.json"

# ---------------------------------------------------------------- step 5: admin key
log "step 5/9 — README Quickstart §5: POST /key/generate (master key)"
JANUS_KEY="$(curl -s -X POST http://127.0.0.1:$GATEWAY_PORT/key/generate \
  -H "x-api-key: $JANUS_MASTER_KEY" -H 'Content-Type: application/json' \
  -d '{"name":"quickstart","models":["deepseek-v4-flash"]}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')"
case "$JANUS_KEY" in
  sk-janus-*) : ;;
  *) fail 5 "admin key — \`POST /key/generate\`" <(printf '%s\n' "$JANUS_KEY") "response has no sk-janus- key";;
esac
step 5 "admin key — \`POST /key/generate\` with the master key (README Quickstart §5)" <(printf 'key: %s… (full value shown exactly once; never logged)\n' "${JANUS_KEY:0:16}")

# ---------------------------------------------------------------- step 6: chat (non-stream)
log "step 6/9 — README Quickstart §6: chat round-trip (non-streaming)"
curl -s "http://127.0.0.1:$GATEWAY_PORT/v1/chat/completions" \
  -H "x-api-key: $JANUS_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"hello"}]}' > "$RUN_DIR/chat.nonstream.json"
grep -q "The weather in Paris is 18 degrees with light rain." "$RUN_DIR/chat.nonstream.json" \
  || fail 6 "chat non-stream — \`curl … /v1/chat/completions\`" "$RUN_DIR/chat.nonstream.json" "golden content missing from response"
step 6 "chat round-trip — non-streaming (README Quickstart §6)" "$RUN_DIR/chat.nonstream.json"

# ---------------------------------------------------------------- step 7: chat (stream)
log "step 7/9 — README Quickstart §7: chat round-trip (streaming)"
curl -sN "http://127.0.0.1:$GATEWAY_PORT/v1/chat/completions" \
  -H "x-api-key: $JANUS_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"hello"}]}' > "$RUN_DIR/chat.stream.sse"
grep -q 'data: \[DONE\]' "$RUN_DIR/chat.stream.sse" || fail 7 "chat stream — \`curl -sN …\`" "$RUN_DIR/chat.stream.sse" "no data: [DONE] terminator"
grep -q '"usage"' "$RUN_DIR/chat.stream.sse" || fail 7 "chat stream — \`curl -sN …\`" "$RUN_DIR/chat.stream.sse" "no terminal usage frame (include_usage)"
step 7 "chat round-trip — streaming SSE + terminal usage frame (README Quickstart §7)" <(grep -E 'data: .*"content"|data: \[DONE\]|"usage"' "$RUN_DIR/chat.stream.sse" | head -8)

# ---------------------------------------------------------------- step 8: key list
log "step 8/9 — README Quickstart §8: GET /key/list (redacted)"
curl -s "http://127.0.0.1:$GATEWAY_PORT/key/list" -H "x-api-key: $JANUS_MASTER_KEY" > "$RUN_DIR/key.list.json"
grep -q '"keys":\[' "$RUN_DIR/key.list.json" || fail 8 "key list — \`curl … /key/list\`" "$RUN_DIR/key.list.json" "no keys array in body"
if grep -qF "$JANUS_KEY" "$RUN_DIR/key.list.json"; then
  fail 8 "key list — \`curl … /key/list\`" "$RUN_DIR/key.list.json" "full key string leaked into the redacted list"
fi
step 8 "key list — \`GET /key/list\` redacted (no full key; README Quickstart §8)" "$RUN_DIR/key.list.json"

# ---------------------------------------------------------------- step 9: metrics
log "step 9/9 — README Quickstart §9: /metrics shows janus_requests_total"
curl -s "http://127.0.0.1:$GATEWAY_PORT/metrics" | grep janus_requests_total > "$RUN_DIR/metrics.janus_requests_total.txt"
grep -q 'status="2xx"' "$RUN_DIR/metrics.janus_requests_total.txt" \
  || fail 9 "metrics — \`curl -s http://127.0.0.1:8080/metrics | grep janus_requests_total\`" "$RUN_DIR/metrics.janus_requests_total.txt" "no 2xx sample"
step 9 "metrics — \`/metrics\` shows \`janus_requests_total\` (README Quickstart §9)" "$RUN_DIR/metrics.janus_requests_total.txt"

# ---------------------------------------------------------------- wrap-up
# Exact-cost spot check (recorded, not a gate): 2 chats × golden 14/12 ×
# DeepSeek 0.14/0.28 per 1K = 2 × 5320 micro-USD = 10640.0.
COST="$(curl -s "http://127.0.0.1:$GATEWAY_PORT/metrics" | awk '/^janus_cost_micro_usd_total /{print $2; exit}')"
{
  printf '## Wrap-up\n\n'
  printf -- '- **Exact-cost spot check:** `janus_cost_micro_usd_total %s` (expected `10640.0` = 2 chats × 5320 micro-USD, golden 14/12 × DeepSeek table) — recorded, not a gate\n' "${COST:-n/a}"
  printf -- '- **Gate wall-clock:** %ss\n' "$(( ($(date +%s%N) - GATE_START) / 1000000000 ))"
  printf -- '- **Verdict:** all README Quickstart steps executed verbatim and passed (offline, fake upstream)\n'
} >> "$RESULTS"

log "walkthrough PASS — all 9 README Quickstart steps green; results in $RESULTS"
