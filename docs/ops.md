# Janus — Operations Runbook

> This is the operator's manual for a **production Janus** (the GraalVM native
> image, the `deploy/` artifacts in this repo, and the `deploy/k8s/` manifests).
> It covers deployment topologies, health checks, backup, upgrade, key/provider/
> DB rotation, monitoring, troubleshooting, and quick ops for the Docker, compose,
> Kubernetes, and systemd packaging. The config surface (every `[janus]` TOML
> section) lives in `docs/governance.md`, `docs/routing.md`, `docs/clustering.md`
> and the root `config.toml`; this runbook is about *operating*, not configuring.

---

## 1. Deployment topologies

Janus is a **single self-contained native executable**: one listener (8080) serves
both the data plane (`/v1/chat/completions`, `/v1/messages`, `/v1/responses`,
`/v1/models`) and the admin plane (`/key/generate|delete|list`, `/health*`,
`/metrics`) — there is **no** data/admin port split and no clustering port.
Multi-node is shared Postgres (see `docs/clustering.md`).

| Topology | Store | State | When to use |
|---|---|---|---|
| **Single-node, memory** | in-memory (no `[janus.store]`) | keys, budgets, rate-limit counters, spend, call records live **in the process only** | Dev, smoke, single-tenant low-stakes. **Restart ⇒ all keys/spend/calls are gone** (keys must be re-issued; there is no backup). |
| **Single-node + Postgres** | `[janus.store] type = "postgres"` | keys, counters, spend, calls persist in the shared DB | Production single-node: durable state, `pg_dump` backup (below), later scale-out is config-only. |
| **Multi-node + Postgres** | postgres (identical section, same DB, same master key) | shared: any node serves any key; spend aggregates cluster-wide | HA: an LB round-robins `/health`-healthy nodes; in-flight requests are never migrated (client/LB retries). |

> **Memory-store restart caveat.** With the in-memory store, a restart is a full
> state wipe: every issued key stops working (401), budgets/limits reset, and the
> call ledger is empty. Default `auth = "on"` **fails the boot** if
> `JANUS_MASTER_KEY` is missing — a forgotten env var must not silently run an
> unauthenticated admin API. Explicit `[janus.keys] auth = "off"` is the
> development/benchmark opt-out (loudly logged). Set the key before exposing Janus.

### Blocking-pool occupation by retry backoff

`POST /v1/chat/completions` and `POST /v1/messages` run on Micronaut's bounded blocking
pool, and the router's retry loop sleeps its backoff on that same thread. With the default
`backoff-max-ms = 2000` and `max-retries = 2`, a slow provider can pin a blocking-pool
thread for the full backoff window per request; a burst of such requests can exhaust the
pool and head-of-line-block unrelated chat traffic (the admin `/key/*` endpoints are not
on this pool). This is a capacity interaction, not a bug — size the pool (or lower
`max-retries`/`backoff-max-ms`, see `docs/routing.md` §tuning) for the worst-case
concurrent slow-upstream load you expect.

## 2. Health checks

Micronaut management exposes health and metrics **unauthenticated** (`sensitive = false`,
pinned in `application.toml`); every other management endpoint is locked down
(`[endpoints.all] sensitive = true` → the framework 401s them without a security module,
and the destructive `/stop` + `/refresh` are additionally disabled outright) and is
**never** reachable unauthenticated, even though the app's `KeyAuthFilter` treats
unlisted paths as exempt:

| Endpoint | Meaning | Probe use |
|---|---|---|
| `GET /health` | aggregated status (`{"status":"UP",...}` with details) | humans, LB, smoke harnesses |
| `GET /health/readiness` | ready to serve traffic (the app is fully booted) | **K8s readiness, compose HEALTHCHECK** |
| `GET /health/liveness` | process alive and serving | **K8s liveness** |
| `GET /metrics` | Prometheus text exposition (Tier-1, path pinned here — `/prometheus` 404s) | scrapers, dashboards |

Restricted (401 / disabled, never 200 unauthenticated): `POST /stop` (remote shutdown —
**disabled**), `POST /refresh` (config refresh — **disabled**), `GET /env`, `GET /beans`,
`GET /routes`, `GET /loggers`, `GET /threaddump`, `GET /info`. Pinned by
`ManagementEndpointExposureTest`.

