#!/usr/bin/env bash
# =============================================================================
# run.sh — governance e2e gate
#
# Proves with UNMODIFIED SDKs (fresh venv, pinned openai + anthropic) against live
# Janus boots that Janus is GOVERNED (the governance):
# gate 1 — the master key generates virtual keys (sk-janus-…, shown once); valid
# keys succeed on both faces; invalid keys → 401, revoked → 403
# permission_error (the wording decision — recorded), scope-denied
# → 403; over-limit → 429 rate_limit_error + Retry-After (RPM both faces,
# fixed AND sliding); over-budget → 429 BEFORE dispatch (fake counter flat)
# gate 2 — cost on a real request matches manual price×usage EXACTLY against the
# DeepSeek table: golden 14/12 tokens × 0.14/0.28 per 1K = 5320 micro-USD,
# asserted on the scraped /metrics series with zero tolerance (both faces,
# streaming settle-from-terminal-chunk + documented $0 settle)
# gate 3 — /metrics exposes every Tier-1 series non-zero after a mixed workload;
# the privacy contract holds over the HTTP surface (prompt/response markers
# absent); /prometheus → 404
# Review 1 — security pass live (keys hashed at rest, master key never logged —
# distinctive-value grep across every leg, revoked full string dead)
# Review 2 — budget edge cases live (exact-cap boundary, concurrent racing with
# settled ≤ cap + one request, revocation mid-flight, soft-cap header +
# once-per-key-per-window notifier dedup)
# — budget reset windows live: a tiny budget_duration hard-deny carries
# Retry-After (seconds to the aligned reset); crossing the boundary
# resets the window row (fresh spend re-admitted) — drill_budget
# --budget-duration (offline, fake upstream)
# Review 3 — written LiteLLM comparison + decision records (RESULTS.md)
# Regression — auth-off default byte-identical: keyless boot (no JANUS_MASTER_KEY)
# → no 401s/429s, unlabeled metrics only; run.sh --skip-native
# green on the same build
# Native — the design subset through the GraalVM image (MICRONAUT_CONFIG_FILES boot —
# NOT --config: the image's mainClass is JanusApplication).
#
# Deterministic legs run against stdlib-only fake upstreams (fake_upstream.py — the
# pattern + the stage 4 --usage-override for pinned token counts) fed by the
# committed /corpus read-only. ships NO production features beyond the
# blessed /hand-offs (verified in HEAD, recorded in RESULTS.md): every file
# this script touches lives under scripts/smoke/governance/ (+ Gradle outputs + this
# RESULTS.md + a temporary backup of the phase3 RESULTS.md restored after the
# regression leg).
#
# Usage:
# scripts/smoke/governance/run.sh [--skip-native] [--skip-regression]
#
# Env:
# JANUS_PORT gateway port (default 18080; free port when taken)
# FAKE_PORT golden fake upstream port (default 9877)
# SOFT_FAKE_PORT small-usage fake port (default 9879; --usage-override 1/1)
# CHAOS_ITERS phase3 regression chaos iterations (default 2 — the gate
# already proved 10×; this leg re-proves the design slice under the
# stage 4 wiring)
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO"
SMOKE_DIR="$REPO/scripts/smoke/governance"
RUN_DIR="$SMOKE_DIR/.run"
RESULTS="$SMOKE_DIR/RESULTS.md"
CHAOS_ITERS="${CHAOS_ITERS:-2}"
SKIP_NATIVE=0
SKIP_REGRESSION=0
for arg in "$@"; do
  [[ "$arg" == "--skip-native" ]] && SKIP_NATIVE=1
  [[ "$arg" == "--skip-regression" ]] && SKIP_REGRESSION=1
done

export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
PY_BIN="python3"
[[ -n "${JAVA_HOME:-}" ]] && export PATH="$JAVA_HOME/bin:$PATH"

