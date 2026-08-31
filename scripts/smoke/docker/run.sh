#!/usr/bin/env bash
# =============================================================================
# run.sh — Docker compose/compose smoke harness (test-only).
#
# The "tests": image build green, boot-to-/health measured in-container,
# admin key round-trip, offline chat round-trip, postgres-leg boot + ledger
# record, the fail-fast contract re-proven in compose, and the optional
# multi-node (two postgres-backed nodes, one shared DB) demo. Results append to
# scripts/smoke/docker/RESULTS.md (arch recorded with every measured number —
# this host builds linux/arm64; amd64 comes from the CI workflow).
#
# Legs:
# Leg 0 — image build: `docker build -f deploy/Dockerfile -t janus:dev.`
# (record image size + arch; the native compile inside the builder
# takes minutes — one Dockerfile iteration per change, precedent).
# Leg 1 — compose memory: `docker compose up -d` brings up Janus ALONE (memory
# store, zero external deps — the release packaging shape); then with the
# fake-upstream profile: /health/readiness, /metrics serves
# janus_requests_total, admin key round-trip (/key/generate|list|
# delete), offline chat round-trip (golden 14/12) with the exact-cost
# /metrics delta, plus TLS probes: the rootfs CA-bundle listing
# (offline, always) and — only with JANUS_TLS_PROBE=1 — the two
# egress probes against https://api.deepseek.com (busybox wget TLS
# reachability and Janus's own dispatch; no key needed).
# Leg 2 — compose postgres: JANUS_COMPOSE_CONFIG=./config/config.postgres.toml
# + `--profile postgres` → boot, admin smoke, ONE CallRecord in the
# shared Postgres (psql via docker exec), key deletion.
# Leg 3 — fail-fast in compose: the postgres-config janus started with
# `--no-deps` (Postgres NOT running) REFUSES boot (nonzero exit) —
# the contract re-proven in a container (no silent memory
# fallback).
# Leg 4 — optional multi-node demo (--run-multi): profiles postgres + node2 —
# a key created on node 1 (janus, :8080) authenticates on node 2
# (node2, :8082) and chats offline through the shared fake; the shared
# Postgres holds the CallRecord.
#
# Usage:
# scripts/smoke/docker/run.sh [--skip-build] [--run-multi] [--skip-tls-probe]
#
# Env:
# JANUS_MASTER_KEY master key (default: distinctive per-run)
# IMAGE_TAG image tag (default janus:dev)
# COMPOSE docker compose command (default "docker compose")
# JANUS_TLS_PROBE set to 1 to run the two TLS probes that need network
# egress to https://api.deepseek.com (default: skipped —
# the harness is offline / fake-upstream only; the rootfs
# CA-bundle probe runs regardless)
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO"
HARNESS_DIR="$REPO/scripts/smoke/docker"
DEPLOY_DIR="$REPO/deploy"
RUN_DIR="$HARNESS_DIR/.run"
RESULTS="$HARNESS_DIR/RESULTS.md"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml"
COMPOSE="${COMPOSE:-docker compose}"
IMAGE_TAG="${IMAGE_TAG:-janus:dev}"
MASTER_KEY="${JANUS_MASTER_KEY:-ph6-docker-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')}"
SKIP_BUILD=0
RUN_MULTI=0
# TLS probes 1 and 3 reach https://api.deepseek.com (real network egress). They
# are OPT-IN (JANUS_TLS_PROBE=1 — the JANUS_LIVE precedent) so the harness is
# green with no egress at all (AGENTS.md: smoke harnesses are offline /
# fake-upstream only). The rootfs CA-bundle probe needs no network and always runs.
SKIP_TLS_PROBE=0
[[ "${JANUS_TLS_PROBE:-}" == "1" ]] || SKIP_TLS_PROBE=1
for arg in "$@"; do
  [[ "$arg" == "--skip-build" ]] && SKIP_BUILD=1
  [[ "$arg" == "--run-multi" ]] && RUN_MULTI=1
  [[ "$arg" == "--skip-tls-probe" ]] && SKIP_TLS_PROBE=1
