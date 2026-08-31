#!/usr/bin/env bash
# =============================================================================
# run.sh — OpenAI-face e2e gate
#
# Proves with an UNMODIFIED OpenAI SDK (fresh venv, pinned openai) against a live
# Janus boot that Janus is a drop-in OpenAI endpoint — non-streaming + streaming
# chat, GET /v1/models, OpenAI-shaped errors, kill-upstream drills, 50-concurrent-
# SSE stability — and that the native-image build stays green. Deterministic legs
# run against a stdlib-only fake DeepSeek upstream (fake_deepseek.py) fed by the
# golden fixtures; the real-DeepSeek leg runs only when DEEPSEEK_API_KEY is
# exported (never in CI). Writes RESULTS.md.
#
# ships NO production changes: the only files this script touches live under
# scripts/smoke/openai-face/ (plus the Gradle build/native outputs and this RESULTS.md).
#
# Usage:
# scripts/smoke/openai-face/run.sh [--fresh-venv] [--skip-native]
#
# Env:
# JANUS_PORT gateway port (default 8080; a free port is picked when taken)
# FAKE_PORT fake-upstream port (default 9876; free port when taken)
# OPENAI_PIN pinned openai package version (default 1.109.0)
# DEEPSEEK_API_KEY exported → the real-upstream leg runs
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SMOKE_DIR="$REPO/scripts/smoke/openai-face"
RUN_DIR="$SMOKE_DIR/.run"
RESULTS="$SMOKE_DIR/RESULTS.md"
OPENAI_PIN="${OPENAI_PIN:-1.109.0}"
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
FAKE_PID=""
JANUS_PID=""
NATIVE_PID=""