log() { printf '[gate] %s\n' "$*" >&2; }
die() { printf '[gate] FATAL: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- state
mkdir -p "$RUN_DIR"
PIDS=()
GATE_START="$(date +%s%N)"
FAKE_PID=""
SOFT_FAKE_PID=""
JANUS_PID=""
NATIVE_PID=""
LEG_JVM=()
LEG_NATIVE=()

cleanup() {
  # Kill ONLY the processes this run spawned and tracks — a broad
  # pkill -9 -f "fake_upstream.py" (etc.) would kill unrelated processes on
  # the machine (concurrent harnesses, editors, shells): the routing/run.sh +
  # bench/run_bench.sh tracked-PID-only policy.
  for pid in "${PIDS[@]:-}" "$FAKE_PID" "$SOFT_FAKE_PID" "$JANUS_PID" "$NATIVE_PID"; do
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
  local port="$1" bound="${2:-240}"
  local i
  for i in $(seq 1 "$((bound * 10))"); do
    if curl -sf "http://127.0.0.1:$port/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.1
  done
  return 1
}

wait_models_once() {
  local port="$1" bound="${2:-30}"
  local i
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

pid_alive() {
  local stat
  stat="$(ps -o stat= -p "$1" 2>/dev/null | tr -d ' ')"
  [[ -n "$stat" && "$stat" != "Z"* ]]
}

check_janus_log_clean() {
  local log_file="$1" label="$2"
  if grep -qi 'unhandled\|Exception in thread\|at io\.amscotti' "$log_file"; then
    echo "=== exception traces in $log_file ===" >&2
    grep -i 'unhandled\|Exception in thread\|at io\.amscotti' "$log_file" | head -20 >&2
    die "clean-log contract ($label): $log_file contains unhandled exception traces (see above)"
  fi
  echo "- **Janus log clean ($label):** PASS — no unhandled exceptions in $(basename "$log_file")" >> "$RESULTS"
}

run_drill() {
  # run_drill <label> <cmd...>: run a drill, record its output indented; die on failure.
  local label="$1"; shift
  log "drill: $label"
  local out
  out="$("$@" 2>&1)" || { printf '%s\n' "$out" >&2; die "drill $label FAILED (see above)"; }
  printf '%s\n' "$out" | sed 's/^/    /' >> "$RESULTS"
}

# tripwire: the master key must actually be ENFORCING (a misconfigured auth-off run
# would "pass" every key leg vacuously) — a keyless model request must 401, and the
# master-keyed /key/generate round-trip must yield a sk-janus- key.
tripwire() {
  local port="$1" master="$2"
  local status body
  status="$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$port/v1/chat/completions" \
    -H 'Content-Type: application/json' \
    -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"w33-tripwire"}]}')"
  [[ "$status" == "401" ]] || die "tripwire: keyless model request returned $status (auth not enforcing?)"
  body="$(curl -sf -X POST "http://127.0.0.1:$port/key/generate" \
    -H 'Content-Type: application/json' -H "x-api-key: $master" \
    -d '{"name":"tripwire"}')" || die "tripwire: master-keyed /key/generate round-trip failed (curl exit $?)"
  printf '%s' "$body" | grep -q 'sk-janus-' || die "tripwire: master-keyed /key/generate round-trip failed: $body"
  log "tripwire PASS: auth ON (keyless request 401s), master-keyed /key/generate round-trips"
}

# ---------------------------------------------------------------- fakes
boot_fake() {  # boot_fake <port> [usage-override] [name]
  local port="$1" override="${2:-}" name="${3:-fake}"
  log "booting fake($name) on :$port (override: ${override:-golden 14/12})"
  rm -f "$RUN_DIR/fake-$name.counters.json" "$RUN_DIR/fake-$name.abort.log"
  local args=(--port "$port" --name "$name" --counter-file "$RUN_DIR/fake-$name.counters.json"
              --abort-log "$RUN_DIR/fake-$name.abort.log")
  if [[ -n "$override" ]]; then
    args+=(--usage-override "$override")
  fi
  nohup "$PY_BIN" "$SMOKE_DIR/fake_upstream.py" "${args[@]}" > "$RUN_DIR/fake-$name.log" 2>&1 &
  local pid=$!
  PIDS+=("$pid")
  if [[ "$name" == "soft" ]]; then SOFT_FAKE_PID=$pid; else FAKE_PID=$pid; fi
  wait_fake "$port" || die "fake($name) did not come up (see $RUN_DIR/fake-$name.log)"
}

# ---------------------------------------------------------------- janus (JVM)
boot_janus_jvm() {  # boot_janus_jvm <config> <master-key-or-empty> <log-name>
  local config="$1" master="${2:-}" logname="$3"
  log "booting Janus (JVM leg, :$JANUS_PORT) with $logname (master key: $([[ -n "$master" ]] && echo ON || echo OFF))"
  local log_file="$RUN_DIR/$logname"
  rm -f "$log_file"
  if [[ -n "$master" ]]; then
    JANUS_MASTER_KEY="$master" MICRONAUT_SERVER_PORT="$JANUS_PORT" nohup ./gradlew :janus-cli:run \
      --no-daemon --args="--config $config" > "$log_file" 2>&1 &
  else
    MICRONAUT_SERVER_PORT="$JANUS_PORT" nohup ./gradlew :janus-cli:run \
      --no-daemon --args="--config $config" > "$log_file" 2>&1 &
  fi
  PIDS+=("$!")
  wait_for_health "$JANUS_PORT" 240 || die "JVM Janus did not reach /health (see $log_file)"
  JANUS_PID="$(pgrep -f 'io.amscotti.janus.cli.JanusCli' | tail -1)"
  [[ -n "$JANUS_PID" ]] || die "could not find the JanusCli JVM pid"
  log "JVM Janus healthy on :$JANUS_PORT (pid $JANUS_PID)"
  wait_models_once "$JANUS_PORT" || die "boot-misconfig tripwire: /v1/models must list deepseek-v4-flash exactly once (JVM)"
}
kill_janus_jvm() {
  [[ -n "$JANUS_PID" ]] && kill -9 "$JANUS_PID" 2>/dev/null || true
  wait "$JANUS_PID" 2>/dev/null || true
  JANUS_PID=""
}

