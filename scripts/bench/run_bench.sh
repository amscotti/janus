#!/usr/bin/env bash
# =============================================================================
# run_bench.sh — packaging benchmark orchestrator (scripts/bench/
# only; test-only — no production sources, no dependency changes).
#
# Same box, same client, same mock for every leg (the packaging Review bullet):
# profile: warm-up request, then 1000 req / 10 concurrent non-streaming chat
# completions (bench_client.py — hey > wrk > ab > curl-loop precedence,
# the T234 order; the producing tool is NAMED per number);
# streaming counted SEPARATELY: stream_bench.py (N concurrent SSE, [DONE] count
# + frame integrity) — never mixed into the throughput benches;
# startup cold+warm + RSS post-warmup (startup_rss.py; macOS /usr/bin/time -l
# max-RSS for the native cold spawn; ps sampling for every leg).
#
# Legs: JVM (janus-cli --config), JVM+Postgres (config.bench.pg.toml against a
# dockerized postgres:16-alpine, the SAME profile so the store-write delta is
# the only variable; --skip-pg), Native (MICRONAUT_CONFIG_FILES — the image's
# mainClass is JanusApplication, NOT the CLI). Every raw tool output is
# archived verbatim under.run/.
#
# Usage:
# scripts/bench/run_bench.sh [--skip-native] [--skip-pg] [--label <tag>]
# Env: FAKE_PORT / BENCH_PORT free-port substitution; RESULTS RESULTS.md path.
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"
BENCH_DIR="$REPO/scripts/bench"
RUN_DIR="$BENCH_DIR/.run"
RESULTS="${RESULTS:-$BENCH_DIR/RESULTS.md}"
LABEL="${LABEL:-run}"
SKIP_NATIVE=0
SKIP_PG=0

log() { printf '[bench] %s\n' "$*" >&2; }
die() { printf '[bench] FATAL: %s\n' "$*" >&2; exit 1; }