```sh
curl -sf http://localhost:8080/health/readiness # 200 {"status":"UP"}
curl -sf http://localhost:8080/health/liveness # 200
curl -sf http://localhost:8080/health # {"status":"UP",...}
curl -sf http://localhost:8080/metrics | grep janus_requests_total
```

K8s probes (`deploy/k8s/deployment.yaml`) use readiness/liveness; the container
image HEALTHCHECK (`deploy/Dockerfile`) uses a busybox `wget` on `/health/
readiness` (distroless has no curl). **Alert candidates** (from the series,
see §7 for the full table):

- `janus_upstream_healthy{provider=...,base_url=...} == 0` sustained → upstream degraded/outage
 (base_url disambiguates two entries for one provider).
- `janus_upstream_breaker_state{provider=...,base_url=...} == 2` (OPEN) → trips every request to
 that upstream until the cooldown.
- `rate(janus_requests_total{status="5xx"}[5m])` > 0 → the store may be down
 (fail-closed 500s, §6) or an upstream is erroring through.
- `janus_cost_micro_usd_total` growth == 0 while traffic is non-zero → meter
 breakage (silent revenue/pricing drift).

## 3. Backup

### Postgres-backed store (the only durable state)

The janus database holds the `keys`, `rate_limits`, `spend`, `spend_entries`,
`calls`, `store_meta` and `schema_migrations` tables. `pg_dump` is the backup:

```sh
# plain SQL (restore with psql) — or --format=custom for pg_restore + partial restore
pg_dump -h <db-host> -U janus -d janus -Fc -f janus-$(date +%F).dump
```

**Hashed-key export semantics (DR).** Keys are stored as **hashes**. A backup restores the key *records* (id, scopes, budget, limits)
but **cannot recover the raw `sk-janus-...` values** — after a DR restore every
client must be **re-issued keys** (`POST /key/generate` with the master key; the
old full keys are unrecoverable and must be treated as lost). Budgets/limits/spend
history restore fine.

### Memory store — no backup possible

With no `[janus.store]` there is nothing to back up: keys/spend die with the
process. If a deployment needs durability it needs Postgres — there is no
in-memory export path by design.

## 4. Upgrade

### Rolling (multi-node, Postgres-backed — recommended)

1. Build/tag the new image (`docker build -f deploy/Dockerfile -t janus:<ver>.`
 or the CI workflow artifact).
2. Roll out **probe-gated** (the K8s Deployment is already
 `maxUnavailable: 0`): new pods must pass readiness (`/health/readiness`)
 before the old ones terminate; the fail-fast means a bad config fails the
 **new** pod, not the old.
3. `kubectl set image deployment/janus janus=janus:<ver>` (or `kubectl rollout
 restart` after `kubectl edit`), watch `kubectl rollout status`.
4. **Schema migration is automatic and idempotent**: `SchemaMigration` applies
 `db/migration/V*.sql` (`CREATE TABLE IF NOT EXISTS`, transaction-wrapped) at
 `PostgresCallStore` construction — there is no migration step, no downtime
 window, and re-runs are no-ops. The current schema is a single `V1__init.sql`.
 A build whose migration resources are missing (the native-image
 `db/migration/*.sql` registration) **refuses to boot** — an empty migration
 list fails fast instead of silently serving a schema-less store.

### Single-node swap (data-loss warning)

With the memory store, upgrading **is** a restart: all keys/spend are lost .
With Postgres, the swap is a short blip: stop the unit/container, replace the
binary/image, start. `systemd`/compose restarts are `on-failure`/`unless-stopped`
— the upgrade is the one case where a clean stop matters (`systemctl stop janus` /
`docker compose stop`), then start.

### Rollback

The previous **image** is the rollback unit (the binary embeds everything — no
data migrations to unwind; schema changes are additive `IF NOT EXISTS`). Re-point
the Deployment image tag / unit `ExecStart` binary to the previous artifact and
restart. **Do not** roll back a schema (there is no destructive migration to undo);
if a new version's code assumes a column the old version didn't create, restore
the DB from the §3 backup instead.