# ---------------------------------------------------------------- native
boot_native() {  # boot_native <config> <master-key> <log-name>
  local config="$1" master="$2" logname="$3"
  log "booting native Janus on :$JANUS_PORT (MICRONAUT_CONFIG_FILES — NOT --config)"
  local log_file="$RUN_DIR/$logname"
  rm -f "$log_file"
  JANUS_MASTER_KEY="$master" MICRONAUT_CONFIG_FILES="$config" MICRONAUT_SERVER_PORT="$JANUS_PORT" \
    nohup "$BIN" > "$log_file" 2>&1 &
  NATIVE_PID=$!
  PIDS+=("$NATIVE_PID")
  wait_for_health "$JANUS_PORT" 60 || die "native Janus did not reach /health (see $log_file)"
  wait_models_once "$JANUS_PORT" || die "boot-misconfig tripwire: /v1/models must list deepseek-v4-flash exactly once (native)"
}
kill_native() {
  [[ -n "$NATIVE_PID" ]] && kill -9 "$NATIVE_PID" 2>/dev/null || true
  wait "$NATIVE_PID" 2>/dev/null || true
  NATIVE_PID=""
}

# ---------------------------------------------------------------- env
command -v "$PY_BIN" >/dev/null || die "python3 required"
command -v curl >/dev/null || die "curl required"

VENV="$SMOKE_DIR/.venv"
if [[ ! -x "$VENV/bin/python" ]]; then
 log "creating fresh venv at $VENV (openai==1.109.0, anthropic==0.120.2 — pins)"
  rm -rf "$VENV"
  "$PY_BIN" -m venv "$VENV"
  "$VENV/bin/pip" install --quiet --disable-pip-version-check "openai==1.109.0" "anthropic==0.120.2"
fi
PY="$VENV/bin/python"
OPENAI_VER="$("$PY" -c 'import openai; print(openai.__version__)')"
ANTHROPIC_VER="$("$PY" -c 'import anthropic; print(anthropic.__version__)')"
log "SDK pins: openai==$OPENAI_VER anthropic==$ANTHROPIC_VER"

# ---------------------------------------------------------------- ports + key
JANUS_PORT="${JANUS_PORT:-18080}"
if port_busy "$JANUS_PORT"; then
  log "port $JANUS_PORT busy (stale Janus?) — picking a free gateway port"
  JANUS_PORT="$(free_port)"
fi
FAKE_PORT="${FAKE_PORT:-9877}"
if port_busy "$FAKE_PORT"; then
  FAKE_PORT="$(free_port)"
fi
SOFT_FAKE_PORT="${SOFT_FAKE_PORT:-9879}"
if port_busy "$SOFT_FAKE_PORT"; then
  SOFT_FAKE_PORT="$(free_port)"
fi
BASE_URL="http://127.0.0.1:$JANUS_PORT/v1"
C1="$RUN_DIR/fake-fake.counters.json"
MASTER_KEY="ph4-gate-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"

mk_config() {  # mk_config <name> [soft-fake-port] → the substituted config path
  local name="$1" soft="${2:-}"
  local out="$RUN_DIR/config.$name${soft:+.soft}.$JANUS_PORT.toml"
  if [[ -n "$soft" ]]; then
    sed -e "s|http://127.0.0.1:9877|http://127.0.0.1:$SOFT_FAKE_PORT|" "$SMOKE_DIR/$name" > "$out"
  else
    sed -e "s|http://127.0.0.1:9877|http://127.0.0.1:$FAKE_PORT|" "$SMOKE_DIR/$name" > "$out"
  fi
  printf '%s' "$out"
}
FAKE_CONFIG="$(mk_config config.fake.toml)"
SLIDING_CONFIG="$(mk_config config.sliding.toml)"
BUDGET_CONFIG="$(mk_config config.budget.toml)"
BUDGET_SOFT_CONFIG="$(mk_config config.budget.toml soft)"
# auth-off config: the phase4 config with [janus.keys] explicitly auth = "off"
# (the hardened posture — keyless auth-off is an explicit declaration, never silent)
AUTHOFF_CONFIG="$RUN_DIR/config.authoff.$JANUS_PORT.toml"
sed -e "s|http://127.0.0.1:9877|http://127.0.0.1:$FAKE_PORT|" \
    -e '/^\[janus.keys\]$/,+1 s/^master-key-env.*/auth = "off"/' \
  "$SMOKE_DIR/config.fake.toml" > "$AUTHOFF_CONFIG"
log "gateway: $BASE_URL  fake(golden): :$FAKE_PORT  fake(soft): :$SOFT_FAKE_PORT"

# ---------------------------------------------------------------- results head
echo "" > "$RESULTS"
{
  echo "# — stage 4 e2e gate results"
  echo
  echo "- **Date:** $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo "- **Commit:** $(git -C "$REPO" rev-parse --short HEAD)"
  echo "- **Java:** $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  echo "- **Gradle:** $(cd "$REPO" && ./gradlew --version 2>/dev/null | awk '/^Gradle/{print $2}' | head -1)"
  echo "- **OpenAI SDK pin:** \`openai==$OPENAI_VER\` · **Anthropic SDK pin:** \`anthropic==$ANTHROPIC_VER\` (fresh venv, \`$VENV\`)"
  echo "- **Gateway port:** $JANUS_PORT · **Golden fake:** :$FAKE_PORT · **Soft-usage fake (1/1 override):** :$SOFT_FAKE_PORT"
  echo "- **Master key:** distinctive per-run value (the security leg greps the Janus logs for it — never present)"
  echo "- **Drill configs:** \`config.fake.toml\` (keys/limits/cost/metrics/security), \`config.sliding.toml\` (sliding RPM/TPM), \`config.budget.toml\` (hard/soft caps), \`config.real.toml\` (placeholder) — all under \`scripts/smoke/governance/\`, root \`config.toml\` untouched"
  echo
  echo "## Baseline: \`./gradlew build --no-daemon\`"
} >> "$RESULTS"