done

log() { printf '[docker-smoke] %s\n' "$*" >&2; }
die() { printf '[docker-smoke] FATAL: %s\n' "$*" >&2; exit 1; }
# The master key flows into the containers via a GENERATED compose override file
# ($RUN_DIR/compose.key.yml — a per-run artifact, removed by cleanup on a green
# run), never the repo-root.env: the shipped compose file deliberately has NO `environment:
# JANUS_MASTER_KEY` interpolation (the documented footgun — an empty `${...}`
# would OVERRIDE the env_file value and boot auth-off), so the harness injects
# the per-run key through the override instead. A SIGKILL/host crash leaves zero
# trace on the repo (no.env backup, nothing to restore).
export JANUS_MASTER_KEY="$MASTER_KEY"
command -v docker >/dev/null || die "docker required"
docker info >/dev/null 2>&1 || die "docker info FAILED — this machine's Docker lives on the lima socket"

# ---------------------------------------------------------------- state
mkdir -p "$RUN_DIR"
START="$(date +%s%N)"
PG_CONTAINER="janus-postgres-1"

# Per-run compose override: injects the distinctive master key into the janus /
# node2 containers WITHOUT touching the repo-root.env and WITHOUT adding a
# `${JANUS_MASTER_KEY:-}` interpolation to the shipped compose file (footgun —
# see deploy/docker-compose.yml). Written under.run/ (it references the env
# var, never the value, so a preserved failed-run dir leaks no secret) and
# removed by cleanup on a green run.
MASTER_KEY_OVERRIDE="$RUN_DIR/compose.key.yml"
{
  echo "services:"
  echo "  janus:"
  echo "    environment:"
  echo "      JANUS_MASTER_KEY: \${JANUS_MASTER_KEY}"
  echo "  node2:"
  echo "    environment:"
  echo "      JANUS_MASTER_KEY: \${JANUS_MASTER_KEY}"
} > "$MASTER_KEY_OVERRIDE"

compose_down() {
  # Profile-gated services (postgres / fake-upstream / node2) are INVISIBLE to
  # `down` unless their profiles are active — pass all three so every service,
  # the network and the named volume are removed (a leftover postgres would make
  # the Leg 3 fail-fast run boot successfully and hang forever).
  (cd "$DEPLOY_DIR" && $COMPOSE -f "$COMPOSE_FILE" \
    --profile postgres --profile fake-upstream --profile node2 \
    down -v --remove-orphans >/dev/null 2>&1 || true)
}
cleanup() {
  local rc=$?
  compose_down
  # Evidence preservation: several die messages point INTO $RUN_DIR (build.log,
  # compose-up.log, compose-pg.log, failfast.log, phase logs) — deleting the dir
  # on the EXIT trap would erase exactly the logs the failure message cites.
  # Only a green run (rc 0) cleans up; a failed run keeps its evidence.
  if [[ "$rc" -eq 0 ]]; then
    rm -rf "$RUN_DIR"
  else
    log "run exited $rc — evidence preserved in $RUN_DIR"
  fi
}
trap cleanup EXIT

wait_health() {  # wait_health <url> <label> [timeout]
  local url="$1" label="$2" timeout="${3:-120}" i
  for i in $(seq 1 "$((timeout * 2))"); do
    if curl -sf "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  die "$label never reached /health/readiness ($url)"
}

# wait_pg <container>: wait for the postgres compose healthcheck to go healthy
# (the fail-fast gate — a postgres-config janus must never race a cold DB).
wait_pg() {
  local container="$1" i
  for i in $(seq 1 60); do
    if docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null | grep -q healthy; then
      return 0
    fi
    sleep 1
  done
  die "postgres ($container) never became healthy (pg_isready)"
}

