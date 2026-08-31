# Clustering (multi-node operation)

> Janus is **single-node by default and stateless in a cluster**: multi-node
> operation is an opt-in upgrade that requires a shared PostgreSQL database and
> a trusted network. This doc covers deployment topologies, the shared-DB
> consistency model (exact cross-node keys, limits, budgets, and spend — no
> gossip or leases), the CAP tradeoff, fail-fast boot and mid-run failure
> semantics, the `[janus.store]` configuration reference, per-node metrics, and
> the security baseline. See [`governance.md`](./governance.md) for the
> governance surface this store backs.

## Quick start

### Single-node (default — no database)

No configuration needed. With no `[janus.store]` section the store is the in-memory
default (`InMemoryCallStore`): zero extra config, and **no
cross-node state** — a second node would not share keys, counters, budgets or call
records. This is the recommended starting profile and the only profile that needs
no Postgres.

### Single-node + Postgres (durability)

Add the `[janus.store]` section (config reference below) and set `JANUS_DB_URL` /
`JANUS_DB_USER` / `JANUS_DB_PASS` (or embed credentials in the URL). The node now
persists keys, rate-limit counters, spend and call records in Postgres — the same
shape a multi-node deployment uses, so a later scale-out is a config-only change
(add nodes, add an LB).

### Multi-node + Postgres (shared state)

Every node runs the **identical** `[janus.store]` section pointing at the **same**
Postgres instance, and the same master key. Nodes are stateless: any node serves
any key (keys authenticate from the DB per request — there are **no caches**, so
no sticky routing and no invalidation fan-out). A round-robin LB in front is fine.
This extends to routing: `session-affinity` is node-independent by construction
(a stateless rendezvous hash over the session id + backend name — the same
`x-janus-session-id` picks the same backend on any node, with no shared state;
see docs/routing.md § Session affinity), so a round-robin LB in front of an
affinity-configured cluster still gives each session one consistent upstream.

```
 ┌─────────────────────────────────────────────┐
 │ Operator LB │
 │ (round-robin; health-checks /health; │
 │ retries on a dead node — in-flight │
 │ requests are never migrated) │
 └───────────────┬───────────────┬─────────────┘
 │ │
 ┌──────────────▼───┐ ┌───────▼──────────────┐
 │ Janus node A │ │ Janus node B │
 │ :18080 │ │ :18081 │
 │ [janus.store] │ │ [janus.store] │
 │ type="postgres" │ │ type="postgres" │
 └───────┬──────────┘ └───────┬──────────────┘
 │ │
 └──────────┬───────────┘
 │ one shared Postgres
 ┌─────▼─────┐
 │ Postgres │ keys · rate_limits · spend ·
 │ (HA for │ spend_entries · calls ·
 │ avail.) │ store_meta · schema_migrations
 └───────────┘
```

**When to choose:** single-node memory for dev/CI; single-node + Postgres when you
need durability and a single point of administration; multi-node + Postgres when
you need horizontal capacity or node-loss availability (two or more nodes behind
an LB sharing one Postgres). Availability-first operators run **Postgres HA**
(replica / managed Postgres) in front of the LB — Janus itself has no gossip,
no degraded mode, and no per-node state to reconcile.

### Compose reference stack (HAProxy)

`deploy/docker-compose.cluster.yml` is a three-node example: shared Postgres,
three identical Janus containers, and **HAProxy** as the operator LB
(round-robin, `GET /health` checks, retry on connect only — in-flight
requests are never migrated). Public listener is `:8080` by default
(`JANUS_CLUSTER_LB_PORT` overrides the host port when 8080 is already taken);
per-node `/health` and `/metrics` stay on `:18081`–`:18083` so scrapes can be
summed.

```sh
# Offline (golden fake upstream) — also the cluster smoke:
bash scripts/smoke/cluster/run.sh --skip-build --skip-live --skip-agents

# Bring the stack up yourself (auth-on: set JANUS_MASTER_KEY in the repo-root .env):
docker compose -f deploy/docker-compose.cluster.yml --profile fake up -d
```

The smoke (`scripts/smoke/cluster/run.sh`) drives keys, both chat faces,
Responses, RPM/TPM/budget, soft-cap warnings, revoke, privacy, spend ==
sum of per-node metrics, and kill-one-node failover through the proxy.
`--skip-live` / `--skip-agents` drop the real-provider and Claude Code /
Codex legs.

Claude Code (`ANTHROPIC_BASE_URL` + a Janus virtual key, `--bare`) is the
coding-agent path that round-trips through the cluster. Codex 0.145 speaks
only the Responses `wire_api` and injects hosted `web_search`; that 400s on
an OpenAI-format upstream (`unsupported_hosted_tool`) and is recorded as a
client limitation, not a cluster outage.

## The shared-DB consistency model

### State inventory

