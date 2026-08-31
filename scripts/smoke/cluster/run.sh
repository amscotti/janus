#!/usr/bin/env bash
# =============================================================================
# run.sh — three-node Janus cluster behind HAProxy (Docker Compose).
#
# Offline (always): postgres + 3 nodes + HAProxy + golden fake. Hits the LB
# for keys, both chat faces, Responses, RPM/TPM/budget, soft-cap, revoke,
# privacy, spend==per-node metrics, and kill-one-node failover.
#
# Live (when provider keys are set, unless --skip-live): same topology with
# config.cluster.live.toml — short real-API chats through the LB.
#
# Agents (when claude/codex are on PATH and live keys exist, unless
# --skip-agents): Claude Code and Codex pointed at the HAProxy listener.
#
# Usage:
# scripts/smoke/cluster/run.sh [--skip-build] [--skip-offline] [--skip-live] [--skip-agents]
#
# Env:
# LIVE_MODELS_CSV override the auto-detected live model list (comma-separated
# drill keys: flash|sonnet|luna|pro|vision|grok|kimi|minimax|
# muse|glm|gptoss|sonar|gemini|glm53) — e.g. exclude a
# provider whose account is temporarily out of quota.
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO"
HARNESS="$REPO/scripts/smoke/cluster"
DEPLOY="$REPO/deploy"
COMPOSE_FILE="$DEPLOY/docker-compose.cluster.yml"
RUN_DIR="$HARNESS/.run"
RESULTS="$HARNESS/RESULTS.md"
COMPOSE="${COMPOSE:-docker compose}"
IMAGE_TAG="${IMAGE_TAG:-janus:dev}"
MASTER_KEY="${JANUS_MASTER_KEY:-cluster-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')}"
SKIP_BUILD=0
SKIP_OFFLINE=0
SKIP_LIVE=0
SKIP_AGENTS=0
for arg in "$@"; do
  [[ "$arg" == "--skip-build" ]] && SKIP_BUILD=1
  [[ "$arg" == "--skip-offline" ]] && SKIP_OFFLINE=1
  [[ "$arg" == "--skip-live" ]] && SKIP_LIVE=1
  [[ "$arg" == "--skip-agents" ]] && SKIP_AGENTS=1
done

log() { printf '[cluster] %s\n' "$*" >&2; }
die() { printf '[cluster] FATAL: %s\n' "$*" >&2; exit 1; }

# Public LB on the host. Default 8080 matches docs; if that port is already
# bound (another local app), fall back to 18080 unless the operator set the env.
if [[ -z "${JANUS_CLUSTER_LB_PORT:-}" ]]; then
  if command -v lsof >/dev/null && lsof -nP -iTCP:8080 -sTCP:LISTEN >/dev/null 2>&1; then
    JANUS_CLUSTER_LB_PORT=18080
  else
    JANUS_CLUSTER_LB_PORT=8080
  fi
fi
if command -v lsof >/dev/null && lsof -nP -iTCP:"$JANUS_CLUSTER_LB_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "host port ${JANUS_CLUSTER_LB_PORT} is already in use — set JANUS_CLUSTER_LB_PORT to a free port"
fi
export JANUS_CLUSTER_LB_PORT
LB="http://127.0.0.1:${JANUS_CLUSTER_LB_PORT}"
if [[ "$JANUS_CLUSTER_LB_PORT" != "8080" ]]; then
  log "host :8080 is in use — publishing HAProxy on :${JANUS_CLUSTER_LB_PORT}"
fi

export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null || true)}"
export JANUS_MASTER_KEY="$MASTER_KEY"
export IMAGE_TAG
command -v docker >/dev/null || die "docker required"
docker info >/dev/null 2>&1 || die "docker info FAILED (check the Docker socket)"
command -v curl >/dev/null || die "curl required"
command -v python3 >/dev/null || die "python3 required"