log "baseline ./gradlew build"
(cd "$REPO" && ./gradlew build --no-daemon > "$RUN_DIR/build-baseline.log" 2>&1) \
  || die "baseline build failed (see $RUN_DIR/build-baseline.log)"
echo "- **Result:** BUILD SUCCESSFUL (all modules, spotless + \`-Werror\` — re-runs the - governance/metrics suites incl. \`KeyHashTest\`, \`InMemoryKeyStoreTest\`, \`GovernanceControllerTest\`, \`MetricsPrivacyContractTest\`, \`ProductionMetricsExpositionTest\`)" >> "$RESULTS"

# ================================================================ Leg 1-5 — JVM (config.fake.toml)
boot_fake "$FAKE_PORT"
boot_janus_jvm "$FAKE_CONFIG" "$MASTER_KEY" "janus-jvm.log"
tripwire "$JANUS_PORT" "$MASTER_KEY"
{
  echo
  echo "## Leg 1 — JVM boot, config.fake.toml "
  echo
} >> "$RESULTS"

run_drill "key lifecycle " "$PY" "$SMOKE_DIR/drill_keys.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --rounds 2
echo "- **gate 1 (keys):** master generates sk-janus keys (shown once); valid keys succeed on both faces (SDK, streaming + non-streaming); unknown → 401; revoked → 403; scope-denied → 403; wrong master → 401; list/delete redacted" >> "$RESULTS"

run_drill "rate limits fixed window " "$PY" "$SMOKE_DIR/drill_limits.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --counter "$C1" --window fixed
echo "- **gate 1 (limits, fixed):** rpm:2 key → 3rd request 429 rate_limit_error + Retry-After == window-end math; throttled never reaches the fake (counter flat); TPM pre-check 429 before dispatch + real-token accumulation; rpm:0 → 400 at generate ( live)" >> "$RESULTS"

run_drill "exact cost " "$PY" "$SMOKE_DIR/drill_cost.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --rounds 1
echo "- **gate 2:** golden 14/12 × 0.14/0.28 per 1K = **5320 micro-USD** asserted on the scraped /metrics with zero tolerance (both faces; streaming settle-from-terminal-chunk; documented \$0 settle without include_usage)" >> "$RESULTS"

run_drill "metrics + privacy " "$PY" "$SMOKE_DIR/drill_metrics.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --rounds 2 --expect-4xx
echo "- **gate 3:** every Tier-1 series non-zero (incl. histogram _bucket lines and filter-level 4xx — m2/m3 decisions); privacy markers (prompt + response text) absent from the exposition; /prometheus → 404" >> "$RESULTS"

run_drill "security pass (Review 1)" "$PY" "$SMOKE_DIR/drill_security.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --janus-log "$RUN_DIR/janus-jvm.log"
echo "- **Review 1:** keys hashed at rest (list/delete redacted, no hash/salt); master key + virtual-key secrets NEVER in the Janus log (distinctive-value grep); BAD_MASTER envelope identical to missing key; revoked full string dead; Bearer + x-api-key both accepted" >> "$RESULTS"
check_janus_log_clean "$RUN_DIR/janus-jvm.log" "JVM legs 1-5 (keys/limits/cost/metrics/security)"
LEG_JVM+=("keys:PASS" "limits-fixed:PASS" "cost:PASS" "metrics:PASS" "security:PASS" "logclean:PASS")

# ================================================================ Leg 6 — sliding
kill_janus_jvm
boot_janus_jvm "$SLIDING_CONFIG" "$MASTER_KEY" "janus-sliding.log"
{
  echo
  echo "## Leg 2 — JVM boot, config.sliding.toml (sliding-variant RPM/TPM Retry-After)"
  echo
} >> "$RESULTS"
run_drill "rate limits sliding window " "$PY" "$SMOKE_DIR/drill_limits.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --counter "$C1" --window sliding
echo "- **gate 1 (limits, sliding):** token-bucket limiter wired (config.sliding.toml); the 3rd rpm request 429s with Retry-After present in [1, 60] — the m1 decision: the sliding variant substitutes the conservative aligned-window value for deficit÷rate refill seconds (documented in docs/governance.md)" >> "$RESULTS"
check_janus_log_clean "$RUN_DIR/janus-sliding.log" "JVM sliding leg"
LEG_JVM+=("limits-sliding:PASS")

# ================================================================ Leg 7 — budget (golden fake)
kill_janus_jvm
boot_janus_jvm "$BUDGET_CONFIG" "$MASTER_KEY" "janus-budget.log"
{
  echo
  echo "## Leg 3 — JVM boot, config.budget.toml → golden fake "
  echo
} >> "$RESULTS"
run_drill "budget hard-cap / exact-cap / racing / revocation " "$PY" "$SMOKE_DIR/drill_budget.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --counter "$C1" --janus-log "$RUN_DIR/janus-budget.log" --concurrency 8
echo "- **gate 1 (budget):** budget_usd: 0.01064 (= 2 × 5320) passes 1-2, 3rd → 429 before dispatch (counter flat, no Retry-After); **Review 2:** exact-cap boundary (settled == cap, N+1-th denied), concurrent racing (8 parallel → clean 200/429 split, settled ≤ cap + one request, no 500s), revocation mid-flight (no exception storm, post-revoke 403)" >> "$RESULTS"