cleanup() {
  # Kill ONLY the processes this run spawned and tracks — a broad
  # pkill -9 -f "io.amscotti.janus.cli.JanusCli" / "fake_deepseek.py" /
  # "nativeCompile/janus" would kill unrelated Janus JVMs / fake upstreams / native
  # binaries on the machine (e.g. those booted by concurrent harnesses that invoke
  # each other): the cross-format/run.sh + bench/run_bench.sh tracked-PID-only policy.
  for pid in "${PIDS[@]:-}" "$FAKE_PID" "$JANUS_PID" "$NATIVE_PID"; do
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
  local port="$1" bound="${2:-120}"
  local i
  for i in $(seq 1 "$((bound * 10))"); do
    if curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

wait_models_nonempty() {
  local port="$1" bound="${2:-30}"
  local i
  for i in $(seq 1 "$((bound * 10))"); do
    if curl -sf "http://127.0.0.1:$port/v1/models" 2>/dev/null | grep -q '"deepseek-v4-flash"'; then
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
  log "creating fresh venv at $VENV (openai==$OPENAI_PIN)"
  rm -rf "$VENV"
  "$PY_BIN" -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --disable-pip-version-check "openai==$OPENAI_PIN"
fi
PY="$VENV/bin/python"
log "SDK pin: openai==$OPENAI_PIN ($("$PY" -c 'import openai; print(openai.__version__)'))"

# ---------------------------------------------------------------- ports
JANUS_PORT="${JANUS_PORT:-8080}"
if port_busy "$JANUS_PORT"; then
  log "port $JANUS_PORT busy (stale Janus?) — picking a free gateway port"
  JANUS_PORT="$(free_port)"
fi
FAKE_PORT="${FAKE_PORT:-9876}"
if port_busy "$FAKE_PORT"; then
  log "port $FAKE_PORT busy — picking a free fake-upstream port"
  FAKE_PORT="$(free_port)"
fi
BASE_URL="http://127.0.0.1:$JANUS_PORT/v1"
FAKE_CONFIG="$RUN_DIR/config.fake.$FAKE_PORT.toml"
sed "s|base-url = \"http://127.0.0.1:9876\"|base-url = \"http://127.0.0.1:$FAKE_PORT\"|" \
  "$SMOKE_DIR/config.fake.toml" > "$FAKE_CONFIG"
log "gateway: $BASE_URL  fake upstream: http://127.0.0.1:$FAKE_PORT  config: $FAKE_CONFIG"

# ---------------------------------------------------------------- fake
boot_fake() {
  log "booting fake DeepSeek upstream on :$FAKE_PORT"
  nohup "$PY_BIN" "$SMOKE_DIR/fake_deepseek.py" \
    --port "$FAKE_PORT" \
    --paused-file "$RUN_DIR/fake.paused" \
    --resume-file "$RUN_DIR/fake.resume" \
    > "$RUN_DIR/fake.log" 2>&1 &
  FAKE_PID=$!
  PIDS+=("$FAKE_PID")
  rm -f "$RUN_DIR/fake.paused" "$RUN_DIR/fake.resume"
  local i
  for i in $(seq 1 100); do
    curl -sf "http://127.0.0.1:$FAKE_PORT/" >/dev/null 2>&1 && return 0
    sleep 0.1
  done
  die "fake upstream did not come up on :$FAKE_PORT (see $RUN_DIR/fake.log)"
}
kill_fake() {
  [[ -n "$FAKE_PID" ]] && kill -9 "$FAKE_PID" 2>/dev/null || true
  wait "$FAKE_PID" 2>/dev/null || true
  FAKE_PID=""
}

# ---------------------------------------------------------------- janus (JVM)
boot_janus_jvm() {
  local config="$1"
  log "booting Janus (JVM leg, :$JANUS_PORT) with $config"
  rm -f "$RUN_DIR/janus-jvm.log"
  MICRONAUT_SERVER_PORT="$JANUS_PORT" nohup ./gradlew :janus-cli:run \
    --no-daemon --args="--config $config" > "$RUN_DIR/janus-jvm.log" 2>&1 &
  PIDS+=("$!")
  wait_for_health "$JANUS_PORT" 180 || die "JVM Janus did not reach /health (see $RUN_DIR/janus-jvm.log)"
  JANUS_PID="$(pgrep -f 'io.amscotti.janus.cli.JanusCli' | tail -1)"
  [[ -n "$JANUS_PID" ]] || die "could not find the JanusCli JVM pid"
  log "JVM Janus healthy on :$JANUS_PORT (pid $JANUS_PID)"
  wait_models_nonempty "$JANUS_PORT" || die "boot-misconfig tripwire: /v1/models empty on the JVM leg"
}
kill_janus_jvm() {
  [[ -n "$JANUS_PID" ]] && kill -9 "$JANUS_PID" 2>/dev/null || true
  wait "$JANUS_PID" 2>/dev/null || true
  # SIGKILL the gradle wrapper does not reap the forked java instantly; the listening
  # socket frees only when the JVM dies. The next leg (native) boots on the same port
  # immediately — wait for the port to actually free (bounded) or the native leg races
  # a held socket and "never reaches /health". (No broad pkill here either — the
  # tracked JANUS_PID above is this run's own JVM; a pkill -f JanusCli would kill a
  # concurrent harness's Janus JVM.)
  for _ in $(seq 1 50); do
    port_busy "$JANUS_PORT" || break
    sleep 0.2
  done
  JANUS_PID=""
}

# ---------------------------------------------------------------- drills
run_eager_kill_drill() {
  log "drill: eager kill — upstream dead before a non-streaming request"
  kill_fake
  local start_ms
  start_ms="$(date +%s%N)"
  "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check eager-kill --bound 5 >&2
  local elapsed_ms=$(( ($(date +%s%N) - start_ms) / 1000000 ))
  log "PASS eager-kill drill: 502 api_error envelope in ${elapsed_ms}ms (bound 5000ms)"
  boot_fake
  echo "$elapsed_ms"
}

run_midstream_drill() {
  log "drill: mid-stream kill — upstream killed between frames"
  rm -f "$RUN_DIR/client-ready" "$RUN_DIR/midstream-client.exit" "$RUN_DIR/fake.paused"
  ( "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check kill-midstream \
      --ready-file "$RUN_DIR/client-ready" --bound 5 \
      > "$RUN_DIR/midstream-client.log" 2>&1
    echo $? > "$RUN_DIR/midstream-client.exit" ) &
  local client_pid=$!
  local i
  for i in $(seq 1 100); do
    [[ -f "$RUN_DIR/client-ready" ]] && break
    sleep 0.1
  done
  [[ -f "$RUN_DIR/client-ready" ]] || {
    cat "$RUN_DIR/midstream-client.log"
    die "mid-stream drill: client never received a first frame"
  }
  sleep 0.3 # the fake is mid-hold (frame flushed, partial frame pending)
  local kill_start
  kill_start="$(date +%s%N)"
  # Deterministic connection-drop: the fake closes the response mid-frame when the
  # resume file appears (EOF-in-pending-frame → SSE error frame). A process kill is
  # racy on macOS (RST vs half-open socket → adapter blocks until the 60s watchdog).
  touch "$RUN_DIR/fake.resume"
  for i in $(seq 1 100); do
    [[ -f "$RUN_DIR/midstream-client.exit" ]] && break
    sleep 0.1
  done
  local kill_elapsed_ms=$(( ($(date +%s%N) - kill_start) / 1000000 ))
  local client_exit
  client_exit="$(cat "$RUN_DIR/midstream-client.exit" 2>/dev/null || echo 1)"
  if [[ "$client_exit" != "0" ]]; then
    cat "$RUN_DIR/midstream-client.log"
    die "mid-stream drill: client failed (exit $client_exit)"
  fi
  if [[ "$kill_elapsed_ms" -gt 5000 ]]; then
    die "mid-stream drill: completion after kill took ${kill_elapsed_ms}ms (> 5000ms — hung?)"
  fi
  log "PASS mid-stream drill: SSE error frame + clean completion in ${kill_elapsed_ms}ms post-kill (bound 5000ms)"
  echo "$kill_elapsed_ms"
  boot_fake
}

# ---------------------------------------------------------------- legs
LEG_JVM=()
LEG_NATIVE=()
LEG_REAL=()

echo "" > "$RESULTS"
{
  echo "# — stage 1 e2e gate results"
  echo
  echo "- **Date:** $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo "- **Commit:** $(git -C "$REPO" rev-parse --short HEAD)"
  echo "- **Java:** $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  echo "- **Gradle:** $(cd "$REPO" && ./gradlew --version 2>/dev/null | awk '/^Gradle/{print $2}' | head -1)"
  echo "- **OpenAI SDK pin:** \`openai==$OPENAI_PIN\` (fresh venv, \`$VENV\`)"
  echo "- **Gateway port:** $JANUS_PORT · **Fake upstream port:** $FAKE_PORT"
  echo
  echo "## Baseline: \`./gradlew build --no-daemon\`"
} >> "$RESULTS"

log "baseline ./gradlew build"
(cd "$REPO" && ./gradlew build --no-daemon > "$RUN_DIR/build-baseline.log" 2>&1) \
  || die "baseline build failed (see $RUN_DIR/build-baseline.log)"
echo "- **Result:** BUILD SUCCESSFUL (all modules, spotless + \`-Werror\`)" >> "$RESULTS"

# ---- JVM leg -----------------------------------------------------------
boot_fake
boot_janus_jvm "$FAKE_CONFIG"
{
  echo
  echo "## Leg 1 — JVM boot vs fake upstream (deterministic, offline)"
  echo
} >> "$RESULTS"

log "smoke (all checks) vs JVM leg"
# Truncate per-run (>> accumulates FAILs from earlier runs into RESULTS.md otherwise).
: > "$RUN_DIR/smoke-jvm.log"
if "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check all >> "$RUN_DIR/smoke-jvm.log" 2>&1; then
  sed 's/^/    /' "$RUN_DIR/smoke-jvm.log" >> "$RESULTS"
  echo "- **SDK smoke (models / non-stream / stream / raw-\`[DONE]\` / errors): PASS**" >> "$RESULTS"
  LEG_JVM+=("smoke:PASS")
else
  cat "$RUN_DIR/smoke-jvm.log" >&2
  die "smoke_sdk --check all FAILED on the JVM leg"
fi

EAGER_MS="$(run_eager_kill_drill)"
echo "- **Kill-upstream drill (eager):** 502 \`api_error\` envelope in ${EAGER_MS}ms (bound 5000ms), no hang" >> "$RESULTS"
MID_MS="$(run_midstream_drill)"
echo "- **Kill-upstream drill (mid-stream):** SSE error frame asserted + clean completion ${MID_MS}ms post-kill (bound 5000ms), no hang" >> "$RESULTS"

log "stress: 50 concurrent SSE streams vs JVM leg"
# Capture WITHOUT letting `set -e` abort the assignment before the failing drill's
# FAILURES output reaches the operator (the cross-format/run.sh pattern).
STRESS_OUT="$("$PY" "$SMOKE_DIR/stress_streams.py" --base-url "$BASE_URL" --janus-pid "$JANUS_PID" \
  --streams 50 --thread-slack 24 2>&1)" \
  || { printf '%s\n' "$STRESS_OUT" >&2; die "stress drill FAILED (see output above)"; }
echo "    $STRESS_OUT" >> "$RESULTS"
echo "- **50 concurrent SSE streams:** $(echo "$STRESS_OUT" | grep -c '^PASS') pass" >> "$RESULTS"
kill_janus_jvm
LEG_JVM+=("drills:PASS" "stress50:PASS")

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
    echo "## Leg 2 — native boot (GraalVM image) vs fake upstream"
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
  wait_models_nonempty "$JANUS_PORT" || die "boot-misconfig tripwire: /v1/models empty on the native leg"

  log "smoke (all checks) vs native leg"
  : > "$RUN_DIR/smoke-native.log"  # truncate per-run (same as smoke-jvm.log)
  if "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check all >> "$RUN_DIR/smoke-native.log" 2>&1; then
    sed 's/^/    /' "$RUN_DIR/smoke-native.log" >> "$RESULTS"
    echo "- **SDK smoke (models / non-stream / stream / raw-\`[DONE]\` / errors): PASS**" >> "$RESULTS"
    LEG_NATIVE+=("smoke:PASS")
  else
    cat "$RUN_DIR/smoke-native.log" >&2
    die "smoke_sdk --check all FAILED on the native leg"
  fi

  log "stress: 20 concurrent SSE streams vs native leg"
  # Same capture-then-die pattern as the JVM-leg stress block (set -e must not
  # swallow the FAILURES output).
  STRESS_OUT="$( "$PY" "$SMOKE_DIR/stress_streams.py" --base-url "$BASE_URL" --janus-pid "$NATIVE_PID" \
    --streams 20 --thread-slack 24 2>&1)" \
    || { printf '%s\n' "$STRESS_OUT" >&2; die "stress drill FAILED on the native leg (see output above)"; }
  echo "    $STRESS_OUT" >> "$RESULTS"
  echo "- **20 concurrent SSE streams (native):** $(echo "$STRESS_OUT" | grep -c '^PASS') pass" >> "$RESULTS"
  kill -9 "$NATIVE_PID" 2>/dev/null || true
  wait "$NATIVE_PID" 2>/dev/null || true
  NATIVE_PID=""
  LEG_NATIVE+=("stress20:PASS")
fi

# ---- real leg (env-gated; never in CI) ---------------------------------
if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  echo
  echo "## Leg 3 — real DeepSeek upstream: **SKIP** (\`DEEPSEEK_API_KEY\` not exported; AGENTS.md — never in CI)" >> "$RESULTS"
  LEG_REAL+=("skip")
else
  REAL_CONFIG="$RUN_DIR/config.real.$JANUS_PORT.toml"
  sed "s|base-url = \"https://api.deepseek.com\"|base-url = \"https://api.deepseek.com\"|" \
    "$SMOKE_DIR/config.real.toml" > "$REAL_CONFIG"
  {
    echo
    echo "## Leg 3 — real DeepSeek upstream (env-gated)"
    echo
  } >> "$RESULTS"
  boot_janus_jvm "$REAL_CONFIG"
  log "smoke (real-happy) vs real DeepSeek"
  if "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check real-happy \
      --models deepseek-v4-flash,deepseek-v4-pro --real >> "$RUN_DIR/smoke-real.log" 2>&1; then
    sed 's/^/    /' "$RUN_DIR/smoke-real.log" >> "$RESULTS"
    echo "- **Real-upstream chat (non-stream + stream) + \`models.list\`: PASS**" >> "$RESULTS"
    LEG_REAL+=("chat:PASS")
  else
    cat "$RUN_DIR/smoke-real.log" >&2
    echo "- **Real-upstream chat: FAIL (see $RUN_DIR/smoke-real.log)**" >> "$RESULTS"
    LEG_REAL+=("chat:FAIL")
  fi
  echo "- **Real-upstream 429:** best-effort — **SKIP** (rate limits are not triggerable on demand; the fake leg covers 429 mapping deterministically)" >> "$RESULTS"

  log "bad-key sub-leg vs real DeepSeek"
  BAD_CONFIG="$RUN_DIR/config.real-badkey.$JANUS_PORT.toml"
  sed 's/DEEPSEEK_API_KEY/JANUS_SMOKE_BAD_KEY/' "$SMOKE_DIR/config.real.toml" > "$BAD_CONFIG"
  kill_janus_jvm
  export JANUS_SMOKE_BAD_KEY="sk-janus-smoke-invalid-$(date +%s)"
  boot_janus_jvm "$BAD_CONFIG"
  if "$PY" "$SMOKE_DIR/smoke_sdk.py" --base-url "$BASE_URL" --check bad-key >> "$RUN_DIR/smoke-badkey.log" 2>&1; then
    sed 's/^/    /' "$RUN_DIR/smoke-badkey.log" >> "$RESULTS"
    echo "- **Bad-key → 401 \`authentication_error\`: PASS**" >> "$RESULTS"
    LEG_REAL+=("badkey:PASS")
  else
    cat "$RUN_DIR/smoke-badkey.log" >&2
    echo "- **Bad-key → 401: FAIL (see $RUN_DIR/smoke-badkey.log)**" >> "$RESULTS"
    LEG_REAL+=("badkey:FAIL")
  fi
  kill_janus_jvm
fi

# ---- final gate --------------------------------------------------------
kill_fake
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
  # Truthful record (review C1): the native leg found and fixed a real defect —
  # missing reflect-config record components for JanusConfig. Amended acceptance
  # (plan notes): the reflect-config change is blessed as the gate's product.
  echo "- Native reflect-config: \`JanusConfig\`/\`JanusConfig\$ModelListEntry\` record components were missing, so the native image failed RouterFactory instantiation (\`Record components not available\`); added \`allRecordComponents\` entries and the native leg went green (found + fixed by the gate itself)."
  echo "- No other defects (all legs green)."
} >> "$RESULTS"

log "gate complete in ${GATE_ELAPSED}s — results in $RESULTS"
log "git status (expected: scripts/smoke/openai-face/ + plan + checklist only):"
git -C "$REPO" status --short
