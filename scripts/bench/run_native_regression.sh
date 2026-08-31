#!/usr/bin/env bash
# =============================================================================
# run_native_regression.sh — tuned-image gate: the native postgres
# regression (drill_native.py) re-run against the CURRENT native image.
#
# Re-proves, after every tuning flag batch (plan step 6), that -O2 /
# -R:MaxHeapSize did not prune the JDBC store classes or break HikariCP's JDK
# proxies : boot tripwire, keyed golden round-trip (14/12 →
# exact 5320 micro-USD /metrics delta), one CallRecord in the shared Postgres,
# plus the strings check (org.postgresql + V1__init.sql embedded) and the
# postgres-shape boot-to-/health time. Records binary size too.
#
# Usage:
# scripts/bench/run_native_regression.sh [--label BASELINE|O2|O2+HEAP]
#
# Env:
# NATIVE_BIN native binary path (default janus-gateway/build/native/nativeCompile/janus)
# PORT / FAKE_PORT / PG_PORT free-port substitution (defaults: free)
# JANUS_MASTER_KEY master key (default: distinctive per-run)
# RESULTS RESULTS.md path (default scripts/bench/RESULTS.md)
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO"
BENCH_DIR="$REPO/scripts/bench"
SMOKE5_DIR="$REPO/scripts/smoke/store"
RUN_DIR="$BENCH_DIR/.run"
RESULTS="${RESULTS:-$BENCH_DIR/RESULTS.md}"
LABEL="${1:-BASELINE}"
BIN="${NATIVE_BIN:-$REPO/janus-gateway/build/native/nativeCompile/janus}"

export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
PY_BIN="python3"

log() { printf '[regress:%s] %s\n' "$LABEL" "$*" >&2; }
die() { printf '[regress:%s] FATAL: %s\n' "$LABEL" "$*" >&2; exit 1; }

[[ -x "$BIN" ]] || die "native binary missing: $BIN"
command -v docker >/dev/null || die "docker required"
docker info >/dev/null 2>&1 || die "docker info FAILED (lima socket — see RESULTS.md)"