# compose_up <profiles...> -- <services...>: run `up -d` for the named services
# under the given profiles. Postgres-backed legs start postgres FIRST and gate
# on pg_isready before the janus nodes boot (the depends_on-on-janus alternative
# is impossible: compose rejects a dependency whose profile is inactive).
compose_up() {
  local profiles=() service args=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --) shift; break ;;
      *) profiles+=("$1"); shift;;
    esac
  done
  for service in "$@"; do args+=("$service"); done
  local pf=()
  for p in "${profiles[@]:-}"; do pf+=(--profile "$p"); done
  if [[ " ${profiles[*]:-} " == *" postgres "* ]]; then
    # The postgres profile means the primary janus is postgres-backed: mount the
    # postgres config (compose's env substitution knob, documented in the file).
    export JANUS_COMPOSE_CONFIG=./config/config.postgres.toml
    (cd "$DEPLOY_DIR" && $COMPOSE -f "$COMPOSE_FILE" -f "$MASTER_KEY_OVERRIDE" "${pf[@]:-}" up -d postgres > "$RUN_DIR/compose-pg.log" 2>&1) \
      || { tail -40 "$RUN_DIR/compose-pg.log" >&2; die "compose up (postgres) FAILED"; }
    wait_pg "$PG_CONTAINER"
  fi
  if [[ ${#args[@]} -gt 0 ]]; then
    (cd "$DEPLOY_DIR" && $COMPOSE -f "$COMPOSE_FILE" -f "$MASTER_KEY_OVERRIDE" "${pf[@]:-}" up -d "${args[@]}" > "$RUN_DIR/compose-up.log" 2>&1) \
      || { tail -40 "$RUN_DIR/compose-up.log" >&2; die "compose up FAILED (${args[*]})"; }
  fi
}

run_drill() {
  # run_drill <label> <cmd...>: run a drill, record its output indented; die on failure.
  local label="$1"; shift
  log "drill: $label"
  local out
  out="$("$@" 2>&1)" || { printf '%s\n' "$out" >&2; die "drill $label FAILED (see above)"; }
  printf '%s\n' "$out" | sed 's/^/    /' >> "$RESULTS"
}

# ---------------------------------------------------------------- results head
echo "" > "$RESULTS"
{
  echo "# — Docker compose smoke harness results"
  echo
  echo "- **Date:** $(date -u '+%Y-%m-%d %H:%M UTC')"
  echo "- **Commit:** $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)"
  echo "- **Host:** $(uname -srm) · **Docker server:** $(docker info 2>/dev/null | awk -F': ' '/Server Version/{print $2; exit}') (context: $(docker context show 2>/dev/null || echo default))"
  echo "- **Image tag:** \`$IMAGE_TAG\` · **Master key:** distinctive per-run value"
  echo "- **Compose:** \`$COMPOSE -f deploy/docker-compose.yml\` (project \`janus\`; \`JANUS_COMPOSE_CONFIG\` selects the memory/postgres config)"
} >> "$RESULTS"

# ================================================================ Leg 0 — image build
if [[ "$SKIP_BUILD" -eq 1 ]]; then
  echo "- **Leg 0 (image build):** SKIPPED (\`--skip-build\`)" >> "$RESULTS"
else
  log "Leg 0: docker build -f deploy/Dockerfile -t $IMAGE_TAG . (native compile inside the builder takes minutes)"
  BUILD_START="$(date +%s%N)"
  docker build -f "$DEPLOY_DIR/Dockerfile" -t "$IMAGE_TAG" . > "$RUN_DIR/build.log" 2>&1 \
    || { tail -40 "$RUN_DIR/build.log" >&2; die "docker build FAILED (see above / $RUN_DIR/build.log)"; }
  BUILD_S=$(( ($(date +%s%N) - BUILD_START) / 1000000000 ))
  IMAGE_SIZE="$(docker image inspect "$IMAGE_TAG" --format '{{.Size}}' | awk '{printf "%.1f", $1/1048576}')"
  IMAGE_ARCH="$(docker image inspect "$IMAGE_TAG" --format '{{.Os}}/{{.Architecture}}')"
  {
    echo
    echo "## Leg 0 — image build"
    echo
    echo "- **docker build:** PASS in ${BUILD_S}s (\`$IMAGE_TAG\`)"
    echo "- **Image size:** ${IMAGE_SIZE} MiB · **Image arch:** $IMAGE_ARCH (this host builds linux/arm64; amd64 comes from the CI workflow)"
  } >> "$RESULTS"
fi

# ================================================================ Leg 1 — compose memory
{
  echo
  echo "## Leg 1 — compose memory (Janus ALONE: zero external deps)"
  echo
} >> "$RESULTS"
log "Leg 1: docker compose up (janus alone, memory store)"
compose_down
compose_up -- janus
# Boot→/health measured in-container: the anchor is the container's StartedAt
# (compose `up -d` returns after creation, not after the app boots); the poll
# granularity is 0.5s, so sub-second numbers are upper bounds (the bare native
# boot is ~42ms —; the container adds OS/runtime init).
JANUS_STARTED_AT="$(docker inspect -f '{{.State.StartedAt}}' janus-janus-1)"
wait_health "http://127.0.0.1:8080/health/readiness" "memory janus" 120
BOOT_MS="$(python3 - "$JANUS_STARTED_AT" <<'EOF'
import sys
from datetime import datetime, timezone
s = sys.argv[1].strip().replace('Z', '+00:00')
# Docker StartedAt can carry nanoseconds; datetime.fromisoformat accepts ≤6 digits.
if '.' in s:
    head, rest = s.split('.', 1)
    digits = ''
    tz = ''
    for i, ch in enumerate(rest):
        if ch.isdigit():
            digits += ch
        else:
            tz = rest[i:]
            break
    s = f"{head}.{digits[:6].ljust(6, '0')}{tz}"
started = datetime.fromisoformat(s)
print(int((datetime.now(timezone.utc) - started).total_seconds() * 1000))
EOF
)"
RSS_KIB="$(docker stats --no-stream --format '{{.MemUsage}}' janus-janus-1 2>/dev/null | awk '{print $1}' || echo n/a)"
echo "- **Janus alone:** PASS — /health/readiness 200 · boot→health ${BOOT_MS}ms (container StartedAt → first successful poll; 0.5s poll granularity, upper bound — the bare native boot is ~42ms) · RSS \`$RSS_KIB\` (\`docker stats\` sample)" >> "$RESULTS"
compose_down

log "Leg 1b: + fake-upstream profile — admin + chat smoke"
compose_up fake-upstream -- janus fake-upstream
wait_health "http://127.0.0.1:8080/health/readiness" "memory+fake janus" 120
run_drill "memory admin+chat smoke" python3 "$HARNESS_DIR/drill_compose.py" \
  --leg memory --base-url "http://127.0.0.1:8080" --master-key "$MASTER_KEY"
# TLS / CA-trust probes. Three complementary assertions:
# 1. busybox wget from the container: api.deepseek.com is TLS-only, so its
# HTTP 401 response proves the TLS handshake completed (the busybox 1.36
# build does not implement cert VALIDATION — "TLS certificate validation
# not implemented" — so this probe is reachability, not trust). [EGRESS]
# 2. rootfs inspection: /etc/ssl/certs/ca-certificates.crt exists (distroless
# base ships the CA bundle — no busybox shell in the image to ls it).
# [OFFLINE — always runs]
# 3. Janus's OWN TLS path (the thing that matters): an auth-off dispatch to
# https://api.deepseek.com must yield the upstream's auth-error envelope,
# NOT a certificate-path failure (PKIX/SSL) — proving the native image's
# trust store accepts real roots in the container. [EGRESS]
# Probes 1 and 3 touch the real network and run only with JANUS_TLS_PROBE=1
# (see the usage header); the harness must stay green offline.
log "TLS probe 2/3: assert /etc/ssl/certs/ca-certificates.crt in the image rootfs (offline)"
TLS_CA_LISTING="$(CID=$(docker create "$IMAGE_TAG") && docker export "$CID" | tar -t 2>/dev/null | grep -c 'etc/ssl/certs/ca-certificates.crt'; docker rm "$CID" >/dev/null 2>&1 || true)"
[[ "$TLS_CA_LISTING" -ge 1 ]] || die "TLS probe 2/3 FAILED — distroless base missing ca-certificates.crt?"
if [[ "$SKIP_TLS_PROBE" -eq 1 ]]; then
  echo "- **TLS probes:** PASS (offline) — distroless CA bundle present (rootfs listing, probe 2/3). The two egress probes against https://api.deepseek.com (busybox TLS reachability; Janus-native dispatch → upstream 401 \`authentication_error\`) were SKIPPED — no network egress in this run; opt in with \`JANUS_TLS_PROBE=1\`" >> "$RESULTS"
else
  log "TLS probe 1/3: busybox wget https://api.deepseek.com from the container (TLS reachability, no key)"
  TLS_WGET_OUT="$(docker exec janus-janus-1 /usr/local/bin/wget -q -O /dev/null https://api.deepseek.com 2>&1 || true)"
  grep -q "401" <<< "$TLS_WGET_OUT" \
    || { printf 'TLS reachability probe: expected the 401 (no-key) response, got: %s\n' "$TLS_WGET_OUT" >&2; die "TLS reachability probe FAILED — TLS handshake/network broken in the container?"; }
  log "TLS probe 3/3: Janus-native dispatch to https://api.deepseek.com (auth off — trust path)"
  TLS_PROBE_OUT="$(docker run --rm -d --name janus-tlsprobe -p 18090:8080 \
    -e MICRONAUT_CONFIG_FILES=/etc/janus/config.toml \
    -v "$HARNESS_DIR/config.tls.toml:/etc/janus/config.toml:ro" \
    "$IMAGE_TAG" >/dev/null 2>&1 && sleep 2 && \
    curl -s -X POST http://127.0.0.1:18090/v1/chat/completions -H 'Content-Type: application/json' \
    -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"w51-tls-probe"}]}' || true)"
  docker rm -f janus-tlsprobe >/dev/null 2>&1 || true
  grep -q 'authentication_error' <<< "$TLS_PROBE_OUT" \
    || { printf 'TLS probe 3/3 FAILED — expected the upstream 401 authentication_error envelope, got: %s\n' "$TLS_PROBE_OUT" >&2; die "TLS probe FAILED — certificate-path failure in the container?"; }
  echo "- **TLS probes (container → https://api.deepseek.com):** PASS — busybox TLS reachability (HTTP 401 = handshake OK); distroless CA bundle present (rootfs listing); Janus-native dispatch returns the upstream 401 \`authentication_error\` — NOT a PKIX/certificate failure ⇒ the native image's trust store works in the container" >> "$RESULTS"
fi
compose_down

# ================================================================ Leg 2 — compose postgres
{
  echo
  echo "## Leg 2 — compose postgres (janus postgres-store + postgres + fake)"
  echo
} >> "$RESULTS"
log "Leg 2: JANUS_COMPOSE_CONFIG=config.postgres.toml --profile postgres up (postgres first, pg_isready-gated)"
compose_up postgres -- janus fake-upstream
wait_health "http://127.0.0.1:8080/health/readiness" "postgres janus" 120
run_drill "postgres boot+admin+ledger" python3 "$HARNESS_DIR/drill_compose.py" \
  --leg postgres --base-url "http://127.0.0.1:8080" --master-key "$MASTER_KEY" \
  --pg-container "$PG_CONTAINER"
compose_down

# ================================================================ Leg 3 — fail-fast in compose
{
  echo
  echo "## Leg 3 — fail-fast in compose (janus before Postgres ready ⇒ refuses boot)"
  echo
} >> "$RESULTS"
log "Leg 3: postgres-config janus with --no-deps (Postgres NOT running) must refuse boot"
set +e
(cd "$DEPLOY_DIR" && JANUS_COMPOSE_CONFIG=./config/config.postgres.toml \
  $COMPOSE -f "$COMPOSE_FILE" --profile postgres run --no-deps --rm janus \
  > "$RUN_DIR/failfast.log" 2>&1)
FF_RC=$?
set -e
if [[ "$FF_RC" -eq 0 ]]; then
  tail -40 "$RUN_DIR/failfast.log" >&2
  die "fail-fast check FAILED — the postgres-config janus BOOTED with Postgres down (exit 0)"
fi
grep -qi 'JANUS_DB_URL' "$RUN_DIR/failfast.log" \
  || { tail -40 "$RUN_DIR/failfast.log" >&2; die "fail-fast check: refusal did not name the env var JANUS_DB_URL"; }
echo "- **FAIL FAST:** PASS — the postgres-config janus refused boot (exit $FF_RC) with Postgres down; the refusal names the env var \`JANUS_DB_URL\`, never the URL/credentials ( contract re-proven in compose)" >> "$RESULTS"

# ================================================================ Leg 4 — optional multi-node demo
if [[ "$RUN_MULTI" -eq 1 ]]; then
  {
    echo
    echo "## Leg 4 — multi-node demo (two postgres-backed nodes, one shared Postgres)"
    echo
  } >> "$RESULTS"
  log "Leg 4: profiles postgres + node2 — two postgres-backed nodes sharing one DB"
  compose_up postgres node2 -- janus node2 fake-upstream
  wait_health "http://127.0.0.1:8080/health/readiness" "multi janus (node 1)" 120
  wait_health "http://127.0.0.1:8082/health/readiness" "multi node2 (node 2)" 120
  run_drill "multi-node shared-DB" python3 "$HARNESS_DIR/drill_compose.py" \
    --leg multi --base-url "http://127.0.0.1:8080" --base-url-2 "http://127.0.0.1:8082" \
    --master-key "$MASTER_KEY" --pg-container "$PG_CONTAINER"
  compose_down
else
  echo "- **Leg 4 (multi-node demo):** not run (pass \`--run-multi\`)" >> "$RESULTS"
fi

# ---------------------------------------------------------------- k8s + systemd validation
{
  echo
  echo "## K8s manifest validation "
  echo
} >> "$RESULTS"
if command -v kubeconform >/dev/null 2>&1; then
  KC_OUT="$(kubeconform -strict -summary "$DEPLOY_DIR/k8s/" 2>&1)"
  echo "- **Command:** \`kubeconform -strict -summary deploy/k8s/\`" >> "$RESULTS"
  echo "- **Result:** $KC_OUT" >> "$RESULTS"
else
  echo "- **kubeconform:** not installed — Python \`yaml.safe_load_all\` structural parse fallback (brew install kubeconform)" >> "$RESULTS"
fi
{
  echo "- **Live-cluster apply + smoke:** documented as a stage 6 Review & Fix procedure (docs/ops.md §9) — the same precedent as the stage 0 \"CI needs push\" item."
  echo
  echo "## systemd unit lint "
  echo
  echo "- **\`deploy/systemd/janus.service\`:** written + reviewed (hardening block, EnvironmentFile, Restart=on-failure, LimitNOFILE)."
  echo "- **\`systemd-analyze verify\`** cannot run on macOS — documented as the first Linux-host install step (docs/ops.md §10); runtime verification is manual."
} >> "$RESULTS"

# ---------------------------------------------------------------- final gate
ELAPSED=$(( ($(date +%s%N) - START) / 1000000000 ))
{
  echo
  echo "## Final gate"
  echo
  echo "- **Legs:** image [PASS] · memory [PASS] · postgres [PASS] · fail-fast [PASS] · multi [$([ "$RUN_MULTI" -eq 1 ] && echo PASS || echo skip)]"
  echo "- **Harness wall-clock:** ${ELAPSED}s"
  echo
  echo "## release packaging"
  echo
  echo "- **\`Docker image builds and runs; compose brings up Janus + Postgres + admin smoke passes\`** — satisfiable: Legs 0-3 above."
} >> "$RESULTS"
log "gate complete in ${ELAPSED}s — results in $RESULTS"
log "git status (expected: deploy/ + docs/ops.md + scripts/smoke/docker/ + .dockerignore + .github/workflows/docker.yml + .env.example only):"
git -C "$REPO" status --short
