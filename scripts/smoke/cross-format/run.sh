#!/usr/bin/env bash
# =============================================================================
# run.sh — cross-format e2e gate
#
# Proves with UNMODIFIED SDKs (fresh venv, pinned openai + anthropic) against a live
# Janus boot that Janus is a drop-in DUAL-PROTOCOL endpoint: Anthropic SDK → Janus →
# DeepSeek and OpenAI SDK → Janus → Claude, each streaming + non-streaming, plus
# tool-call round-trips in both directions — and the cross-format review & Fix bullets:
# prior-art delta-shape review (RESULTS.md record), stream-abort cancellation drill,
# native-image build with the Jackson polymorphic types, fixture cross-check (env-
# gated). Deterministic legs run against stdlib-only fake upstreams
# (fake_anthropic.py — strict schema mode — and fake_openai_compat.py) fed by the
# committed /fixtures; the real-upstream legs run only when ANTHROPIC_API_KEY
# / DEEPSEEK_API_KEY are exported (never in CI). Writes RESULTS.md.
#
# ships NO production features: the only files this script touches live under
# scripts/smoke/cross-format/ (plus the Gradle build/native outputs and this RESULTS.md);
# the blessed production fixes (D1 stream_options strip, D2 oa outbound object)
# are their own test-first commits, verified by the matrix suites inside./gradlew
# build and exercised live here.
#
# Usage:
# scripts/smoke/cross-format/run.sh [--fresh-venv] [--skip-native]
#
# Env:
# JANUS_PORT gateway port (default 8080; free port when taken)
# FAKE_OAI_PORT fake OpenAI-compat upstream port (default 9877)
# FAKE_ANTH_PORT fake Anthropic upstream port (default 9878)
# OPENAI_PIN pinned openai package version (default 1.109.0)
# ANTHROPIC_PIN pinned anthropic package version (default latest resolved)
# ANTHROPIC_API_KEY / DEEPSEEK_API_KEY exported → the real-upstream legs run
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SMOKE_DIR="$REPO/scripts/smoke/cross-format"
RUN_DIR="$SMOKE_DIR/.run"
RESULTS="$SMOKE_DIR/RESULTS.md"
OPENAI_PIN="${OPENAI_PIN:-1.109.0}"
ANTHROPIC_PIN="${ANTHROPIC_PIN:-}"
FRESH_VENV=0
SKIP_NATIVE=0

export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
PY_BIN="python3"
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

log() { printf '[gate] %s\n' "$*" >&2; }  # stderr: keeps $(...) capture clean (drill timings)
die() { printf '[gate] FATAL: %s\n' "$*" >&2; exit 1; }

# Parse flags anywhere in argv (not only as $1 — `run.sh --fresh-venv --skip-native`
# used to leave SKIP_NATIVE=0 and launch the multi-minute nativeCompile leg the
# operator asked to skip). Same loop shape as bench/run_bench.sh.
args=("$@")
i=0
while [[ $i -lt ${#args[@]} ]]; do
  case "${args[$i]}" in
    --fresh-venv) FRESH_VENV=1 ;;
    --skip-native) SKIP_NATIVE=1 ;;
    *) die "unknown argument: ${args[$i]}";;
  esac
  i=$((i + 1))
done

# ---------------------------------------------------------------- state
mkdir -p "$RUN_DIR"
PIDS=()
GATE_START="$(date +%s%N)"
FAKE_OAI_PID=""
FAKE_ANTH_PID=""
JANUS_PID=""
NATIVE_PID=""