mkdir -p "$RUN_DIR"
: > "$RUN_DIR/fake.counters.json"
START="$(date +%s%N)"

# Per-run master key — do not interpolate JANUS_MASTER_KEY in the shipped
# compose file (empty ${} would override env_file).
OVERRIDE="$RUN_DIR/compose.key.yml"
cat > "$OVERRIDE" <<EOF
services:
  janus-1:
    environment:
      JANUS_MASTER_KEY: \${JANUS_MASTER_KEY}
  janus-2:
    environment:
      JANUS_MASTER_KEY: \${JANUS_MASTER_KEY}
  janus-3:
    environment:
      JANUS_MASTER_KEY: \${JANUS_MASTER_KEY}
EOF

compose() {
  # JANUS_CLUSTER_COUNTER_DIR: directory-mounted counter store (the compose
  # file mounts it at /app/counters; the fake writes fake.counters.json inside
  # it — a file-path bind of a missing host path would be auto-created as a
  # directory and shadow the counter file).
  (cd "$DEPLOY" && JANUS_CLUSTER_COUNTER_DIR="$RUN_DIR" \
    $COMPOSE -f "$COMPOSE_FILE" -f "$OVERRIDE" "$@")
}

compose_down() {
  compose --profile fake down -v --remove-orphans >/dev/null 2>&1 || true
}

cleanup() {
  compose_down
}
trap cleanup EXIT

wait_url() {
  local url="$1" label="$2" timeout="${3:-180}" i
  for i in $(seq 1 "$((timeout * 2))"); do
    if curl -sf "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  die "$label never became ready ($url)"
}

wait_pg() {
  local i
  for i in $(seq 1 60); do
    if docker inspect -f '{{.State.Health.Status}}' janus-cluster-postgres 2>/dev/null | grep -q healthy; then
      return 0
    fi
    sleep 1
  done
  die "postgres never became healthy"
}

run_drill() {
  local label="$1"; shift
  log "drill: $label"
  local out
  out="$("$@" 2>&1)" || { printf '%s\n' "$out" >&2; die "drill $label FAILED"; }
  printf '%s\n' "$out" | sed 's/^/    /' >> "$RESULTS"
}

boot_stack() {  # boot_stack <config-rel-to-deploy> <profiles...>
  local config="$1"; shift
  export JANUS_CLUSTER_CONFIG="$config"
  export JANUS_CLUSTER_COUNTER_DIR="$RUN_DIR"
  log "compose up postgres"
  compose "$@" up -d postgres > "$RUN_DIR/compose-pg.log" 2>&1 \
    || { tail -40 "$RUN_DIR/compose-pg.log" >&2; die "compose up postgres FAILED"; }
  wait_pg
  if [[ " $* " == *" --profile fake "* ]] || [[ " $* " == *" fake "* ]]; then
    log "compose up fake-upstream"
    compose --profile fake up -d fake-upstream > "$RUN_DIR/compose-fake.log" 2>&1 \
      || { tail -40 "$RUN_DIR/compose-fake.log" >&2; die "compose up fake FAILED"; }
    local i fake_ok=0
    for i in $(seq 1 60); do
      if docker inspect -f '{{.State.Health.Status}}' janus-cluster-fake 2>/dev/null | grep -q healthy; then
        fake_ok=1
        break
      fi
      sleep 1
    done
    [[ "$fake_ok" -eq 1 ]] || die "fake-upstream never became healthy (see docker logs janus-cluster-fake)"
  fi
  log "compose up janus-1/2/3 + haproxy"
  compose "$@" up -d janus-1 janus-2 janus-3 haproxy > "$RUN_DIR/compose-up.log" 2>&1 \
    || { tail -80 "$RUN_DIR/compose-up.log" >&2; die "compose up nodes/lb FAILED"; }
  wait_url "http://127.0.0.1:18081/health/readiness" "janus-1" 180
  wait_url "http://127.0.0.1:18082/health/readiness" "janus-2" 180
  wait_url "http://127.0.0.1:18083/health/readiness" "janus-3" 180
  wait_url "${LB}/health/readiness" "haproxy" 60
  log "stack healthy (3 nodes + LB)"
}

