#!/usr/bin/env bash
# =============================================================================
# run.sh — System One decisions e2e gate (docs/system-one.md)
#
# Boots Janus (JVM; add --native to also prove the GraalVM image — the
# ServiceLoader registration + reflect-configs only bite in native) against
# two stdlib fake decision upstreams (fake_decision_upstream.py serves
# /v1/systemone + /v1/decisions) with governance ON and proves:
#   drop-in   — a Jev-shaped request passes through semantically unchanged
#               and returns the Jev shape; exact cost 2 micro-USD
#               (42 tokens x $0.042/Mtok = 1.764, per-term ceiling)
#   auth      — keyless 401; wrong-scope virtual key 403 before dispatch
#   translate — gpt-6-luna: upstream receives the OpenAI Decisions shape,
#               the client still receives the Jev shape
#   failover  — pooled alias walks past a 503 entry (upstream-model remap
#               evidence in the fake logs) to the healthy entry
#   errors    — unknown alias 404 model_not_found; malformed 400
#   budget    — over-cap key denied 429 BEFORE dispatch (fake log flat);
#               tpm=1 key denied pre-dispatch
#   images    — non-vision alias 400 before dispatch; vision alias forwards
#               a data-URL input_image part (deterministic capability gate)
#   metrics   — face="systemone" series; state marker absent (privacy)
#
# Live leg (opt-in, NEVER part of the offline gate): JANUS_LIVE=1 additionally
# replays the Jev body against a local Ollama decision model on :11434
# (model in $OLLAMA_DECISION_MODEL, default tev1:0.8b) directly, as a
# wire-shape sanity check. Without JANUS_LIVE=1 the leg is reported SKIP —
# the gate stays offline/fake-upstream only (the AGENTS.md contract).
#
# Usage: scripts/smoke/decisions/run.sh [--keep] [--native]
# Env: JANUS_PORT (default 18081), FAKE_PORT (9871), FAKE2_PORT (9872),
#      JANUS_LIVE (0/1), OLLAMA_DECISION_MODEL (tev1:0.8b)
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO"
SMOKE_DIR="$REPO/scripts/smoke/decisions"
RUN_DIR="$SMOKE_DIR/.run"
RESULTS="$SMOKE_DIR/RESULTS.md"

log() { printf '[gate] %s\n' "$*" >&2; }
die() { printf '[gate] FATAL: %s\n' "$*" >&2; exit 1; }

KEEP=0
NATIVE=0
for arg in "$@"; do
  case "$arg" in
    --keep) KEEP=1 ;;
    --native) NATIVE=1 ;;
    *) die "unknown flag: $arg (expected --keep or --native)" ;;
  esac
done

export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"
PY_BIN="${PY_BIN:-python3}"

free_port() {
  "$PY_BIN" - "$1" <<'PY'
import socket, sys
port = int(sys.argv[1])
for candidate in range(port, port + 50):
    with socket.socket() as s:
        try:
            s.bind(("127.0.0.1", candidate))
        except OSError:
            continue
        print(candidate)
        raise SystemExit(0)
raise SystemExit(1)
PY
}

JANUS_PORT="$(free_port "${JANUS_PORT:-18081}")" || die "no free Janus port"
FAKE_PORT="$(free_port "${FAKE_PORT:-9871}")" || die "no free fake port"
# Start the second search past the first pick — free_port does not reserve.
FAKE2_PORT="$(free_port "$((${FAKE2_PORT:-9872} > FAKE_PORT ? ${FAKE2_PORT:-9872} : FAKE_PORT + 1))")" || die "no free fake2 port"

mkdir -p "$RUN_DIR"
PIDS=()
cleanup() {
  for pid in "${PIDS[@]:-}"; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done
  # Gradle `run` forks the app JVM; reap the run-scoped process group by the
  # exact config path only (never a broad pattern).
  pkill -f "smoke/decisions/.run/config.toml" 2>/dev/null || true
}
trap cleanup EXIT

wait_http() { # url, seconds — any HTTP response (even 401) proves "up"
  local url="$1" deadline=$((SECONDS + ${2:-60}))
  while (( SECONDS < deadline )); do
    curl -s -o /dev/null -m 3 "$url" && return 0
    sleep 2
  done
  return 1
}