mkdir -p "$RUN_DIR"
PIDS=()
NATIVE_PID=""
PG_CONTAINER="janus-w50-pg"
cleanup() {
  # Kill ONLY the processes this run spawned and tracks — a broad
  # pkill -9 -f "fake_upstream.py" / "build/native/nativeCompile/janus" would
  # kill unrelated processes on the machine (concurrent harnesses, other native
  # boots): the bench/run_bench.sh + routing/run.sh tracked-PID-only policy.
  for pid in "${PIDS[@]:-}" "$NATIVE_PID"; do
    [[ -n "$pid" ]] && kill -9 "$pid" 2>/dev/null || true
  done
  docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

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

PORT="${PORT:-$(free_port)}"
FAKE_PORT="${FAKE_PORT:-9877}"
if port_busy "$FAKE_PORT"; then FAKE_PORT="$(free_port)"; fi
PG_PORT="${PG_PORT:-$(free_port)}"
MASTER_KEY="${JANUS_MASTER_KEY:-w50-regress-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')}"
BASE_URL="http://127.0.0.1:$PORT/v1"

# ---------------------------------------------------------------- fake upstream
log "booting golden fake on :$FAKE_PORT"
rm -f "$RUN_DIR/fake-regress.counters.json"
nohup "$PY_BIN" "$BENCH_DIR/fake_upstream.py" --port "$FAKE_PORT" --name regress \
  --counter-file "$RUN_DIR/fake-regress.counters.json" > "$RUN_DIR/fake-regress.log" 2>&1 &
PIDS+=("$!")
# Post-loop guard (the run_bench.sh pattern): a bare `break` would let the script
# continue and die later with a misleading "native image did not reach /health".
fake_up=0
for i in $(seq 1 50); do
  if curl -sf "http://127.0.0.1:$FAKE_PORT/" >/dev/null 2>&1; then
    fake_up=1
    break
  fi
  sleep 0.1
done
[[ "$fake_up" -eq 1 ]] || die "fake upstream did not come up on :$FAKE_PORT (see $RUN_DIR/fake-regress.log)"

# ---------------------------------------------------------------- drill Postgres
log "starting drill Postgres on :$PG_PORT (container $PG_CONTAINER, postgres:16-alpine)"
docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$PG_CONTAINER" -e POSTGRES_USER=janus -e POSTGRES_PASSWORD=janus -e POSTGRES_DB=janus \
  -p "127.0.0.1:$PG_PORT:5432" postgres:16-alpine >/dev/null \
  || die "docker run for the drill Postgres failed"
# Post-loop guard (the run_bench.sh pg_ready pattern): without it a Postgres that
# never becomes ready surfaces later as a misleading native-boot or drill failure.
pg_ready=0
for i in $(seq 1 60); do
  if docker exec "$PG_CONTAINER" pg_isready -U janus -d janus >/dev/null 2>&1; then
    pg_ready=1
    break
  fi
  sleep 1
done
[[ "$pg_ready" -eq 1 ]] || die "drill Postgres did not become ready on :$PG_PORT (see: docker logs $PG_CONTAINER)"

# ---------------------------------------------------------------- config (postgres shape)
# store-smoke config.nodeA.toml read-only; only the fake port is substituted into a
#.run copy (the runner env-overrides the server port + JDBC creds).
CFG="$RUN_DIR/config.native-regress.toml"
sed "s|http://127.0.0.1:9877|http://127.0.0.1:$FAKE_PORT|" "$SMOKE5_DIR/config.nodeA.toml" > "$CFG"

# ---------------------------------------------------------------- boot + measure + drill
SIZE_BYTES="$(stat -f%z "$BIN" 2>/dev/null || stat -c%s "$BIN")"
SIZE_MIB="$(awk -v b="$SIZE_BYTES" 'BEGIN{printf "%.1f", b/1048576}')"
log "native binary: ${SIZE_MIB} MiB ($BIN)"

NATIVE_START="$(date +%s%N)"
JANUS_MASTER_KEY="$MASTER_KEY" JANUS_DB_URL="jdbc:postgresql://127.0.0.1:$PG_PORT/janus" \
  JANUS_DB_USER=janus JANUS_DB_PASS=janus \
  MICRONAUT_CONFIG_FILES="$CFG" MICRONAUT_SERVER_PORT="$PORT" \
  nohup "$BIN" > "$RUN_DIR/native-regress.log" 2>&1 &
NATIVE_PID=$!
PIDS+=("$NATIVE_PID")
HEALTH_MS=""
for i in $(seq 1 600); do
  if curl -sf "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
    HEALTH_MS=$((($(date +%s%N) - NATIVE_START) / 1000000))
    break
  fi
  sleep 0.1
done
[[ -n "$HEALTH_MS" ]] || die "native image did not reach /health (see $RUN_DIR/native-regress.log)"
log "postgres-shape boot -> /health: ${HEALTH_MS}ms"

DRIVER_STRINGS="$(strings "$BIN" 2>/dev/null | grep -c 'org\.postgresql' || true)"
MIGRATION_STRINGS="$(strings "$BIN" 2>/dev/null | grep -c 'V1__init\.sql\|CREATE TABLE IF NOT EXISTS keys' || true)"
log "strings check: org.postgresql=$DRIVER_STRINGS migration-markers=$MIGRATION_STRINGS"
[[ "$DRIVER_STRINGS" -gt 0 && "$MIGRATION_STRINGS" -gt 0 ]] || die "strings check FAILED (driver/migration pruned?)"

log "drill_native.py (postgres leg) against :$PORT"
DRILL_OUT="$("$PY_BIN" "$SMOKE5_DIR/drill_native.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --pg-container "$PG_CONTAINER" 2>&1)" \
  || { printf '%s\n' "$DRILL_OUT" >&2; die "drill_native FAILED (see above)"; }

# ---------------------------------------------------------------- record
{
  echo
  echo "### Native regression ($LABEL)"
  echo
  echo "\`\`\`"
  printf '%s\n' "$DRILL_OUT" | sed 's/^/    /'
  echo "\`\`\`"
  echo "- **Label:** $LABEL · **Binary size:** ${SIZE_MIB} MiB · **Boot → /health (postgres shape):** ${HEALTH_MS}ms"
  echo "- **Strings check:** \`org.postgresql\` occurrences: $DRIVER_STRINGS · migration SQL markers: $MIGRATION_STRINGS (both > 0 ⇒ driver + \`V1__init.sql\` embedded)"
  echo "- **drill_native:** ALL PASS (tripwire, keyed golden round-trip, exact 5320-µUSD /metrics delta, CallRecord in the shared Postgres)"
} >> "$RESULTS"
log "drill_native: ALL PASS (recorded in $RESULTS)"