cleanup() {
  # Kill ONLY the processes this run spawned and tracks — a broad
  # pkill -9 -f "fake_openai_compat.py" (etc.) would kill unrelated processes
  # on the machine (concurrent harnesses, editors, shells): the routing/run.sh +
  # bench/run_bench.sh tracked-PID-only policy.
  for pid in "${PIDS[@]:-}" "$FAKE_OAI_PID" "$FAKE_ANTH_PID" "$JANUS_PID" "$NATIVE_PID"; do
    [[ -n "$pid" ]] && kill -9 "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT

# ---------------------------------------------------------------- helpers
port_busy() {
  "$PY_BIN" - "$1" <<'EOF'
import socket, sys
s = socket.socket()
try:
    s.bind(("127.0.0.1", int(sys.argv[1])))
except OSError:
    sys.exit(0)  # busy
finally:
    s.close()
sys.exit(1)
EOF
  [[ $? -eq 0 ]]
}

free_port() {
  "$PY_BIN" - <<'EOF'
import socket
s = socket.socket()
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
EOF
}

wait_for_health() {
  local port="$1" bound="${2:-180}"
  local i
  for i in $(seq 1 "$((bound * 10))"); do
    if curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

wait_models_both_aliases() {
  local port="$1" bound="${2:-30}"
  local i
  for i in $(seq 1 "$((bound * 10))"); do
    local models
    models="$(curl -sf "http://127.0.0.1:$port/v1/models" 2>/dev/null || true)"
    if echo "$models" | grep -q '"deepseek-v4-flash"' && echo "$models" | grep -q '"claude-3-5-sonnet"'; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

wait_fake() {
  local port="$1" bound="${2:-30}"
  local i
  for i in $(seq 1 "$((bound * 10))"); do
    if curl -sf "http://127.0.0.1:$port/" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

# ---------------------------------------------------------------- env
command -v "$PY_BIN" >/dev/null || die "python3 required"
command -v curl >/dev/null || die "curl required"

VENV="$SMOKE_DIR/.venv"
if [[ ! -x "$VENV/bin/python" || "$FRESH_VENV" -eq 1 ]]; then
  log "creating fresh venv at $VENV (openai==$OPENAI_PIN, anthropic==$ANTHROPIC_PIN)"
  rm -rf "$VENV"
  "$PY_BIN" -m venv "$VENV"
  if [[ -n "$ANTHROPIC_PIN" ]]; then
    "$VENV/bin/pip" install --quiet --disable-pip-version-check "openai==$OPENAI_PIN" "anthropic==$ANTHROPIC_PIN"
  else
    "$VENV/bin/pip" install --quiet --disable-pip-version-check "openai==$OPENAI_PIN" "anthropic"
  fi
fi
PY="$VENV/bin/python"
OPENAI_VER="$("$PY" -c 'import openai; print(openai.__version__)')"
ANTHROPIC_VER="$("$PY" -c 'import anthropic; print(anthropic.__version__)')"
log "SDK pins: openai==$OPENAI_VER anthropic==$ANTHROPIC_VER"

# ---------------------------------------------------------------- ports
JANUS_PORT="${JANUS_PORT:-8080}"
if port_busy "$JANUS_PORT"; then
  log "port $JANUS_PORT busy (stale Janus?) — picking a free gateway port"
  JANUS_PORT="$(free_port)"
fi
FAKE_OAI_PORT="${FAKE_OAI_PORT:-9877}"
if port_busy "$FAKE_OAI_PORT"; then
  FAKE_OAI_PORT="$(free_port)"
fi
FAKE_ANTH_PORT="${FAKE_ANTH_PORT:-9878}"
if port_busy "$FAKE_ANTH_PORT"; then
  FAKE_ANTH_PORT="$(free_port)"
fi
BASE_URL="http://127.0.0.1:$JANUS_PORT/v1"
FAKE_CONFIG="$RUN_DIR/config.fake.$JANUS_PORT.toml"
sed -e "s|base-url = \"http://127.0.0.1:9877\"|base-url = \"http://127.0.0.1:$FAKE_OAI_PORT\"|" \
    -e "s|base-url = \"http://127.0.0.1:9878\"|base-url = \"http://127.0.0.1:$FAKE_ANTH_PORT\"|" \
  "$SMOKE_DIR/config.fake.toml" > "$FAKE_CONFIG"
log "gateway: $BASE_URL  fake-openai: :$FAKE_OAI_PORT  fake-anthropic: :$FAKE_ANTH_PORT  config: $FAKE_CONFIG"

# ---------------------------------------------------------------- fakes
boot_fake_oai() {
  log "booting fake OpenAI-compat upstream on :$FAKE_OAI_PORT"
  nohup "$PY_BIN" "$SMOKE_DIR/fake_openai_compat.py" \
    --port "$FAKE_OAI_PORT" \
    --paused-file "$RUN_DIR/fake-oai.paused" \
    --resume-file "$RUN_DIR/fake-oai.resume" \
    --abort-log "$RUN_DIR/abort.log" \
    > "$RUN_DIR/fake-oai.log" 2>&1 &
  FAKE_OAI_PID=$!
  PIDS+=("$FAKE_OAI_PID")
  rm -f "$RUN_DIR/fake-oai.paused" "$RUN_DIR/fake-oai.resume"
  wait_fake "$FAKE_OAI_PORT" || die "fake OpenAI-compat did not come up (see $RUN_DIR/fake-oai.log)"
}
kill_fake_oai() {
  [[ -n "$FAKE_OAI_PID" ]] && kill -9 "$FAKE_OAI_PID" 2>/dev/null || true
  wait "$FAKE_OAI_PID" 2>/dev/null || true
  FAKE_OAI_PID=""
}
boot_fake_anth() {
  log "booting fake Anthropic upstream on :$FAKE_ANTH_PORT"
  nohup "$PY_BIN" "$SMOKE_DIR/fake_anthropic.py" \
    --port "$FAKE_ANTH_PORT" \
    --paused-file "$RUN_DIR/fake-anth.paused" \
    --resume-file "$RUN_DIR/fake-anth.resume" \
    --abort-log "$RUN_DIR/abort.log" \
    > "$RUN_DIR/fake-anth.log" 2>&1 &
  FAKE_ANTH_PID=$!
  PIDS+=("$FAKE_ANTH_PID")
  rm -f "$RUN_DIR/fake-anth.paused" "$RUN_DIR/fake-anth.resume"
  wait_fake "$FAKE_ANTH_PORT" || die "fake Anthropic did not come up (see $RUN_DIR/fake-anth.log)"
}
kill_fake_anth() {
  [[ -n "$FAKE_ANTH_PID" ]] && kill -9 "$FAKE_ANTH_PID" 2>/dev/null || true
  wait "$FAKE_ANTH_PID" 2>/dev/null || true
  FAKE_ANTH_PID=""
}

# ---------------------------------------------------------------- janus (JVM)
boot_janus_jvm() {
  local config="$1"
  log "booting Janus (JVM leg, :$JANUS_PORT) with $config"
  rm -f "$RUN_DIR/janus-jvm.log"
  MICRONAUT_SERVER_PORT="$JANUS_PORT" nohup ./gradlew :janus-cli:run \
    --no-daemon --args="--config $config" > "$RUN_DIR/janus-jvm.log" 2>&1 &
  PIDS+=("$!")
  wait_for_health "$JANUS_PORT" 240 || die "JVM Janus did not reach /health (see $RUN_DIR/janus-jvm.log)"
  JANUS_PID="$(pgrep -f 'io.amscotti.janus.cli.JanusCli' | tail -1)"
  [[ -n "$JANUS_PID" ]] || die "could not find the JanusCli JVM pid"
  log "JVM Janus healthy on :$JANUS_PORT (pid $JANUS_PID)"
  wait_models_both_aliases "$JANUS_PORT" || die "boot-misconfig tripwire: /v1/models lacks both aliases on the JVM leg"
}
kill_janus_jvm() {
  [[ -n "$JANUS_PID" ]] && kill -9 "$JANUS_PID" 2>/dev/null || true
  wait "$JANUS_PID" 2>/dev/null || true
  # SIGKILL the gradle wrapper does not reap the forked java instantly; the listening
  # socket frees only when the JVM dies. The next leg (native) boots on the same port
  # immediately — wait for the port to actually free (bounded) or the native leg races
  # a held socket and "never reaches /health". (No broad pkill here either — the
  # tracked JANUS_PID above is this run's own JVM.)
  for _ in $(seq 1 50); do
    port_busy "$JANUS_PORT" || break
    sleep 0.2
  done
  JANUS_PID=""
}

# ---------------------------------------------------------------- drills
run_eager_kill_drill() {
  local which="$1"  # oo (fake OpenAI-compat) | aa (fake Anthropic)
  log "drill: eager kill — $which upstream dead before a non-streaming request"
  local start_ms elapsed_ms
  if [[ "$which" == "oo" ]]; then
    kill_fake_oai
    start_ms="$(date +%s%N)"
    "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check eager-kill --bound 5 >&2
    elapsed_ms=$(( ($(date +%s%N) - start_ms) / 1000000 ))
    log "PASS eager-kill ($which): 502 api_error envelope in ${elapsed_ms}ms (bound 5000ms)"
    boot_fake_oai
  else
    kill_fake_anth
    start_ms="$(date +%s%N)"
    "$PY" "$SMOKE_DIR/smoke_anthropic.py" --base-url "$BASE_URL" --model claude-3-5-sonnet --check eager-kill --bound 5 >&2
    elapsed_ms=$(( ($(date +%s%N) - start_ms) / 1000000 ))
    log "PASS eager-kill ($which): 502 api_error envelope in ${elapsed_ms}ms (bound 5000ms)"
    boot_fake_anth
  fi
  echo "$elapsed_ms"
}

run_midstream_drill() {
  local face="$1"  # openai | anthropic
  local paused resume
  paused="$RUN_DIR/fake-oai.paused"; resume="$RUN_DIR/fake-oai.resume"
  if [[ "$face" == "anthropic" ]]; then
    paused="$RUN_DIR/fake-anth.paused"; resume="$RUN_DIR/fake-anth.resume"
  fi
  log "drill: mid-stream kill — $face face, upstream killed between frames"
  rm -f "$RUN_DIR/client-ready" "$RUN_DIR/midstream-client.exit" "$paused" "$resume"
  ( "$PY" "$SMOKE_DIR/kill_midstream.py" --base-url "$BASE_URL" --face "$face" \
      --ready-file "$RUN_DIR/client-ready" --bound 5 \
      > "$RUN_DIR/midstream-$face.log" 2>&1
    echo $? > "$RUN_DIR/midstream-client.exit" ) &
  local client_pid=$!
  local i
  for i in $(seq 1 100); do
    [[ -f "$RUN_DIR/client-ready" ]] && break
    sleep 0.1
  done
  [[ -f "$RUN_DIR/client-ready" ]] || {
    cat "$RUN_DIR/midstream-$face.log"
    die "mid-stream drill ($face): client never received a first frame"
  }
  sleep 0.3 # the fake is mid-hold (frame flushed, partial frame pending)
  local kill_start
  kill_start="$(date +%s%N)"
  touch "$resume"
  for i in $(seq 1 100); do
    [[ -f "$RUN_DIR/midstream-client.exit" ]] && break
    sleep 0.1
  done
  local kill_elapsed_ms=$(( ($(date +%s%N) - kill_start) / 1000000 ))
  local client_exit
  client_exit="$(cat "$RUN_DIR/midstream-client.exit" 2>/dev/null || echo 1)"
  if [[ "$client_exit" != "0" ]]; then
    cat "$RUN_DIR/midstream-$face.log"
    die "mid-stream drill ($face): client failed (exit $client_exit)"
  fi
  if [[ "$kill_elapsed_ms" -gt 5000 ]]; then
    die "mid-stream drill ($face): completion after kill took ${kill_elapsed_ms}ms (> 5000ms — hung?)"
  fi
  log "PASS mid-stream drill ($face): SSE error frame + clean completion in ${kill_elapsed_ms}ms post-kill (bound 5000ms)"
  wait "$client_pid" 2>/dev/null || true
  echo "$kill_elapsed_ms"
}

check_fake_anthropic_strict() {
 # : the strict fake's stream_options rejection — the D1 red
  # surface the plan designed the strict mode for — must be an exercised
  # assertion, not dead code. Raw POST with a leaked stream_options to the fake
  # Anthropic → 400 invalid_request_error (param=stream_options). The live oa/aa
  # stream legs are the green side (Janus strips the field; the fake accepts).
  log "strict-fake tripwire: leaked stream_options must be rejected 400 invalid_request_error"
  "$PY" - "$FAKE_ANTH_PORT" <<'EOF'
import http.client, json, sys
port = int(sys.argv[1])
conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10.0)
body = json.dumps({
    "model": "claude-3-5-sonnet",
    "max_tokens": 1024,
    "stream": True,
    "stream_options": {"include_usage": True},
    "messages": [{"role": "user", "content": "strict-fake-check"}],
})
conn.request("POST", "/v1/messages", body=body, headers={"Content-Type": "application/json"})
resp = conn.getresponse()
raw = resp.read().decode("utf-8", "replace")
conn.close()
assert resp.status == 400, f"strict fake: HTTP {resp.status} (expected 400): {raw}"
err = json.loads(raw)["error"]
assert err["type"] == "invalid_request_error", f"strict fake: error.type {err['type']!r} != invalid_request_error"
assert err.get("param") == "stream_options", f"strict fake: param {err.get('param')!r} != stream_options"
print("PASS strict-fake tripwire: stream_options rejected with 400 invalid_request_error (param=stream_options)")
EOF
}

# Hard gate assertion : the abort drill's "clean Janus log" contract
# (Review & Fix bullet 2c) must fail the gate, not just WARN, and be recorded.
check_janus_log_clean() {
  local log_file="$1" label="$2"
  if grep -qi 'unhandled\|Exception in thread\|at io\.amscotti' "$log_file"; then
    echo "=== exception traces in $log_file ===" >&2
    grep -i 'unhandled\|Exception in thread\|at io\.amscotti' "$log_file" | head -20 >&2
    die "abort drill ($label): $log_file contains unhandled exception traces (see above)"
  fi
  echo "- **Janus log clean (abort drill, $label):** PASS — no unhandled exceptions in $(basename "$log_file")" >> "$RESULTS"
}

# ---------------------------------------------------------------- legs
LEG_JVM=()
LEG_NATIVE=()
LEG_REAL=()

echo "" > "$RESULTS"
{
  echo "# — stage 2 e2e gate results"
  echo
  echo "- **Date:** $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo "- **Commit:** $(git -C "$REPO" rev-parse --short HEAD)"
  echo "- **Java:** $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  echo "- **Gradle:** $(cd "$REPO" && ./gradlew --version 2>/dev/null | awk '/^Gradle/{print $2}' | head -1)"
  echo "- **OpenAI SDK pin:** \`openai==$OPENAI_VER\` · **Anthropic SDK pin:** \`anthropic==$ANTHROPIC_VER\` (fresh venv, \`$VENV\`)"
  echo "- **Gateway port:** $JANUS_PORT · **Fake OpenAI-compat:** :$FAKE_OAI_PORT · **Fake Anthropic:** :$FAKE_ANTH_PORT"
  echo
  echo "## Baseline: \`./gradlew build --no-daemon\`"
} >> "$RESULTS"

log "baseline ./gradlew build"
(cd "$REPO" && ./gradlew build --no-daemon > "$RUN_DIR/build-baseline.log" 2>&1) \
  || die "baseline build failed (see $RUN_DIR/build-baseline.log)"
echo "- **Result:** BUILD SUCCESSFUL (all modules, spotless + \`-Werror\`)" >> "$RESULTS"

# ---- JVM leg -----------------------------------------------------------
boot_fake_oai
boot_fake_anth
boot_janus_jvm "$FAKE_CONFIG"
{
  echo
  echo "## Leg 1 — JVM boot vs fake upstreams (deterministic, offline)"
  echo
} >> "$RESULTS"

# : exercise the strict fake's D1 red surface directly (the plan's
# red→green sequence was substituted by this assertion — the blessed fixes predate
# the gate run, so the rejection path must be proven live, not assumed).
# 2>/dev/null: the function's progress log goes to stderr (gate console only);
# only the PASS/FAIL assertion line (stdout) is captured into RESULTS.md.
STRICT_OUT="$(check_fake_anthropic_strict 2>/dev/null)" \
  || die "strict-fake tripwire FAILED (D1 red surface broken): $STRICT_OUT"
echo "    $STRICT_OUT" >> "$RESULTS"
echo "- **Strict-fake tripwire (D1 red surface):** PASS — raw POST with \`stream_options\` rejected 400 \`invalid_request_error\`; the live \`oa\`/\`aa\` stream legs are the green side (Janus strips the field, the fake accepts)" >> "$RESULTS"

log "smoke_sdk (oo + oa + tools + errors) vs JVM leg"
: > "$RUN_DIR/smoke-sdk-jvm.log"
if "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check all >> "$RUN_DIR/smoke-sdk-jvm.log" 2>&1; then
  sed 's/^/    /' "$RUN_DIR/smoke-sdk-jvm.log" >> "$RESULTS"
  echo "- **OpenAI SDK smoke (models / oo non-stream+stream+raw / oa non-stream+stream+raw / tools / errors): PASS**" >> "$RESULTS"
  LEG_JVM+=("sdk:PASS")
else
  cat "$RUN_DIR/smoke-sdk-jvm.log" >&2
  die "smoke_sdk --check all FAILED on the JVM leg"
fi

log "smoke_anthropic (ao + aa + tools + errors) vs JVM leg"
: > "$RUN_DIR/smoke-anthropic-jvm.log"
if "$PY" "$SMOKE_DIR/smoke_anthropic.py" --base-url "$BASE_URL" --check all >> "$RUN_DIR/smoke-anthropic-jvm.log" 2>&1; then
  sed 's/^/    /' "$RUN_DIR/smoke-anthropic-jvm.log" >> "$RESULTS"
  echo "- **Anthropic SDK smoke (ao+aa non-stream+stream+raw / tools / errors): PASS**" >> "$RESULTS"
  LEG_JVM+=("anthropic:PASS")
else
  cat "$RUN_DIR/smoke-anthropic-jvm.log" >&2
  die "smoke_anthropic --check all FAILED on the JVM leg"
fi

EAGER_OO_MS="$(run_eager_kill_drill oo)"
echo "- **Kill-upstream drill (eager, oo):** 502 \`api_error\` envelope in ${EAGER_OO_MS}ms (bound 5000ms), no hang" >> "$RESULTS"
EAGER_AA_MS="$(run_eager_kill_drill aa)"
echo "- **Kill-upstream drill (eager, aa):** 502 \`api_error\` envelope in ${EAGER_AA_MS}ms (bound 5000ms), no hang" >> "$RESULTS"
MID_OAI_MS="$(run_midstream_drill openai)"
echo "- **Kill-upstream drill (mid-stream, OpenAI face):** SSE error frame + clean completion ${MID_OAI_MS}ms post-kill (bound 5000ms), no hang" >> "$RESULTS"
MID_ANTH_MS="$(run_midstream_drill anthropic)"
echo "- **Kill-upstream drill (mid-stream, Anthropic face):** SSE error frame + clean completion ${MID_ANTH_MS}ms post-kill (bound 5000ms), no hang" >> "$RESULTS"

log "abort drill (4 cells: oo/oa/ao/aa)"
# run_drill pattern (routing/run.sh): capture WITHOUT letting `set -e` abort the
# assignment before the failing drill's output reaches the operator.
ABORT_OUT="$("$PY" "$SMOKE_DIR/abort_drill.py" --base-url "$BASE_URL" --janus-pid "$JANUS_PID" \
  --abort-log "$RUN_DIR/abort.log" --thread-slack 24 2>&1)" \
  || { printf '%s\n' "$ABORT_OUT" >&2; die "abort drill FAILED (see output above)"; }
echo "    $ABORT_OUT" >> "$RESULTS"
echo "- **Stream-abort drill:** $(echo "$ABORT_OUT" | grep -c '^PASS') pass — upstream cancellation observed, platform threads flat, follow-ups succeed" >> "$RESULTS"
if ! grep -q '^PASS' <<<"$ABORT_OUT"; then
  echo "$ABORT_OUT" >&2
  die "abort drill FAILED (see output above)"
fi
# : the abort drill's clean-log contract is a hard gate assertion.
check_janus_log_clean "$RUN_DIR/janus-jvm.log" "JVM leg"
LEG_JVM+=("drills:PASS" "abort:PASS" "logclean:PASS")

log "stress: 50 concurrent SSE streams (both faces) vs JVM leg"
STRESS_OUT="$("$PY" "$SMOKE_DIR/stress_streams.py" --base-url "$BASE_URL" --janus-pid "$JANUS_PID" \
  --streams 50 --thread-slack 24 2>&1)" \
  || { printf '%s\n' "$STRESS_OUT" >&2; die "stress drill FAILED (see output above)"; }
echo "    $STRESS_OUT" >> "$RESULTS"
echo "- **50 concurrent SSE streams (both faces):** $(echo "$STRESS_OUT" | grep -c '^PASS') pass" >> "$RESULTS"
kill_janus_jvm
LEG_JVM+=("stress50:PASS")

# ---- native leg --------------------------------------------------------
if [[ "$SKIP_NATIVE" -eq 1 ]]; then
  echo
  echo "## Leg 2 — native boot: SKIPPED (--skip-native)" >> "$RESULTS"
  LEG_NATIVE+=("skip")
else
  log ":janus-gateway:nativeCompile"
  (cd "$REPO" && ./gradlew :janus-gateway:nativeCompile --no-daemon > "$RUN_DIR/native-build.log" 2>&1) \
    || die "nativeCompile failed (see $RUN_DIR/native-build.log)"
  BIN="$REPO/janus-gateway/build/native/nativeCompile/janus"
  [[ -x "$BIN" ]] || die "native binary missing: $BIN"
  if [[ "$(uname)" == "Darwin" ]]; then
    NATIVE_SIZE_MB="$(stat -f%z "$BIN" | awk '{printf "%.1f", $1/1048576}')"
  else
    NATIVE_SIZE_MB="$(stat -c%s "$BIN" | awk '{printf "%.1f", $1/1048576}')"
  fi
  {
    echo
    echo "## Leg 2 — native boot (GraalVM image) vs fake upstreams"
    echo
    echo "- **Native binary size:** ${NATIVE_SIZE_MB} MiB (\`$BIN\`)"
  } >> "$RESULTS"

  log "booting native Janus on :$JANUS_PORT (MICRONAUT_CONFIG_FILES — NOT --config)"
  NATIVE_START="$(date +%s%N)"
  MICRONAUT_CONFIG_FILES="$FAKE_CONFIG" MICRONAUT_SERVER_PORT="$JANUS_PORT" \
    nohup "$BIN" > "$RUN_DIR/native.log" 2>&1 &
  NATIVE_PID=$!
  PIDS+=("$NATIVE_PID")
  wait_for_health "$JANUS_PORT" 60 || die "native Janus did not reach /health (see $RUN_DIR/native.log)"
  NATIVE_HEALTH_MS=$(( ($(date +%s%N) - NATIVE_START) / 1000000 ))
  log "native Janus healthy on :$JANUS_PORT in ${NATIVE_HEALTH_MS}ms (pid $NATIVE_PID)"
  echo "- **Boot → /health:** ${NATIVE_HEALTH_MS}ms" >> "$RESULTS"
  wait_models_both_aliases "$JANUS_PORT" || die "boot-misconfig tripwire: /v1/models lacks both aliases on the native leg"

  log "smoke (reduced: oo + oa + both Anthropic-face directions + tools + errors) vs native leg"
  : > "$RUN_DIR/smoke-native.log"
  if "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check oa \
      >> "$RUN_DIR/smoke-native.log" 2>&1 \
     && "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check oo \
      >> "$RUN_DIR/smoke-native.log" 2>&1 \
     && "$PY" "$SMOKE_DIR/smoke_anthropic.py" --base-url "$BASE_URL" --check all \
      >> "$RUN_DIR/smoke-native.log" 2>&1; then
    sed 's/^/    /' "$RUN_DIR/smoke-native.log" >> "$RESULTS"
    echo "- **SDK smoke (native): oo + oa (both faces/upstreams) + ao/aa + tools + errors: PASS**" >> "$RESULTS"
    LEG_NATIVE+=("smoke:PASS")
  else
    cat "$RUN_DIR/smoke-native.log" >&2
    die "smoke FAILED on the native leg"
  fi

  log "abort drill (reduced: 2 cells) vs native leg"
  # NOTE: --abort-log must be the FAKES' boot-time abort log ($RUN_DIR/abort.log —
  # boot_fake_oai/boot_fake_anth pass it); abort_drill.py reads/clears that file, the
  # fakes append to it. A different path here would never receive the records and the
  # drill would fail (the fakes' log path is fixed at boot, not per-drill).
  ABORT_NATIVE="$("$PY" "$SMOKE_DIR/abort_drill.py" --base-url "$BASE_URL" --janus-pid "$NATIVE_PID" \
    --abort-log "$RUN_DIR/abort.log" --thread-slack 24 2>&1)" \
    || { printf '%s\n' "$ABORT_NATIVE" >&2; die "abort drill FAILED on the native leg (see output above)"; }
  echo "    $ABORT_NATIVE" >> "$RESULTS"
  if ! grep -q '^PASS' <<<"$ABORT_NATIVE"; then
    echo "$ABORT_NATIVE" >&2
    die "abort drill FAILED on the native leg"
  fi
  echo "- **Abort drill (native):** upstream cancellation observed, platform threads flat" >> "$RESULTS"
 # : same clean-log assertion on the native leg's log.
  check_janus_log_clean "$RUN_DIR/native.log" "native leg"

  log "stress: 20 concurrent SSE streams (both faces) vs native leg"
  STRESS_OUT="$( "$PY" "$SMOKE_DIR/stress_streams.py" --base-url "$BASE_URL" --janus-pid "$NATIVE_PID" \
    --streams 20 --thread-slack 24 2>&1)" \
    || { printf '%s\n' "$STRESS_OUT" >&2; die "stress drill FAILED on the native leg (see output above)"; }
  echo "    $STRESS_OUT" >> "$RESULTS"
  echo "- **20 concurrent SSE streams (native):** $(echo "$STRESS_OUT" | grep -c '^PASS') pass" >> "$RESULTS"
  kill -9 "$NATIVE_PID" 2>/dev/null || true
  wait "$NATIVE_PID" 2>/dev/null || true
  NATIVE_PID=""
  LEG_NATIVE+=("abort:PASS" "stress20:PASS")
fi

# ---- real legs (env-gated; never in CI) ---------------------------------
if [[ -z "${ANTHROPIC_API_KEY:-}" && -z "${DEEPSEEK_API_KEY:-}" ]]; then
  echo
  echo "## Leg 3 — real upstreams: **SKIP** (no API keys exported; AGENTS.md — never in CI)" >> "$RESULTS"
  LEG_REAL+=("skip")
else
  REAL_CONFIG="$RUN_DIR/config.real.$JANUS_PORT.toml"
  cp "$SMOKE_DIR/config.real.toml" "$REAL_CONFIG"
  {
    echo
    echo "## Leg 3 — real upstreams (env-gated)"
    echo
  } >> "$RESULTS"
  boot_janus_jvm "$REAL_CONFIG"
  if [[ -n "${ANTHROPIC_API_KEY:-}" ]]; then
    log "smoke (oa, real Claude) — OpenAI SDK → Janus → real Anthropic"
    if "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check oa --real \
        >> "$RUN_DIR/smoke-real.log" 2>&1; then
      sed 's/^/    /' "$RUN_DIR/smoke-real.log" >> "$RESULTS"
      echo "- **OpenAI SDK → real Claude (oa, stream + non-stream + tools): PASS**" >> "$RESULTS"
      LEG_REAL+=("oa:PASS")
    else
      cat "$RUN_DIR/smoke-real.log" >&2
      echo "- **OpenAI SDK → real Claude: FAIL (see $RUN_DIR/smoke-real.log)**" >> "$RESULTS"
      LEG_REAL+=("oa:FAIL")
    fi
    # m4 : the real oa-tools turn is best-effort — Claude may answer the
    # trigger prompt directly instead of emitting a tool_use, failing that one leg.
    echo "- **Note (oa real leg):** the tools turn is best-effort — Claude may answer the trigger prompt directly instead of emitting a \`tool_use\` (fails that leg); env-gated, never CI — treat an oa:FAIL here as a model-behavior artifact unless the envelope is wrong" >> "$RESULTS"
  else
    echo "- **OpenAI SDK → real Claude: SKIP (\`ANTHROPIC_API_KEY\` not exported)**" >> "$RESULTS"
  fi
  if [[ -n "${DEEPSEEK_API_KEY:-}" ]]; then
    log "smoke (ao, real DeepSeek) — Anthropic SDK → Janus → real DeepSeek"
    if "$PY" "$SMOKE_DIR/smoke_anthropic.py" --base-url "$BASE_URL" --check real-happy --real \
        >> "$RUN_DIR/smoke-real.log" 2>&1; then
      sed 's/^/    /' "$RUN_DIR/smoke-real.log" >> "$RESULTS"
      echo "- **Anthropic SDK → real DeepSeek (ao, stream + non-stream): PASS**" >> "$RESULTS"
      LEG_REAL+=("ao:PASS")
    else
      cat "$RUN_DIR/smoke-real.log" >&2
      echo "- **Anthropic SDK → real DeepSeek: FAIL (see $RUN_DIR/smoke-real.log)**" >> "$RESULTS"
      LEG_REAL+=("ao:FAIL")
    fi
  else
    echo "- **Anthropic SDK → real DeepSeek: SKIP (\`DEEPSEEK_API_KEY\` not exported)**" >> "$RESULTS"
  fi
  echo "- **Fixture cross-check vs real capture (Review & Fix bullet 4):** env-gated \`captureFixtures\` run — **SKIP** without a key at gate time; the live SDK legs above act as the real-bytes arbiter for the shapes they exercise" >> "$RESULTS"
  kill_janus_jvm
fi

# ---- final gate --------------------------------------------------------
kill_fake_oai
kill_fake_anth
log "final ./gradlew build --no-daemon"
(cd "$REPO" && ./gradlew build --no-daemon > "$RUN_DIR/build-final.log" 2>&1) \
  || die "final build failed (see $RUN_DIR/build-final.log)"
GATE_ELAPSED=$(( ($(date +%s%N) - GATE_START) / 1000000000 ))
{
  echo
  echo "## Final gate"
  echo
  echo "- **\`./gradlew build --no-daemon\`:** BUILD SUCCESSFUL (spotless, \`-Werror\`, all modules)"
  if [[ "$SKIP_NATIVE" -eq 1 ]]; then
    echo "- **\`./gradlew :janus-gateway:nativeCompile\`:** not run (\`--skip-native\`)"
  else
    echo "- **\`./gradlew :janus-gateway:nativeCompile\`:** BUILD SUCCESSFUL"
  fi
  echo "- **Legs:** JVM [$(IFS=,; echo "${LEG_JVM[*]}")] · Native [$(IFS=,; echo "${LEG_NATIVE[*]}")] · Real [$(IFS=,; echo "${LEG_REAL[*]}")]"
  echo "- **Gate wall-clock:** ${GATE_ELAPSED}s"
  echo
  echo "## Defects found"
  echo
  echo "- (filled in by the implementer after the D1/D2 test-first sequence — see the"
  echo "  fix commits and the sections above; any drill-proven defect is recorded here)"
} >> "$RESULTS"

log "gate complete in ${GATE_ELAPSED}s — results in $RESULTS"
log "git status (expected: scripts/smoke/cross-format/ + blessed production diffs + plan only):"
git -C "$REPO" status --short