# ---------------------------------------------------------------- fakes
log "starting fake decision upstreams on :$FAKE_PORT and :$FAKE2_PORT"
"$PY_BIN" "$SMOKE_DIR/fake_decision_upstream.py" --port "$FAKE_PORT" --log-file "$RUN_DIR/fake.jsonl" &
PIDS+=("$!")
"$PY_BIN" "$SMOKE_DIR/fake_decision_upstream.py" --port "$FAKE2_PORT" --log-file "$RUN_DIR/fake2.jsonl" &
PIDS+=("$!")
wait_http "http://127.0.0.1:$FAKE_PORT/v1/models" 15 || die "fake upstream did not start"
wait_http "http://127.0.0.1:$FAKE2_PORT/v1/models" 15 || die "second fake upstream did not start (failover leg would flap)"

# ---------------------------------------------------------------- janus
sed -e "s/__FAKE_PORT__/$FAKE_PORT/g" -e "s/__FAKE2_PORT__/$FAKE2_PORT/g" \
  "$SMOKE_DIR/config.fake.toml" > "$RUN_DIR/config.toml"
export JANUS_MASTER_KEY="decisions-gate-master-DISTINCTIVE-7f3a"
log "booting Janus on :$JANUS_PORT (JVM leg)"
MICRONAUT_SERVER_PORT="$JANUS_PORT" nohup ./gradlew :janus-cli:run --no-daemon \
  --args="--config $RUN_DIR/config.toml" > "$RUN_DIR/janus.log" 2>&1 &
PIDS+=("$!")
wait_http "http://127.0.0.1:$JANUS_PORT/v1/models" 240 || { tail -n 40 "$RUN_DIR/janus.log" >&2; die "Janus did not start"; }

# ---------------------------------------------------------------- drill
JANUS_BASE="http://127.0.0.1:$JANUS_PORT" \
FAKE_LOG="$RUN_DIR/fake.jsonl" FAKE2_LOG="$RUN_DIR/fake2.jsonl" \
  "$PY_BIN" "$SMOKE_DIR/drill_decisions.py" 2>&1 | tee "$RUN_DIR/drill.log"
grep -q 'DECISIONS DRILL PASS' "$RUN_DIR/drill.log" || die "drill did not pass"

# Privacy sweep: the master key and state marker must not appear in Janus logs.
sweep_log() {
  local log_file="$1"
  [[ -f "$log_file" ]] || return 0
  if grep -q 'MARKER-STATE-7f3a' "$log_file"; then
    die "PRIVACY VIOLATION: state marker in $log_file"
  fi
  if grep -q "$JANUS_MASTER_KEY" "$log_file"; then
    die "SECURITY VIOLATION: master key in $log_file"
  fi
}
sweep_log "$RUN_DIR/janus.log"
log "privacy sweep clean (no state marker, no master key in Janus log)"

# ---------------------------------------------------------------- optional native leg (--native)
# The decision ServiceLoader registration and the new reflect-config entries
# are only exercised for real in an image (a JVM-only gate proves neither) —
# the cross-format harness's pattern, opt-in because nativeCompile is slow.
NATIVE_LEG="SKIP (--native not requested)"
if (( NATIVE == 1 )); then
  log "building the GraalVM image (:janus-gateway:nativeCompile)"
  ./gradlew :janus-gateway:nativeCompile --no-daemon > "$RUN_DIR/native-build.log" 2>&1 \
    || die "nativeCompile failed (see $RUN_DIR/native-build.log)"
  BIN="$REPO/janus-gateway/build/native/nativeCompile/janus"
  [[ -x "$BIN" ]] || die "native binary missing: $BIN"
  NATIVE_PORT="$(free_port $((JANUS_PORT + 1)))" || die "no free native port"
  log "booting native Janus on :$NATIVE_PORT and replaying the drill against it"
  MICRONAUT_CONFIG_FILES="$RUN_DIR/config.toml" MICRONAUT_SERVER_PORT="$NATIVE_PORT" \
    nohup "$BIN" > "$RUN_DIR/native.log" 2>&1 &
  PIDS+=("$!")
  wait_http "http://127.0.0.1:$NATIVE_PORT/v1/models" 120 \
    || { tail -n 40 "$RUN_DIR/native.log" >&2; die "native Janus did not start"; }
  # The native leg must prove its own upstream calls: the JVM leg's fake-log
  # entries would otherwise satisfy the failover/translation assertions.
  : > "$RUN_DIR/fake.jsonl"
  : > "$RUN_DIR/fake2.jsonl"
  JANUS_BASE="http://127.0.0.1:$NATIVE_PORT" \
  FAKE_LOG="$RUN_DIR/fake.jsonl" FAKE2_LOG="$RUN_DIR/fake2.jsonl" \
    "$PY_BIN" "$SMOKE_DIR/drill_decisions.py" 2>&1 | tee "$RUN_DIR/drill-native.log"
  grep -q 'DECISIONS DRILL PASS' "$RUN_DIR/drill-native.log" || die "native drill did not pass"
  sweep_log "$RUN_DIR/native.log"
  NATIVE_LEG="PASS (drill green against the GraalVM image; native log privacy-swept)"