# ---------------------------------------------------------------- results
{
  echo "# Cluster (HAProxy + 3 nodes + Postgres) results"
  echo
  echo "- **Date:** $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo "- **Commit:** $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "- **Image:** \`$IMAGE_TAG\`"
  echo "- **LB:** ${LB}  ·  nodes :18081 :18082 :18083"
} > "$RESULTS"

# ---------------------------------------------------------------- image
if [[ "$SKIP_BUILD" -eq 1 ]]; then
  docker image inspect "$IMAGE_TAG" >/dev/null 2>&1 \
    || die "--skip-build but image $IMAGE_TAG is missing"
  echo "- **Image build:** SKIPPED (\`--skip-build\`, \`$IMAGE_TAG\` present)" >> "$RESULTS"
else
  log "docker build -f deploy/Dockerfile -t $IMAGE_TAG (native compile; minutes)"
  docker build -f "$DEPLOY/Dockerfile" -t "$IMAGE_TAG" . > "$RUN_DIR/build.log" 2>&1 \
    || { tail -40 "$RUN_DIR/build.log" >&2; die "docker build FAILED"; }
  echo "- **Image build:** PASS (\`$IMAGE_TAG\`)" >> "$RESULTS"
fi

# ================================================================ fake cluster
if [[ "$SKIP_OFFLINE" -eq 1 ]]; then
  echo "- **Offline cluster:** SKIPPED (\`--skip-offline\`)" >> "$RESULTS"
else
  log "=== offline cluster (fake upstream) ==="
  compose_down
  : > "$RUN_DIR/fake.counters.json"
  boot_stack "./config/config.cluster.toml" --profile fake
  {
    echo
    echo "## Offline cluster (golden fake)"
    echo
  } >> "$RESULTS"
  run_drill "cluster through HAProxy" python3 "$HARNESS/drill_cluster.py" \
    --lb "$LB" \
    --nodes http://127.0.0.1:18081,http://127.0.0.1:18082,http://127.0.0.1:18083 \
    --master-key "$MASTER_KEY" \
    --pg-container janus-cluster-postgres \
    --counter "$RUN_DIR/fake.counters.json"
  echo "- **Offline cluster:** PASS — health, faces, RPM, TPM, budget, soft-cap, revoke, privacy, spend==metrics, kill-node failover" >> "$RESULTS"
fi

# ================================================================ live + agents
have_key() {
  local name="$1"
  # Read the name from the process environment or the repo-root.env without
  # printing the value.
  if [[ -n "${!name:-}" ]]; then
    return 0
  fi
  if [[ -f "$REPO/.env" ]] && grep -qE "^${name}=" "$REPO/.env"; then
    local val
    val="$(grep -E "^${name}=" "$REPO/.env" | head -1 | cut -d= -f2-)"
    [[ -n "$val" ]]
    return
  fi
  return 1
}

LIVE_MODELS=()
if [[ -n "${LIVE_MODELS_CSV:-}" ]]; then
  # Operator override (comma-separated drill keys) — e.g. exclude a provider whose
  # account is temporarily out of quota without editing the harness.
  IFS=',' read -r -a LIVE_MODELS <<< "$LIVE_MODELS_CSV"