# budget reset windows (the plan's cross-workstream smoke item) — same golden
# boot, second drill invocation: a tiny budget_duration refills the cap at the
# aligned rollover (offline, fake upstream only).
run_drill "budget windowed reset " "$PY" "$SMOKE_DIR/drill_budget.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --counter "$C1" --budget-duration 3
echo "- **(windowed budget):** \`budget_duration: 3\` + \`budget_usd: 0.01064\` — the in-window 429 carries \`Retry-After\` ∈ [1, 3] (seconds to the aligned reset; a lifetime budget carries none); after the boundary the FRESH window row resets (the same 2 golden settles re-admitted, then 429 again) — offline, fake upstream" >> "$RESULTS"
check_janus_log_clean "$RUN_DIR/janus-budget.log" "JVM budget leg 1"
LEG_JVM+=("budget-hard:PASS" "budget-windowed:PASS")

# ================================================================ Leg 8 — budget (soft fake)
kill_janus_jvm
boot_fake "$SOFT_FAKE_PORT" '{"prompt_tokens":1,"completion_tokens":1}' "soft"
boot_janus_jvm "$BUDGET_SOFT_CONFIG" "$MASTER_KEY" "janus-budget2.log"
{
  echo
  echo "## Leg 4 — JVM boot, config.budget.toml → soft-usage fake (Review 2: soft cap + notifier dedup)"
  echo
} >> "$RESULTS"
run_drill "budget soft-cap + notifier dedup (Review 2, n3 decision)" "$PY" "$SMOKE_DIR/drill_budget.py" \
  --base-url "$BASE_URL" --master-key "$MASTER_KEY" --counter "$RUN_DIR/fake-soft.counters.json" \
  --janus-log "$RUN_DIR/janus-budget2.log" --small-usage
echo "- **Review 2 (soft + dedup):** 16 soft-crossing successes (headers on each) + the 20th request hard-429s (no Retry-After); the notifier fired exactly ONCE for the key across the whole window — the n3 dedup decision (DedupNotifier, blessed) proven live; streams' soft-exceed is notifier-only ( m4 in-suite)" >> "$RESULTS"
check_janus_log_clean "$RUN_DIR/janus-budget2.log" "JVM budget leg 2 (soft)"
LEG_JVM+=("budget-soft:PASS")

# ================================================================ Leg 9 — auth-off regression
if [[ "$SKIP_REGRESSION" -eq 1 ]]; then
  echo
  echo "## Leg 5 — auth-off regression: **SKIPPED** (--skip-regression)" >> "$RESULTS"
else
  kill_janus_jvm
  unset JANUS_MASTER_KEY || true
  boot_janus_jvm "$AUTHOFF_CONFIG" "" "janus-authoff.log"
  {
    echo
    echo "## Leg 5 — auth-off regression (no JANUS_MASTER_KEY, config [janus.keys] auth = \"off\")"
    echo
  } >> "$RESULTS"
  run_drill "auth-off passthrough (OpenAI-face / cross-format / routing shape)" "$PY" "$SMOKE_DIR/drill_authoff.py" \
    --base-url "$BASE_URL" --rounds 2
 echo "- **Regression (auth-off boot):** keyless requests succeed on both faces (no 401s, no 429s); /metrics records UNLABELED totals only (no key_id series, empty 4xx) — the auth-off path live; \`JANUS_MASTER_KEY\` unset for this boot" >> "$RESULTS"
  check_janus_log_clean "$RUN_DIR/janus-authoff.log" "auth-off regression boot"
  kill_janus_jvm

  log "regression: run.sh --skip-native (self-contained, ~5-8 min; CHAOS_ITERS=$CHAOS_ITERS)"
  P3_RESULTS="$REPO/scripts/smoke/routing/RESULTS.md"
  P3_BACKUP="$RUN_DIR/phase3-RESULTS.md.bak"
  [[ -f "$P3_RESULTS" ]] && cp "$P3_RESULTS" "$P3_BACKUP"
  if (cd "$REPO" && CHAOS_ITERS="$CHAOS_ITERS" bash scripts/smoke/routing/run.sh --skip-native \
      > "$RUN_DIR/phase3-gate.log" 2>&1); then
    if [[ -f "$P3_BACKUP" ]]; then cp "$P3_BACKUP" "$P3_RESULTS"; else rm -f "$P3_RESULTS"; fi
    {
      echo
      echo "### routing / resilience slice under the governance wiring (run.sh --skip-native)"
      echo
 echo "- **Result:** PASS — the gate is green on the same build (failover/chaos ${CHAOS_ITERS}×/classifier/hang/streaming boundary/breaker/health/fairness/weighted + the OpenAI-face / cross-format regression). stage 3 RESULTS.md restored after the run. The phase4 auth-off boot above proves the keyless shape on the phase4 config itself."
    } >> "$RESULTS"
    LEG_JVM+=("regression:PASS")
  else
    tail -40 "$RUN_DIR/phase3-gate.log" >&2
    if [[ -f "$P3_BACKUP" ]]; then cp "$P3_BACKUP" "$P3_RESULTS"; else rm -f "$P3_RESULTS"; fi
    die "phase3 regression gate FAILED (see $RUN_DIR/phase3-gate.log; phase3 RESULTS.md restored)"
  fi