fi
log "native leg: $NATIVE_LEG"

# ---------------------------------------------------------------- optional live leg (JANUS_LIVE=1, never part of the offline gate)
OLLAMA_MODEL="${OLLAMA_DECISION_MODEL:-tev1:0.8b}"
OLLAMA_LEG="SKIP (offline gate — set JANUS_LIVE=1 for the local Ollama leg)"
if [[ "${JANUS_LIVE:-0}" == "1" ]]; then
  if curl -s -m 3 http://127.0.0.1:11434/api/tags | grep -q '"decision"' \
     && curl -s -m 3 http://127.0.0.1:11434/api/tags | grep -q "\"$OLLAMA_MODEL\""; then
    log "live leg: replaying Jev body against $OLLAMA_MODEL directly"
    OLLAMA_OUT="$(curl -s -m 180 http://127.0.0.1:11434/v1/systemone -H 'Content-Type: application/json' -d "{
      \"model\":\"$OLLAMA_MODEL\",\"state\":\"I was charged twice.\",
      \"questions\":{\"refund\":{\"type\":\"noul\",\"instructions\":\"Is the customer asking for money back?\"}}}")"
    echo "$OLLAMA_OUT" | grep -q '"type": "noul"\|"type":"noul"' \
      && OLLAMA_LEG="PASS ($OLLAMA_MODEL answered a Jev-shaped request on /v1/systemone)" \
      || OLLAMA_LEG="FAIL ($OLLAMA_MODEL response was not Jev-shaped: $OLLAMA_OUT)"
  else
    OLLAMA_LEG="SKIP (JANUS_LIVE=1 but no $OLLAMA_MODEL with the decision capability on :11434)"
  fi
fi
log "live Ollama leg: $OLLAMA_LEG"
[[ "$OLLAMA_LEG" == FAIL* ]] && die "live Ollama leg failed (JANUS_LIVE=1)"

# ---------------------------------------------------------------- results
{
  echo "# Decisions smoke gate — RESULTS"
  echo
  echo "Run: $(date -u +%Y-%m-%dT%H:%M:%SZ) · ports janus=$JANUS_PORT fake=$FAKE_PORT fake2=$FAKE2_PORT"
  echo
  sed 's/^/- /' "$RUN_DIR/drill.log"
  if [[ -f "$RUN_DIR/drill-native.log" ]]; then
    sed 's/^/- [native] /' "$RUN_DIR/drill-native.log"
  fi
  echo "- [ok] privacy sweep clean (no state marker, no master key in Janus log)"
  echo "- native leg: $NATIVE_LEG"
  echo "- live Ollama leg: $OLLAMA_LEG"
  echo
  if [[ "$NATIVE_LEG" == PASS* && "$OLLAMA_LEG" == PASS* ]]; then
    echo "Verdict: **PASS** (JVM, native, and live legs green; see drill output above)."
  elif [[ "$NATIVE_LEG" == PASS* ]]; then
    echo "Verdict: **PASS (JVM + native)** — live Ollama leg not run; see the per-leg lines above."
  else
    echo "Verdict: **PASS (JVM gate only)** — native/live legs not run; see the per-leg lines above."
  fi
} > "$RESULTS"

(( KEEP == 1 )) || rm -rf "$RUN_DIR"
log "DECISIONS GATE PASS (RESULTS.md written)"