## 5. Rotation

| Secret | How | Notes |
|---|---|---|
| **Master key** (`JANUS_MASTER_KEY`) | 1) set the new value in the env/Secret; 2) restart every node (rolling); 3) re-issue any virtual keys whose admin ops need continuity (keys themselves are unaffected — they authenticate independently of the master key) | The master key only authenticates the admin API; rotating it does **not** invalidate issued virtual keys. |
| **Provider keys** (`DEEPSEEK_API_KEY`, …) | replace the Secret/`.env` value, then **restart the node** (rolling): provider keys resolve **once at boot** (`ModelListFactory`), and a K8s projected-secret rotation never touches a running container's env — so a restart is always required. Unresolvable at boot is fine for lazy providers (they fail only when dispatched); resolve before the provider's requests matter. | |
| **DB URL/credentials** (`JANUS_DB_URL/_USER/_PASS`) | 1) create the new role/URL in Postgres; 2) update the Secret/`.env`; 3) **restart every node** (the HikariCP pool reads the env at boot — the fail-fast proves the new URL works) | Never put the URL in the TOML config — env-var name only (the pattern). |

### Provider base URLs — SSRF trust boundary

`base-url` (per-entry or the `[janus.providers.<name>]` block default) is **operator
TOML config, never end-user input** — no request path writes it, so a remote caller
cannot steer the node's dispatch target. It is still a trust boundary: it names the
upstream endpoint the node sends every request for that alias to, so treat the
config/Secret mount as secret-equivalent (a config change silently redirects all
traffic for that alias). Janus validates the scheme on the config path — only
`http`/`https` are accepted; a scheme-less or non-http(s) `base-url` fails fast at
boot. Intranet `http://` hosts are operator-owned by design
(dev/edge topologies, e.g. Ollama on the LAN).

## 6. Troubleshooting

- **Boot refuses with an env-var/pool error** — the fail-fast contract is
 doing its job: `[janus.store] type = "postgres"` could not resolve
 `JANUS_DB_URL` or reach the DB, so the node refuses to start (nonzero exit;
 the message names the **env var**, never the URL/credentials). Fix the DB/env
 and restart. A node silently falling back to memory in a multi-node deployment
 would violate read-your-writes — this refusal is the safety.
- **Every store-touching request 500s mid-run** — Postgres went away: the store
 is **fail-closed** (clean `api_error` 500s, no hang/thread leak/retry storm).
 Fix the DB; HikariCP recovers the pool automatically once it is back (the
 pgdown drill pinned this).
- **401 on `/v1/*`** — the request key is missing/invalid/expired: key was issued
 by a node whose store was wiped (memory restart, §1), the key was deleted, or
 the client sent a raw provider key instead of a Janus `sk-janus-` key.
- **403 on `/v1/*`** — the key is valid but **revoked** or its model scope
 excludes the requested model alias.
- **401 on `/key/*`** — the admin call used the wrong master key (or none).
- **401 on everything, including keyless requests** — auth is ON (a master key
 resolves) and the request carries no/garbage key: expected with auth enabled.
- **429 with `Retry-After`** — rate limit/budget cap (per-key rpm/tpm/budget_usd);
 a budget cap 429 happens *before* dispatch.
- **504 `timeout` on upstream** — the upstream answered headers but the non-streaming
 body stalled past the 300 s body-read deadline (or never answered headers within the
 60 s header-arrival window). Adapter deadlines default to connect 10 s /
 header-arrival 60 s / body-read 300 s — `docs/architecture.md` § Timeouts); a
 provider that genuinely needs longer than 300 s for a completion, or a flaky provider
 whose 429/401 error bodies stall, is a provider-side issue (the head-derived status
 and `Retry-After` still surface correctly on a stalled error body).
- **Upstream errors but Janus is healthy** — look at
 `janus_upstream_healthy`/`janus_upstream_breaker_state` : the provider is
 unhealthy or the breaker is OPEN; check the provider's own status/keys.

## 7. Monitoring

`/metrics` is Prometheus text, **always on, Tier-1 only** (no prompt/response
text, no model alias, no request ids — `docs/governance.md` §privacy). Series:

| Series | Type | Labels |
|---|---|---|
| `janus_requests_total` | Counter | `face` (openai\|anthropic\|responses\|admin — the last for the `/key/*` operations and master-key rejections on those routes) × `status` (2xx\|4xx\|5xx; a stream ending with a mid-stream error frame/stall counts 5xx, never 2xx) |
| `janus_request_duration_seconds` | Timer | `face` — `_bucket` histogram + count/sum/max |
| `janus_ledger_write_seconds` | Timer | (unlabeled) — call-ledger store-write duration, `_bucket` histogram + count/sum; once per write attempt (success and contained failure alike) |
| `janus_tokens_in_total` / `janus_tokens_out_total` | Counter | (unlabeled) |
| `janus_cost_micro_usd_total` | Counter | (unlabeled; exact integer micro-USD) |
| `janus_key_requests_total` / `janus_key_tokens_in_total` / `janus_key_tokens_out_total` / `janus_key_cost_micro_usd_total` | Counter | `key_id` |
| `janus_upstream_healthy` | Gauge | `provider` × `base_url` (1 = dispatch-eligible) |
| `janus_upstream_breaker_state` | Gauge | `provider` × `base_url` (0=CLOSED, 1=HALF_OPEN, 2=OPEN) |

Cluster totals = **sum of per-node scrapes**; the DB (`spend`/`calls`) is the
authoritative cluster ledger (per-node metrics sum == DB total). Alert
suggestions: see §2.

## 8. Docker / compose quick ops

```sh
# Build the image (builder → distroless runtime; BuildKit caches)
docker build -f deploy/Dockerfile -t janus:dev.

Released images are published on `v*` tags by the Docker workflow:
`ghcr.io/amscotti/janus:<version>` — multi-arch (linux/amd64 from the x64
runner, linux/arm64 from GitHub's native arm64 runner; merged into one
manifest, no QEMU) with `:latest` tracking the newest tag. The k8s manifest
references it directly, so a cluster install needs no local build; arm64
hosts can also build it natively (`docker build -f deploy/Dockerfile` —
the M-series smoke harness proves that path).

# Run (config via MICRONAUT_CONFIG_FILES — NEVER --config in the image)
docker run --rm -p 8080:8080 \
 -e MICRONAUT_CONFIG_FILES=/etc/janus/config.toml \
 -v "$PWD/config.toml:/etc/janus/config.toml:ro" \
 -e JANUS_MASTER_KEY=... janus:dev

# Compose: Janus alone (memory store, zero external deps)
docker compose -f deploy/docker-compose.yml up -d

# Compose: Janus + fake upstream (offline admin/chat smoke)
docker compose -f deploy/docker-compose.yml --profile fake-upstream up -d

# Compose: Janus + Postgres (janus switches to the postgres-store config)
JANUS_COMPOSE_CONFIG=./config/config.postgres.toml \
 docker compose -f deploy/docker-compose.yml --profile postgres up -d

# Compose: two postgres-backed nodes sharing one DB (store-smoke multi-node shape)
JANUS_COMPOSE_CONFIG=./config/config.postgres.toml \
 docker compose -f deploy/docker-compose.yml --profile node2 up -d

# Compose: three nodes + HAProxy (docs/clustering.md — the recommended
# round-robin /health-checked LB). Listener :8080; per-node metrics :18081–18083.
docker compose -f deploy/docker-compose.cluster.yml --profile fake up -d
# Offline + live/agent cluster smoke:
#   bash scripts/smoke/cluster/run.sh --skip-build

# Logs / exec / health
docker compose -f deploy/docker-compose.yml logs -f janus
docker compose -f deploy/docker-compose.yml exec janus /usr/local/bin/wget -qO- http://127.0.0.1:8080/health/readiness
docker compose -f deploy/docker-compose.yml ps
```