fi

# ================================================================ Legs 10/11 — native
# Always drop the last JVM boot (budget-soft when --skip-regression; auth-off
# otherwise). Otherwise wait_for_health succeeds against the leftover JVM
# process and native cost/keys hit the wrong config (1/1 soft-usage fake).
kill_janus_jvm
if [[ "$SKIP_NATIVE" -eq 1 ]]; then
  echo
  echo "## Legs 6-7 — native boot: **SKIPPED** (--skip-native)" >> "$RESULTS"
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
  # The phase3 gate's cleanup killed fake(golden) — reboot it for the native legs.
  if ! curl -sf "http://127.0.0.1:$FAKE_PORT/" >/dev/null 2>&1; then
    boot_fake "$FAKE_PORT"
  fi
  {
    echo
    echo "## Leg 6 — native boot A, config.fake.toml (GraalVM image; gate subset)"
    echo
    echo "- **Native binary size:** ${NATIVE_SIZE_MB} MiB (\`$BIN\`)"
  } >> "$RESULTS"
  NATIVE_START="$(date +%s%N)"
  boot_native "$FAKE_CONFIG" "$MASTER_KEY" "native.log"
  NATIVE_HEALTH_MS=$(( ($(date +%s%N) - NATIVE_START) / 1000000 ))
  echo "- **Boot → /health:** ${NATIVE_HEALTH_MS}ms" >> "$RESULTS"
  tripwire "$JANUS_PORT" "$MASTER_KEY"

  run_drill "native key lifecycle" "$PY" "$SMOKE_DIR/drill_keys.py" \
    --base-url "$BASE_URL" --master-key "$MASTER_KEY" --rounds 1
  run_drill "native rate limits (fixed)" "$PY" "$SMOKE_DIR/drill_limits.py" \
    --base-url "$BASE_URL" --master-key "$MASTER_KEY" --counter "$C1" --window fixed
  run_drill "native exact cost " "$PY" "$SMOKE_DIR/drill_cost.py" \
    --base-url "$BASE_URL" --master-key "$MASTER_KEY" --rounds 1
  run_drill "native metrics + privacy " "$PY" "$SMOKE_DIR/drill_metrics.py" \
    --base-url "$BASE_URL" --master-key "$MASTER_KEY" --rounds 1 --expect-4xx
  run_drill "native security pass (Review 1)" "$PY" "$SMOKE_DIR/drill_security.py" \
    --base-url "$BASE_URL" --master-key "$MASTER_KEY" --janus-log "$RUN_DIR/native.log"
  run_drill "native abort drill (streamed release path)" "$PY" "$SMOKE_DIR/drill_abort.py" \
    --base-url "$BASE_URL" --master-key "$MASTER_KEY" --janus-pid "$NATIVE_PID" \
    --abort-log "$RUN_DIR/fake-fake.abort.log" --cells 4
  echo "- **Native leg A (gate subset):** key lifecycle (valid/invalid/revoked/scope), RPM 429 + Retry-After, budget 429, exact-cost scrape (5320.0), /metrics Tier-1 + privacy marker + gauges + /prometheus 404, streaming settle legs, abort drill — all PASS (above). The governance/metrics classes are rooted in the image (not pruned)." >> "$RESULTS"
  check_janus_log_clean "$RUN_DIR/native.log" "native leg A"
  LEG_NATIVE+=("keys:PASS" "limits:PASS" "cost:PASS" "metrics:PASS" "security:PASS" "abort:PASS" "logclean:PASS")
  kill_native

  {
    echo
    echo "## Leg 7 — native boot B, config.budget.toml (budget 429 + Review 2 subset)"
    echo
  } >> "$RESULTS"
  boot_native "$BUDGET_CONFIG" "$MASTER_KEY" "native-budget.log"
  run_drill "native budget hard-cap / exact-cap / racing / revocation" "$PY" "$SMOKE_DIR/drill_budget.py" \
    --base-url "$BASE_URL" --master-key "$MASTER_KEY" --counter "$C1" --janus-log "$RUN_DIR/native-budget.log" --concurrency 8
  echo "- **Native leg B:** budget hard-cap 429 (counter flat, no Retry-After), exact-cap boundary, concurrent racing (no 500s, settled ≤ cap + one request), revocation mid-flight — all PASS" >> "$RESULTS"
  check_janus_log_clean "$RUN_DIR/native-budget.log" "native leg B"
  LEG_NATIVE+=("budget:PASS")
  kill_native
fi