else
  have_key DEEPSEEK_API_KEY && LIVE_MODELS+=("flash" "pro" "vision")
  have_key ANTHROPIC_API_KEY && LIVE_MODELS+=("sonnet")
  have_key OPENAI_API_KEY && LIVE_MODELS+=("luna")
  have_key XAI_API_KEY && LIVE_MODELS+=("grok")
  have_key OPENROUTER_API_KEY && LIVE_MODELS+=("kimi" "minimax" "glm53")
  have_key META_API_KEY && LIVE_MODELS+=("muse")
  have_key FIREWORKS_API_KEY && LIVE_MODELS+=("glm")
  have_key GROQ_API_KEY && LIVE_MODELS+=("gptoss")
  have_key PERPLEXITY_API_KEY && LIVE_MODELS+=("sonar")
  have_key GEMINI_API_KEY && LIVE_MODELS+=("gemini")
fi

if [[ "$SKIP_LIVE" -eq 1 ]]; then
  echo "- **Live cluster:** SKIPPED (\`--skip-live\`)" >> "$RESULTS"
elif [[ ${#LIVE_MODELS[@]} -eq 0 ]]; then
  echo "- **Live cluster:** SKIPPED (no DEEPSEEK_API_KEY / ANTHROPIC_API_KEY / OPENAI_API_KEY)" >> "$RESULTS"
  log "no live provider keys — skipping live + agents"
else
  log "=== live cluster (${LIVE_MODELS[*]}) ==="
  compose_down
  # Load.env into this shell so compose env_file is not the only channel
  # and so agent CLIs see nothing they shouldn't. Values stay in the env.
  if [[ -f "$REPO/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "$REPO/.env"
    set +a
  fi
  export JANUS_MASTER_KEY="$MASTER_KEY"
  boot_stack "./config/config.cluster.live.toml"
  IFS=','
  MODEL_CSV="${LIVE_MODELS[*]}"
  unset IFS
  {
    echo
    echo "## Live cluster (real providers through HAProxy)"
    echo
  } >> "$RESULTS"
  run_drill "live APIs through HAProxy" python3 "$HARNESS/drill_live.py" \
    --lb "$LB" \
    --master-key "$MASTER_KEY" \
    --models "$MODEL_CSV"
  echo "- **Live cluster:** PASS (\`${LIVE_MODELS[*]}\`)" >> "$RESULTS"

  if [[ "$SKIP_AGENTS" -eq 1 ]]; then
    echo "- **Agents:** SKIPPED (\`--skip-agents\`)" >> "$RESULTS"
  else
    LIVE_KEY="$(curl -sf -X POST "${LB}/key/generate" \
      -H "x-api-key: $MASTER_KEY" -H 'Content-Type: application/json' \
      -d '{"name":"cluster-agents","models":["claude-sonnet-5","gpt-5.6-luna","gpt-5.6","deepseek-v4-flash"],"budget_usd":5.0}' \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')"
    WANT_CLAUDE=0
    WANT_CODEX=0
    have_key ANTHROPIC_API_KEY && command -v claude >/dev/null && WANT_CLAUDE=1
    have_key OPENAI_API_KEY && command -v codex >/dev/null && WANT_CODEX=1
    if [[ "$WANT_CLAUDE" -eq 0 && "$WANT_CODEX" -eq 0 ]]; then
      echo "- **Agents:** SKIPPED (no claude/codex CLI or matching key)" >> "$RESULTS"
    else
      {
        echo
        echo "## Coding agents through HAProxy"
        echo
      } >> "$RESULTS"
      run_drill "claude/codex through the cluster" env REPO="$REPO" bash "$HARNESS/drill_agent.sh" \
        "$LB" "$LIVE_KEY" "$RUN_DIR" "$WANT_CLAUDE" "$WANT_CODEX"
      echo "- **Agents:** PASS (claude=$WANT_CLAUDE codex=$WANT_CODEX)" >> "$RESULTS"
    fi
  fi
fi

ELAPSED=$(( ($(date +%s%N) - START) / 1000000000 ))
{
  echo
  echo "## Summary"
  echo
  echo "- **Wall-clock:** ${ELAPSED}s"
  echo "- **Verdict:** PASS"
} >> "$RESULTS"
log "cluster gate complete in ${ELAPSED}s — $RESULTS"