# Parse flags anywhere in argv (not only as $1 — a trailing/mid --label used to be
# silently ignored).
args=("$@")
i=0
while [[ $i -lt ${#args[@]} ]]; do
  case "${args[$i]}" in
    --skip-native) SKIP_NATIVE=1 ;;
    --skip-pg) SKIP_PG=1 ;;
    --label)
      i=$((i + 1))
      [[ $i -lt ${#args[@]} ]] || die "--label requires a value"
      LABEL="${args[$i]}"
;;
    *)
      die "unknown argument: ${args[$i]}"
;;
  esac
  i=$((i + 1))
done

export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
PY_BIN="python3"
BIN="$REPO/janus-gateway/build/native/nativeCompile/janus"

mkdir -p "$RUN_DIR"
PIDS=()
JVM_PID=""
JVM_PG_PID=""
NATIVE_PID=""
PG_CONTAINER="janus-bench-pg"
cleanup() {
  # Kill ONLY the processes this run spawned and tracks (PIDS + the leg PIDs) —
  # a broad pkill -9 -f on 'fake_upstream.py' would kill unrelated processes
  # on the machine.
  for pid in "${PIDS[@]:-}" "$JVM_PID" "$JVM_PG_PID" "$NATIVE_PID"; do
    [[ -n "$pid" ]] && kill -9 "$pid" 2>/dev/null || true
  done
  # The PG leg's dockerized Postgres (tracked container name — the
  # run_native_regression.sh pattern; a broad 'docker rm -f janus-w50-pg' would be
  # the other harness's container).
  [[ "$SKIP_PG" -eq 1 ]] || docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

port_busy() {
  "$PY_BIN" - "$1" <<'EOF'
import socket, sys
s = socket.socket()
try:
    s.bind(("127.0.0.1", int(sys.argv[1])))
except OSError:
    sys.exit(0)
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

wait_health() {  # wait_health <port> <bound_s>
  local port="$1" bound="${2:-120}"
  local i
  for i in $(seq 1 $((bound * 10))); do
    curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1 && return 0
    sleep 0.1
  done
  return 1
}

FAKE_PORT="${FAKE_PORT:-9879}"
if port_busy "$FAKE_PORT"; then FAKE_PORT="$(free_port)"; fi
BENCH_PORT="${BENCH_PORT:-18090}"
if port_busy "$BENCH_PORT"; then BENCH_PORT="$(free_port)"; fi
CHAT_URL="http://127.0.0.1:$BENCH_PORT/v1/chat/completions"
HEALTH_URL="http://127.0.0.1:$BENCH_PORT/health"
BENCH_AUTH=""

# ---------------------------------------------------------------- results head (idempotent:
# written only when RESULTS.md is missing/empty so legs can run in separate invocations)
if [[ ! -s "$RESULTS" ]]; then
  {
    echo "# packaging native production pass + benchmarks"
    echo
    echo "- **Date:** $(date -u '+%Y-%m-%d %H:%M UTC')"
    echo "- **Commit:** $(git -C "$REPO" rev-parse --short HEAD)"
    echo "- **Host OS:** $(uname -srm) ($(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown))"
    echo "- **Java:** $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
    echo "- **Gradle:** $(cd "$REPO" && ./gradlew --version 2>/dev/null | awk '/^Gradle/{print $2}' | head -1)"
    echo "- **Load tool:** $(command -v hey || command -v wrk || command -v ab || echo curl-loop)"
    echo "- **Profile:** warm-up request, then 1000 req / 10 concurrent non-streaming; streaming counted separately (20 concurrent SSE)"
    echo "- **Mock:** golden fake upstream (copy, committed corpus, pinned usage 14/12) on :$FAKE_PORT"
    echo "- **Raw outputs:** \`scripts/bench/.run/\` (verbatim tool output, no cherry-picking)"
    echo
    echo "## Comparison — Janus JVM + native"
    echo
    echo "| Implementation | Tool | Throughput (req/s) | p50 (ms) | p95 (ms) | Streams ok (/20) | Warm /health (ms) | RSS post-warmup (KiB) | RSS note |"
    echo "|---|---|---|---|---|---|---|---|---|"
    echo
  } >> "$RESULTS"
fi

# ---------------------------------------------------------------- fake
log "booting golden fake on :$FAKE_PORT"
rm -f "$RUN_DIR/fake-bench.counters.json"
nohup "$PY_BIN" "$BENCH_DIR/fake_upstream.py" --port "$FAKE_PORT" --name bench \
  --counter-file "$RUN_DIR/fake-bench.counters.json" > "$RUN_DIR/fake-bench.log" 2>&1 &
PIDS+=("$!")
fake_up=0
for i in $(seq 1 50); do
  if curl -sf "http://127.0.0.1:$FAKE_PORT/" >/dev/null 2>&1; then
    fake_up=1
    break
  fi
  sleep 0.1
done
[[ "$fake_up" -eq 1 ]] || die "fake upstream did not come up on :$FAKE_PORT (see $RUN_DIR/fake-bench.log)"

# per-run bench config (committed config.bench.toml + substituted ports)
CFG="$RUN_DIR/config.bench.$LABEL.toml"
sed -e "s|port = 18090|port = $BENCH_PORT|" \
    -e "s|http://127.0.0.1:9879|http://127.0.0.1:$FAKE_PORT|" \
    "$BENCH_DIR/config.bench.toml" > "$CFG"

run_leg_profile() {  # run_leg_profile <name> <auth-or-empty>
  local name="$1" auth="${2:-}"
  local auth_flag=()
  [[ -n "$auth" ]] && auth_flag=(--auth "$auth")
  log "profile ($name): warmup + 1000/10 throughput"
  # bash 3.2 (macOS) + set -u: an empty ${arr[@]} is an "unbound variable" — use the
  # ${arr[@]+...} guard so the no-auth legs pass no --auth flag at all.
  "$PY_BIN" "$BENCH_DIR/bench_client.py" --name "$name" --url "$CHAT_URL" \
    --run-dir "$RUN_DIR" ${auth_flag[@]+"${auth_flag[@]}"} > "$RUN_DIR/$name.bench.summary.txt"
  log "profile ($name): streaming concurrency (20 SSE)"
  "$PY_BIN" "$BENCH_DIR/stream_bench.py" --name "$name" --url "$CHAT_URL" \
    --streams 20 --run-dir "$RUN_DIR" ${auth_flag[@]+"${auth_flag[@]}"} > "$RUN_DIR/$name.stream.summary.txt"
  log "profile ($name): warm /health"
  "$PY_BIN" "$BENCH_DIR/startup_rss.py" warm --health-url "$HEALTH_URL" > "$RUN_DIR/$name.warm.txt"
}

record_leg() {  # record_leg <name> <tool> <rps> <p50> <p95> <streams-ok> <warm-ms> <rss-kb> <rss-note>
  {
    echo "| $1 | $2 | $3 | $4 ms | $5 ms | $6/20 | $7 ms | $8 | $9 |"
  } >> "$RESULTS"
}

# bench_client emits p95_ms for hey/ab/curl; wrk (no 95th in its stock table)
# emits p90_ms under its true key. Read the real one and label wrk's tool entry
# so the table never silently compares hey's p95 against wrk's p90.
metric_of() {  # metric_of <summary-file> <key-with-fallback...>
  awk -F= '
    /^p95_ms=/ {v95=$2}
    /^p90_ms=/ {v90=$2}
    END {print (v95 != "" ? v95 : v90)}
  ' "$1"
}
tool_of() {  # tool_of <summary-file>
  local t p90note=""
  t="$(awk -F= '/^tool=/{print $2}' "$1")"
  if awk -F= '/^p90_ms=/{found=1} END{exit !found}' "$1"; then p90note=" (high=p90)"; fi
  printf '%s%s' "$t" "$p90note"
}

# ================================================================ JVM leg
log "=== JVM leg (janus-cli --config) ==="
rm -f "$RUN_DIR/jvm.log"
nohup ./gradlew :janus-cli:run --no-daemon --args="--config $CFG" > "$RUN_DIR/jvm.log" 2>&1 &
PIDS+=("$!")
JVM_BOOT_START="$(date +%s%N)"
wait_health "$BENCH_PORT" 240 || die "JVM leg did not reach /health (see $RUN_DIR/jvm.log)"
JVM_COLD_MS=$(( ($(date +%s%N) - JVM_BOOT_START) / 1000000 ))
JVM_PID="$(pgrep -f "io.amscotti.janus.cli.JanusCli" | head -1)"
run_leg_profile "jvm" ""
"$PY_BIN" "$BENCH_DIR/startup_rss.py" rss --pid "$JVM_PID" > "$RUN_DIR/jvm.rss.txt"
JVM_RSS_KB="$(awk -F= '/^rss_kb=/{print $2}' "$RUN_DIR/jvm.rss.txt")"
JVM_WARM_MS="$(awk -F= '/^warm_ms=/{print $2}' "$RUN_DIR/jvm.warm.txt")"
JVM_TOOL="$(tool_of "$RUN_DIR/jvm.bench.summary.txt")"
JVM_RPS="$(awk -F= '/^requests_per_sec=/{print $2}' "$RUN_DIR/jvm.bench.summary.txt")"
JVM_P50="$(awk -F= '/^p50_ms=/{print $2}' "$RUN_DIR/jvm.bench.summary.txt")"
JVM_P95="$(metric_of "$RUN_DIR/jvm.bench.summary.txt")"
JVM_STREAMS="$(awk -F= '/^completed=/{print $2}' "$RUN_DIR/jvm.stream.summary.txt")"
echo "- **JVM leg:** cold boot → /health ${JVM_COLD_MS}ms (incl. Gradle single-use daemon), warm /health ${JVM_WARM_MS}ms, RSS post-warmup ${JVM_RSS_KB} KiB (ps sample of the app JVM)" >> "$RESULTS"
record_leg "Janus JVM" "$JVM_TOOL" "$JVM_RPS" "$JVM_P50" "$JVM_P95" "$JVM_STREAMS" "$JVM_WARM_MS" "$JVM_RSS_KB" "ps post-warmup"
kill -9 "$JVM_PID" 2>/dev/null || true
sleep 1

# ================================================================ JVM + Postgres leg 
if [[ "$SKIP_PG" -eq 1 ]]; then
  echo "- **JVM+PG leg:** SKIPPED (--skip-pg)" >> "$RESULTS"
elif ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "- **JVM+PG leg:** SKIPPED — docker unavailable (the leg boots a dockerized postgres:16-alpine)" >> "$RESULTS"
else
  log "=== JVM + Postgres-store leg  ==="
  PG_PORT="$(free_port)"
  log "starting bench Postgres on :$PG_PORT (container $PG_CONTAINER, postgres:16-alpine)"
  docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  # The run_native_regression.sh / smoke-store drill pattern: known dev
  # credentials exported by the RUNNER env (the config references env-var NAMES —
  # the secrets rule: no literal credentials in TOML).
  if ! docker run -d --name "$PG_CONTAINER" -e POSTGRES_USER=janus -e POSTGRES_PASSWORD=janus -e POSTGRES_DB=janus \
      -p "127.0.0.1:$PG_PORT:5432" postgres:16-alpine >/dev/null; then
    echo "- **JVM+PG leg:** SKIPPED — docker run for the bench Postgres failed (recorded, not silently dropped)" >> "$RESULTS"
  else
    pg_ready=0
    for i in $(seq 1 60); do
      if docker exec "$PG_CONTAINER" pg_isready -U janus -d janus >/dev/null 2>&1; then
        pg_ready=1
        break
      fi
      sleep 1
    done
    if [[ "$pg_ready" -ne 1 ]]; then
      echo "- **JVM+PG leg:** SKIPPED — bench Postgres did not become ready (see: docker logs $PG_CONTAINER)" >> "$RESULTS"
    else
      # Same substitutions as the in-memory bench config (same placeholders in
      # config.bench.pg.toml); only the [janus.store] block differs.
      PG_CFG="$RUN_DIR/config.bench.pg.$LABEL.toml"
      sed -e "s|port = 18090|port = $BENCH_PORT|" \
          -e "s|http://127.0.0.1:9879|http://127.0.0.1:$FAKE_PORT|" \
          "$BENCH_DIR/config.bench.pg.toml" > "$PG_CFG"
      rm -f "$RUN_DIR/jvm-pg.log"
      JANUS_DB_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/janus" JANUS_DB_USER=janus JANUS_DB_PASS=janus \
        nohup ./gradlew :janus-cli:run --no-daemon --args="--config $PG_CFG" > "$RUN_DIR/jvm-pg.log" 2>&1 &
      PIDS+=("$!")
      if wait_health "$BENCH_PORT" 240; then
        JVM_PG_PID="$(pgrep -f "io.amscotti.janus.cli.JanusCli" | head -1)"
        run_leg_profile "jvm-pg" ""
        if [[ -n "$JVM_PG_PID" ]]; then
          "$PY_BIN" "$BENCH_DIR/startup_rss.py" rss --pid "$JVM_PG_PID" > "$RUN_DIR/jvm-pg.rss.txt"
        fi
        JVM_PG_RSS_KB="$(awk -F= '/^rss_kb=/{print $2}' "$RUN_DIR/jvm-pg.rss.txt" 2>/dev/null || echo n/a)"
        JVM_PG_WARM_MS="$(awk -F= '/^warm_ms=/{print $2}' "$RUN_DIR/jvm-pg.warm.txt")"
        JVM_PG_TOOL="$(tool_of "$RUN_DIR/jvm-pg.bench.summary.txt")"
        JVM_PG_RPS="$(awk -F= '/^requests_per_sec=/{print $2}' "$RUN_DIR/jvm-pg.bench.summary.txt")"
        JVM_PG_P50="$(awk -F= '/^p50_ms=/{print $2}' "$RUN_DIR/jvm-pg.bench.summary.txt")"
        JVM_PG_P95="$(metric_of "$RUN_DIR/jvm-pg.bench.summary.txt")"
        JVM_PG_STREAMS="$(awk -F= '/^completed=/{print $2}' "$RUN_DIR/jvm-pg.stream.summary.txt")"
        echo "- **JVM+PG leg :** dockerized postgres:16-alpine on :$PG_PORT (container $PG_CONTAINER, dev creds via runner env) · production store defaults (pool 10, retention 1000) · same profile as the JVM leg — the p50/p95 delta IS the per-request call-ledger transaction cost (advisory lock → INSERT → prune → store_meta)" >> "$RESULTS"
        record_leg "Janus JVM + Postgres" "$JVM_PG_TOOL" "$JVM_PG_RPS" "$JVM_PG_P50" "$JVM_PG_P95" "$JVM_PG_STREAMS" "$JVM_PG_WARM_MS" "$JVM_PG_RSS_KB" "dockerized PG"
        [[ -n "$JVM_PG_PID" ]] && kill -9 "$JVM_PG_PID" 2>/dev/null || true
        sleep 1
      else
        echo "- **JVM+PG leg:** FAILED to boot (see $RUN_DIR/jvm-pg.log) — recorded, not silently dropped" >> "$RESULTS"
      fi
    fi
  fi
fi

# ================================================================ native leg
if [[ "$SKIP_NATIVE" -eq 1 ]]; then
  echo "- **Native leg:** SKIPPED (--skip-native)" >> "$RESULTS"
else
  log "=== native leg (MICRONAUT_CONFIG_FILES) ==="
  [[ -x "$BIN" ]] || die "native binary missing: $BIN (run :janus-gateway:nativeCompile first)"
  NATIVE_SIZE_BYTES="$(stat -f%z "$BIN" 2>/dev/null || stat -c%s "$BIN")"
  NATIVE_SIZE_MIB="$(awk -v b="$NATIVE_SIZE_BYTES" 'BEGIN{printf "%.1f", b/1048576}')"
  # cold boot + max RSS via /usr/bin/time (scaffold procedure; process killed after)
  log "native cold boot + max RSS"
  MICRONAUT_CONFIG_FILES="$CFG" MICRONAUT_SERVER_PORT="$BENCH_PORT" \
    "$PY_BIN" "$BENCH_DIR/startup_rss.py" cold --health-url "$HEALTH_URL" \
    --cmd "$BIN" > "$RUN_DIR/native.cold.txt" || true
  NATIVE_COLD_MS="$(awk -F= '/^cold_ms=/{print $2}' "$RUN_DIR/native.cold.txt")"
  NATIVE_MAX_RSS_KB="$(awk -F= '/^max_rss_kb=/{print $2}' "$RUN_DIR/native.cold.txt")"
  # bench instance
  rm -f "$RUN_DIR/native.log"
  MICRONAUT_CONFIG_FILES="$CFG" MICRONAUT_SERVER_PORT="$BENCH_PORT" \
    nohup "$BIN" > "$RUN_DIR/native.log" 2>&1 &
  NATIVE_PID=$!
  PIDS+=("$NATIVE_PID")
  wait_health "$BENCH_PORT" 60 || die "native leg did not reach /health (see $RUN_DIR/native.log)"
  run_leg_profile "native" ""
  "$PY_BIN" "$BENCH_DIR/startup_rss.py" rss --pid "$NATIVE_PID" > "$RUN_DIR/native.rss.txt"
  NATIVE_RSS_KB="$(awk -F= '/^rss_kb=/{print $2}' "$RUN_DIR/native.rss.txt")"
  NATIVE_WARM_MS="$(awk -F= '/^warm_ms=/{print $2}' "$RUN_DIR/native.warm.txt")"
  NATIVE_TOOL="$(tool_of "$RUN_DIR/native.bench.summary.txt")"
  NATIVE_RPS="$(awk -F= '/^requests_per_sec=/{print $2}' "$RUN_DIR/native.bench.summary.txt")"
  NATIVE_P50="$(awk -F= '/^p50_ms=/{print $2}' "$RUN_DIR/native.bench.summary.txt")"
  NATIVE_P95="$(metric_of "$RUN_DIR/native.bench.summary.txt")"
  NATIVE_STREAMS="$(awk -F= '/^completed=/{print $2}' "$RUN_DIR/native.stream.summary.txt")"
  echo "- **Native leg:** binary ${NATIVE_SIZE_MIB} MiB · cold boot → /health ${NATIVE_COLD_MS}ms · max RSS (lifetime, /usr/bin/time -l) ${NATIVE_MAX_RSS_KB} KiB · RSS post-warmup ${NATIVE_RSS_KB} KiB (ps)" >> "$RESULTS"
  record_leg "Janus native" "$NATIVE_TOOL" "$NATIVE_RPS" "$NATIVE_P50" "$NATIVE_P95" "$NATIVE_STREAMS" "$NATIVE_WARM_MS" "$NATIVE_RSS_KB" "ps post-warmup"
  kill -9 "$NATIVE_PID" 2>/dev/null || true
  sleep 1
fi


echo "- **Legs complete.** See the comparison table above; raw outputs under \`$RUN_DIR\`." >> "$RESULTS"
log "bench run complete — see $RESULTS"