| Table | Owns | Cross-node semantics |
|---|---|---|
| `keys` | hashed virtual keys (`prefix`, `salt`, `secret_hash`, caps, status) | read-your-writes: a key created on node A is visible on node B on the very next request — no replication lag, no cache TTL |
| `rate_limits` | fixed-window counters (`key_id, dimension, window_start, count` — window in the PK) | **exact**: two nodes' requests accumulate in the SAME row; the 3rd request over an `rpm: 2` cap 429s regardless of which node serves it (atomic upsert, no overshoot). **Bounded retention**: a sampled write-path janitor (`PgRateLimiter`, 1 DELETE per 1024 writes) prunes rows older than the current window + 2 (~3-minute horizon) — only the current window is ever read, so a stale row can never be re-admitted |
| `spend` | window-scoped `settled`/`pending` micro-USD totals per key (`key_id, window_start` — window in the PK; lifetime keys keep a single `window_start = 0` row) | **exact**: reserve/settle/release are atomic statements (increment-then-check upserts; settle clamps negative actuals to 0 — `max(actual, 0)`); the sum of settled spend never exceeds cap + one request cluster-wide, and both nodes enforce the SAME window row — a budget that trips on node A 429s on node B. Old window rows are pruned to current + 2 prior by a sampled write-path janitor that folds each pruned row's settled into the key's window-0 all-time accumulator row (the sum never decreases) |
| `spend_entries` | the `LedgerEntry` ring, bigserial seq | shared ring: any node's settle appends; retention prunes by seq |
| `calls` | the `CallRecord` ring, bigserial seq, newest-first per key | cluster-wide view: `recentCalls` on any node reads the same table (records from ALL nodes under the key's id, bounded by retention) |
| `store_meta` | the global monotonic `dropped` overflow counter | shared counter |
| `schema_migrations` | migration version tracking (no Flyway) | idempotent; applied once per database, not per node |

Every auth/limit/budget/spend/call op is a **single atomic SQL statement or
transaction** on the shared DB. There are no caches (`PgKeyStore` authenticates
from the DB per request), so there is nothing to invalidate, replicate or merge.

### CAP tradeoff

The Postgres instance is the single point of consistency — **CP with the
availability placed in Postgres HA**:

| Partition state | Behavior |
|---|---|
| Store reachable | exact cross-node keys/limits/budgets/spend (read-your-writes; atomic counters) |
| Store unreachable (partition) | every store-touching request **fails closed** with a clean 5xx envelope (the store exception propagates to a 500 `api_error` — no hang, no retry storm); the node **refused to boot** in the first place if the DB was down at startup |
| Store recovers | HikariCP pool recovery — the same node serves requests again without a restart |
| Node dies | the LB health-checks `/health` and retries on a surviving node; in-flight requests are never migrated (documented streaming bound) |

There is a single exact tier: RPM, budget reserve/settle, and spend are atomic
in the shared database. There is no per-node overshoot, no fail-open default,
and no coordinator leases. The price is availability: a database outage is a
total outage for store-touching requests (fail-closed by design). Availability
comes from Postgres HA, not a Janus degraded mode.

### Per-node vs cluster-wide data

- **`/metrics` is per-node local.** Cluster totals are the sum of per-node
  scrapes. The scrape of `janus_key_cost_micro_usd_total` across nodes matches
  the `spend` table and the price × usage math.
- **The DB is the authoritative cluster-wide ledger**: `spend` (all-time) and
  `calls` (per-request records from every node).
- **`recentCalls(key, n)` on any node reads the shared `calls` table** — the
  per-key view is cluster-wide by construction.
- **`dropped` is the global `store_meta` counter.**
- **Two rings are kept:** the `LedgerEntry` ring feeds `/metrics`; the
  `CallRecord` ring is the richer per-request log and is Postgres-backed. In
  postgres mode both read the shared tables, so the duplication exists only
  in the in-memory store and is harmless.

## Failure semantics

### Boot fail-fast

`[janus.store] type = "postgres"` constructs the pool with HikariCP
`initializationFailTimeout = 1`: an unreachable database **refuses the node to
start** — nonzero exit, and the error names the **env var** (`JANUS_DB_URL`),
never the URL (credentials may be embedded). A node silently falling back to
memory in a multi-node deployment would violate read-your-writes. Unknown types,
postgres without `jdbc-url-env`, and `[janus.limits] window = "sliding"` combined
with postgres (see below) are all rejected at config binding.

### Mid-run fail-closed + recovery

A Postgres outage mid-run propagates the store exception through the request path
to the gateway error handler: every store-touching request fails with a **clean
500 `api_error` envelope** (no stack trace in the body, no hang, no platform-thread
leak, no retry storm). When Postgres
returns, HikariCP pool recovery lets the **same node** serve requests again — no
restart needed. Non-store routes (`/health`, `/v1/models`, `/metrics`) stay up.

### In-flight requests are never migrated

A client whose node dies mid-stream sees a documented connection reset — Janus does
not migrate in-flight requests (that would require a stateful proxy). The operator
LB health-checks `/health` and retries. The surviving node serves fresh
requests; the dead node's in-flight stream resets.

### Hot-key write serialization and pool sizing

The per-key call-ring/spend-ring writers (`PgCallLedger.recordCall`,
`PgSpendLedger.recordSpend`) hold a per-key `pg_advisory_xact_lock` until commit so
racing prunes evict each overflow exactly once. Per-key serialization (latency) is a
documented, accepted cost; its **availability** side is that a burst of concurrent
writers on one hot key with concurrency ≥ `max-pool-size` holds every pooled
connection blocked on that key's lock, and a concurrent op on an unrelated key then
waits on pool checkout and, past `connectionTimeout` (2s), surfaces a clean 5xx
(`api_error`) for an otherwise-valid request. The DB is never wrong — this is a
pool-sizing artifact, not a correctness bug. Sizing guidance for write-heavy keys:
`max-pool-size` should exceed the expected peak concurrent writers on a single hot
key (default 10 covers typical peaks; a single key sustaining ≥ `max-pool-size`
simultaneous requests is the degenerate case to size against), and per-key writes
are serialized anyway, so extra pool slots buy concurrency *across* keys, not on one.
Read/limit/budget ops that do not take the lock are unaffected beyond the shared
checkout wait.

## Configuration reference

```toml
[janus.keys]
master-key-env = "JANUS_MASTER_KEY" # shared by every node — admin ops land in the shared DB

[janus.limits]
window = "fixed" # must stay fixed (the default) with a postgres store (see below)
# soft-cap-fraction = 0.8
# ledger-retention = 1000 # per-key spend-ledger ring (spend_entries prune), both impls

[janus.store]
type = "postgres" # "memory" (absent ⇒ memory default) | "postgres"
jdbc-url-env = "JANUS_DB_URL" # env var NAME holding the full JDBC URL — never the URL in TOML
user-env = "JANUS_DB_USER" # optional; overrides URL-embedded credentials
password-env = "JANUS_DB_PASS" # optional; overrides URL-embedded credentials
max-pool-size = 5 # HikariCP pool size (default 10)
retention = 1000 # per-key call-ring retention, both impls (default 1000) — independent
                 # of [janus.limits] ledger-retention (the spend-ledger ring)
```

- **Env-only credentials**: the URL, user and password come from the environment, never from TOML.
- **Postgres mode is fixed-window only.** `window = "sliding"` (token bucket) has
 no Postgres counterpart — the combination is **rejected at boot**. An operator who configured
 sliding on a postgres node would otherwise silently get fixed-window cross-node
 semantics. The in-memory store supports both windows.
- **Boot asymmetry**: the JVM CLI takes `--config
 <path>`; the GraalVM native image's `mainClass` is `JanusApplication` and boots
 with `MICRONAUT_CONFIG_FILES=<path>` + the env vars. All nodes of a deployment
 use the same store config; only the server port differs.

## Security baseline

- **Trusted network only.** The Postgres instance holds the authoritative state —
 run it on a private network, restrict the JDBC port to the Janus nodes, and use
 **Postgres TLS** (`?ssl=true` / `sslmode=require` in the JDBC URL) for anything
 but a trusted LAN. Credentials are env-only and never logged (the
 failure drill asserts the boot error names the env var, never the URL).
- **Master key via env** (`JANUS_MASTER_KEY`, shared across nodes), never in TOML,
 never logged.
- **No inter-node protocol.** Janus nodes never talk to each other. The only
 shared infrastructure is the database, which you secure like any Postgres.
- **Per-request auth from the DB** (no auth cache) means a revoked key is rejected
 on every node on the next request — no TTL window, no fan-out to get wrong.
- **K8s deployment**: manifests ship with the repo; the topology is the
 diagram above — a Deployment per node, a headless/ClusterIP Service + LB in
 front, the Postgres URL + master key as a Secret, and pod anti-affinity so a
 node loss does not take down every Janus replica.

## Design choices

| Topic | Behavior |
|---|---|
| Metering | Exact cluster-wide RPM, TPM, budget, and spend. Atomic upserts; zero overshoot. One database round-trip per counter op. |
| Fail-closed | A partitioned store cannot meter, so every store-touching request is denied (see the CAP table). |
| Fixed-window math | `window_start = floorDiv(now, 60) × 60`. An RPM 429's `Retry-After` is the seconds until the next aligned window end. |
| Metrics | Scrape every node and sum. The database is the authoritative ledger. |
| Membership | None. Nodes do not gossip. The database is the consistency point. |
| Caches | None. Auth, limits, and spend read the database per request. A revoke is visible on the next request on every node. |

## Decision records

| Item | Decision |
|---|---|
| Aborted streams | **record-nothing** — a client disconnect before exhaustion writes no `CallRecord`; the closed `CallStatus` set is unchanged |
| `sliding` + postgres | **rejected at binding** — Postgres mode is fixed-window only |
| `PgSpendLedger.settle` negative actual | **clamp, never throw** (`max(actual, 0)` / SQL `GREATEST(?, 0)`) |
| Two rings | **keep both** — `LedgerEntry` feeds metrics; `CallRecord` is the per-request log |
| Mid-run store outage | **fail closed** — clean 500 `api_error`; recovery via HikariCP; availability via Postgres HA |
| Cluster totals | **sum per-node scrapes**; the DB is the authoritative ledger |
| Postgres down at boot | **refuse cleanly** — nonzero exit, error names the env var (never the URL) |