The compose project dir is `deploy/` (the file's directory), so the configs are
`./config/config.memory.toml` (default) / `./config/config.postgres.toml` and the
root `.env` is loaded via `env_file:../.env` (optional — compose holds no
secrets).

> **Compose + `JANUS_MASTER_KEY`:** set the master key in the
> **repo-root `.env`** (`JANUS_MASTER_KEY=...`). The compose `environment` blocks
> deliberately declare **no** `JANUS_MASTER_KEY` entry — compose interpolation
> (`${JANUS_MASTER_KEY:-}`) reads the shell env and `deploy/.env`, *not* the repo
> root `.env`, so such an entry would interpolate empty and silently override the
> `env_file` value → the gateway boots with auth OFF despite a key present in the
> root `.env`. `env_file` is the sole master-key channel for compose. A shell
> `export` alone is not seen by the containers.
>
> **Compose + `JANUS_DB_URL/_USER/_PASS`:** the same rule. The repo-root `.env`
> is the DB-credentials channel: `deploy/config/compose-db.env` supplies the
> compose-internal Postgres defaults (`janus/janus` @ `postgres:5432`) as the
> *first* `env_file` entry, and the optional root `.env` (later entries win)
> overrides them — e.g. to point the cluster at an external DB. There is no
> `environment: JANUS_DB_*` entry for the same reason as the master key: its
> interpolated default would silently replace a root-`.env` URL and the gateway
> would boot against the wrong (fresh) database.

## 9. Kubernetes quick ops

The `deploy/k8s/` manifests are a **stateless Deployment** (not a StatefulSet —
state lives in Postgres, or is per-pod ephemeral with the memory store), a
**single-port** ClusterIP (80 → 8080; data + admin on one listener), Micronaut
probes, and an optional Postgres StatefulSet + NetworkPolicy (restrict 8080
ingress and Janus → Postgres 5432).

```sh
# 1. Edit the Secret placeholders FIRST (deploy/k8s/secret.yaml — §"must-edit
# before apply") — or create it imperatively:
kubectl create secret generic janus-secrets \
 --from-literal=JANUS_MASTER_KEY='<long-random-secret>' \
 --from-literal=DEEPSEEK_API_KEY='sk-...' \
 --from-literal=JANUS_DB_URL='jdbc:postgresql://janus-postgres:5432/janus' \
 --from-literal=JANUS_DB_USER='janus' \
 --from-literal=JANUS_DB_PASS='<db-password>'

# 2. Apply (postgres.yaml + networkpolicy.yaml are optional — apply only what
# matches the Deployment's config.toml [janus.store] section)
kubectl apply -f deploy/k8s/configmap.yaml
kubectl apply -f deploy/k8s/secret.yaml
kubectl apply -f deploy/k8s/service.yaml
kubectl apply -f deploy/k8s/deployment.yaml
# kubectl apply -f deploy/k8s/postgres.yaml # only for the in-cluster demo DB
# kubectl apply -f deploy/k8s/networkpolicy.yaml # needs a CNI that enforces

# 3. Verify
kubectl rollout status deployment/janus
kubectl get pods -l app=janus
kubectl port-forward svc/janus 8080:80 &
curl -sf http://127.0.0.1:8080/health/readiness

# Scale (Postgres-backed HA: 3 replicas share the DB; memory store: keep 1)
kubectl scale deployment/janus --replicas=3
```

> **Live-cluster verification** (apply + smoke against a real cluster): the
> manifests are validated client-side
> (`kubectl apply --dry-run=client -f deploy/k8s/`). The CI workflow builds the image on push to
> `main`/`v*` tags — point the Deployment's `image:` at the built tag before a
> real rollout.

## 10. systemd (single host)

The unit is `deploy/systemd/janus.service` (example — edit paths/limits for your
host). Install steps:

```sh
sudo useradd --system --home /opt/janus --shell /usr/sbin/nologin janus
sudo install -d -o janus -g janus /opt/janus /etc/janus /var/log/janus
sudo install -m 0755 janus-gateway/build/native/nativeCompile/janus /opt/janus/bin/janus
sudo install -m 0644 /path/to/config.toml /etc/janus/config.toml # env-var NAMES only
sudo install -m 0600 -o root -g root /path/to/janus.env /etc/janus/janus.env
# janus.env: JANUS_MASTER_KEY=... DEEPSEEK_API_KEY=... [JANUS_DB_URL=...]
sudo install -m 0644 deploy/systemd/janus.service /etc/systemd/system/janus.service
sudo systemctl daemon-reload
sudo systemctl enable --now janus
```

Verify: `systemctl status janus`, `journalctl -u janus -f`, and the §2 curl
checks. **macOS caveat:** the unit is written
and lint-checked here but **not runtime-verified**; run
`systemd-analyze verify /etc/systemd/system/janus.service` on the Linux host (or
in a container) as the first install step, and treat the runtime steps as
manual verification.

## 11. CI/CD

Three GitHub Actions workflows (`.github/workflows/`). Every job that runs
`./gradlew` first pins the Gradle wrapper distribution digest
(`scripts/pin-gradle-digest.sh` fetches Gradle's official
`gradle-<v>-bin.zip.sha256` and writes `distributionSha256Sum` into the local
`gradle/wrapper/gradle-wrapper.properties`), so a tampered/corrupted
distribution download fails fast instead of building with it — the repo
digest-pins its Actions for the same reason; `docker.yml` passes the digest
into the image build as the `GRADLE_DIST_SHA256` build-arg.

- **`ci.yml`** — every push/PR to `main`: `./gradlew build` (the gate), the
 `verify-artifacts.sh` deploy/docs/smoke guard + its fixture test
 (`test_verify_artifacts.sh`; PyYAML-depends — the YAML leg is mandatory on the
 runner via `VERIFY_ARTIFACTS_REQUIRE_YAML=1`, and skipped with an install hint
 on a dev machine without PyYAML; an absent python3 degrades the same way for
 the YAML + py_compile legs), the aggregate JaCoCo report (report-only, no %
 gate), a **JVM fat jar** (`:janus-gateway:shadowJar` → Actions artifact
 `janus-jvm-jar`, smoked via `/health`), a GraalVM **native** compile with the
 **Gradle JVM pinned to 6 GB** (`NATIVE_IMAGE_MEM`, matching the
 `deploy/Dockerfile` build-arg — native-image is memory-hungry), offline smokes
 of the native binary, and Actions artifact `janus-native-*`. A `live-provider`
 job runs `:janus-gateway:liveTest` (green-by-skip without repo secrets; see
 `scripts/live-provider/README.md`).
- **`docker.yml`** — push to `main`/`v*` tags: builds the linux/amd64 image with
 BuildKit cache, no registry publish.
- **`release.yml`** — push a tag matching `v*` (e.g. `v0.1.0`): full build +
 smokes, then a **GitHub Release** with:
 - `janus-<version>.jar` (JVM fat jar)
 - `janus-<version>-linux-amd64` (native binary)
 - `SHA256SUMS`

**How to cut a release**

The tag (minus the leading `v`) must equal `gradle.properties`' `version` **and**
the packaged `[janus] version` in
`janus-gateway/src/main/resources/application.toml` — release assets are named
from the tag while the build bakes the `gradle.properties` version into the
artifacts, so `release.yml` fails early on a mismatch instead of shipping
`janus-<tag>.*` assets that report the old version.

```bash
# 1) bump BOTH gradle.properties (version=…) and application.toml ([janus] version)
# 2) commit, then tag with the same number:
git tag -a v0.1.0 -m "Janus 0.1.0"
git push origin v0.1.0
# → Actions "Release" workflow publishes the GitHub Release + assets
```

**Runnable artifacts**

| Source | Artifact | How to run |
|--------|----------|------------|
| GitHub Release (`v*`) | `janus-<ver>-linux-amd64` | `./janus-<ver>-linux-amd64` + `MICRONAUT_CONFIG_FILES=…` |
| GitHub Release (`v*`) | `janus-<ver>.jar` | `java -jar janus-<ver>.jar` + `MICRONAUT_CONFIG_FILES=…` |
| CI Actions Artifacts | `janus-native-*` / `janus-jvm-jar` | same, from the latest `main` build (retention-limited) |

Local: `./gradlew :janus-gateway:shadowJar` or `:janus-gateway:nativeCompile`.

CI/docker use `permissions: contents: read`; **release** needs `contents: write` to
create the Release. Actions are digest-pinned.