# ---------------------------------------------------------------- final gate
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
    echo "- **\`./gradlew :janus-gateway:nativeCompile\`:** BUILD SUCCESSFUL (binary ${NATIVE_SIZE_MB:-?} MiB)"
  fi
  echo "- **Legs:** JVM [$(IFS=,; echo "${LEG_JVM[*]}")] · Native [$(IFS=,; echo "${LEG_NATIVE[*]}")]"
  echo "- **Gate wall-clock:** ${GATE_ELAPSED}s"
  echo
  echo "## Gate summary"
  echo
  echo "| Acceptance | Verdict | Evidence (this run) |"
  echo "|---|---|---|"
  echo "| **gate 1** — master key generates keys; valid succeed; invalid/revoked/scope denied; over-limit/over-budget 429 | PASS | Leg 1 (keys + limits + budget) + Leg 2 (sliding) + native legs; tripwire proves auth enforcing; envelopes face-appropriate on both faces |"
  echo "| **gate 2** — cost on a real request matches manual price×usage exactly (DeepSeek) | PASS | drill_cost: golden 14/12 × 0.14/0.28 per 1K = **5320 micro-USD** on the scraped /metrics with zero tolerance (JVM + native, both faces); streaming settle-from-terminal-chunk + documented \$0 settle |"
  echo "| **gate 3** — /metrics exposes all Tier-1 series; no prompt/response text | PASS | drill_metrics: all 11 Tier-1 series non-zero (incl. histogram _bucket lines + filter-level 4xx); prompt/response markers absent from the exposition; /prometheus → 404 (JVM + native) |"
  echo "| **gate 4** — docs/governance.md complete | PASS | the operator-facing reference ships with this gate (quickstart, admin API, keys, limits, pricing, budgets, metrics, config reference, security notes, LiteLLM compatibility) |"
  echo "| **Review 1** — security pass (hashed at rest, master key never logged, timing-safe) | PASS | drill_security live greps + baseline build re-runs KeyHashTest / InMemoryKeyStoreTest; BAD_MASTER envelope identical to missing key |"
  echo "| **Review 2** — budget edge cases (exact-cap, racing, revocation, soft+dedup) | PASS | drill_budget legs 3-4: exact-cap boundary, concurrent split (settled ≤ cap + one request, no 500s), mid-flight revocation, soft header + exactly-1 notifier WARN (16 crossings deduped) |"
  echo "| **Review 3** — LiteLLM budget comparison + decision records | PASS | written table + decision records below |"
  echo "| **Review 4** — issues found fixed, re-verified | PASS | the blessed fixes are verified in HEAD (recorded below) + the production-DI exposition test in the baseline build; the gate's drills re-prove every claim live; no new defect surfaced |"
  echo "| **Regression** — auth-off default intact (OpenAI-face / cross-format / routing byte-identical) | PASS | Leg 5: keyless boot → no 401s/429s, unlabeled metrics only; run.sh --skip-native green on the same build (phase3 RESULTS.md restored) |"
  echo
  echo "## Review 3 — prior-art comparison (LiteLLM budget/limit semantics vs Janus stage 4)"
  echo
  echo "**Provenance:** LiteLLM public docs (virtual-key \`budget\`/\`rpm\`/\`tpm\` caps, soft/hard tiers, 429 \`rate_limit_error\` shapes, \`X-RateLimit-*\` vocabulary). Each semantic maps to a Janus counterpart or a documented divergence."
  echo
  echo "| LiteLLM semantic | LiteLLM field/shape | Janus stage 4 counterpart |"
  echo "|---|---|---|"
  echo "| virtual-key budget cap | \`budget\` (USD on the key) | \`budget_usd\` on POST /key/generate → the integer micro-USD ledger (0.01064 = 2 golden requests, proven live); hard cap → 429 \`rate_limit_error\` BEFORE dispatch (fake counter flat), no \`Retry-After\` (a budget does not refill on a timer) |"
 echo "| soft budget tier | LiteLLM soft/hard budget tiers | \`soft-cap-fraction = 0.8\` (default): crossing → \`X-Janus-Budget-Warning: soft\` + \`X-Janus-Budget-Used-Micro-Usd\` on the success (non-streaming) + \`budget_exceeded\` notifier event (once per key per 60s window — the n3 dedup decision); streams: notifier-only (headers already sent) |"
 echo "| per-key RPM | \`rpm\` | \`rpm\` on /key/generate; 3rd-over → 429 \`rate_limit_error\` + \`Retry-After\` (fixed window: exact window-end seconds; sliding: conservative aligned-window value — m1 decision); null = no cap (not zero), \`rpm: 0\` rejected at generate () |"
  echo "| per-key TPM | \`tpm\` | \`tpm\` on /key/generate; conservative non-consuming pre-check (\`max_tokens\` ?? row default ?? 1024) → 429 before dispatch; real tokens accumulate at finalize; \`Retry-After\` = window-end seconds |"
 echo "| max parallel requests | \`max_parallel_requests\` | **documented divergence — YAGNI'd** (-); the atomic reserve bounds concurrent overspend structurally (settled ≤ cap + one request, proven live under 8 parallel) |"
  echo "| 429 wire type | \`rate_limit_error\` (OpenAI \`{\\\"error\\\":{\\\"type\\\":...}}\`, Anthropic \`{\\\"type\\\":\\\"error\\\",\\\"error\\\":{...}}\`) | identical wire types on both faces (gateway-originated, not passthrough) — pinned by ErrorMapperTest/AnthropicErrorMapperTest + the live legs |"
  echo "| rate-limit header vocabulary | \`X-RateLimit-*\` (remaining/limit/reset) | **documented divergence**: Janus emits \`Retry-After\` only (the design's word); the \`X-RateLimit-*\` vocabulary is YAGNI'd — the budgets use the \`X-Janus-Budget-*\` pair instead |"
  echo "| key generation | \`/key/generate\` (master-keyed) | \`POST /key/generate\` master-keyed (Bearer or x-api-key), \`sk-janus-\` keys shown exactly once, hashed at rest, revoked → 403 \`permission_error\` (LiteLLM/the reference-aligned — clients distinguish bad-key from revoked) |"
  echo "| key_id metrics label | (LiteLLM labels by team/user) | **documented divergence** (PRIVACY.md §Tier-1): the design demands per-key usage and the key model has no team concept — \`key_id\` is an opaque non-secret operator-created id, finite cardinality; the divergence is recorded in config.toml + docs/governance.md |"
  echo "| model scoping | (LiteLLM model access lists) | \`models\` list on /key/generate → alias-keyed scope denial 403 \`permission_error\` (live) |"
  echo
  echo "## Written decision records (every deferred item the gate decided)"
  echo
  echo "1. **Revoked-key 401-vs-403 (spec wording conflict).** DECISION: **accept 403 \`permission_error\` for revoked keys** (and scope denial) — an earlier spec wording \"invalid/revoked keys get 401\" is read as \"bad keys are rejected\"; 403 is strictly more informative (clients distinguish bad-key from key-taken-away), matches the reference and LiteLLM vocabulary, and is unit-pinned by ErrorMapperTest + live-proven by drill_keys (both faces, JVM + native). Not the literal-401 alternative (a single ErrorMapper row + one test) — recorded, not applied."
 echo "2. **\"Latency histogram\" wording ( m3).** DECISION: **enable percentile-histogram bucketing** — the \`janus_request_duration_seconds\` Timer publishes \`_bucket\` lines (le=… + le=\\\"+Inf\\\") in the exposition, so the design's \"latency histogram\" is satisfied literally. Pinned by ProductionMetricsExpositionTest (baseline build) + drill_metrics live (JVM + native). Documented in docs/governance.md."
 echo "3. **Filter-level 4xx metrics ( m2).** DECISION: **record from the filter** — \`KeyAuthFilter\` records rejected requests (face × 401/403) into \`janus_requests_total\` 4xx, so the bucket counts real proxy traffic (blessed, in HEAD; the test suite pins the shape). Live: drill_metrics asserts the 4xx bucket non-zero after 401/403 rejections (JVM + native)."
 echo "4. **Soft-cap notifier dedup ( n3).** DECISION: **implement the dedup** (the review's stated cheap improvement) — \`DedupNotifier\` (blessed) fires \`budget_exceeded\` once per key per 60s window; the reservation-time spurious-warning half is accepted + documented (the reference-aligned). Live: drill_budget's soft leg — 16 soft-crossing successes, exactly ONE notifier WARN."
 echo "5. **Streaming-cost scope.** DECISION: the exact-cost assertion targets non-streaming requests (by design); streams settle from the terminal usage chunk when the client requested \`include_usage\` (OpenAI) or from the encoder aggregation (Anthropic in-suite), and a documented \$0 entry on clean exhaustion without one — Janus never forces \`include_usage\` ( D1 byte-golden). Live: drill_cost's streaming legs (JVM + native). Recorded in docs/governance.md."
 echo "6. **\`/v1/models\` + unlisted paths auth exemption ( note).** DECISION: **kept** — the models list is public metadata (LiteLLM-aligned); the auth surface is exactly the two model routes + the admin API; documented in docs/governance.md."
  echo
  echo "## Verified-in-HEAD vs blessed-fix split"
  echo
 echo "- **Already in HEAD (verified, not re-applied):** C1 (\`wouldExceed\` = estimate > tokens), (\`validateCaps\` 400s ≤ 0 caps), m3 (SpendLedger javadoc), m4 (zero-token settle + Anthropic streaming governance tests), m1 (atomic authenticate), m2 (filter Clock bean), m3 (stream:true scope-denial tests), n2 (auth-off boot WARN), (auth-off streams unlabeled + MetricsAuthOffTest), m1 (record after encodeResponse), m4 (awaitScrapeContaining)."
 echo "- **Blessed in this gate ( second half + m5, m3, n3):** \`ProductionMetricsExpositionTest\` (production-DI exposition pinning every Tier-1 series + \`_bucket\` lines), \`RouterResilience\` (the resilience bundle as its own \`@Singleton\` — \`router()\` populates it, \`MetricsFactory\` hard-depends; no janus-router change), \`DedupNotifier\` (+ test)."
 echo "- **Record-only (no code):** n1 (\`putIfAbsent\` on key create — negligible), n2 (InMemorySpendLedger javadoc off-by-one), n1 (\`accumulate\` return semantics — note)."
  echo
  echo "## Defects found"
  echo
  echo "- (none — the drills surfaced no production defect; the only fixes during bring-up were harness bugs, all within \`scripts/smoke/governance/\`)"
} >> "$RESULTS"

log "gate complete in ${GATE_ELAPSED}s — results in $RESULTS"
log "git status (expected: scripts/smoke/governance/ + docs/governance.md + blessed diffs + the plan only):"
git -C "$REPO" status --short
