<p align="center">
  <img src="docs/janus.jpg" alt="Janus, two-faced Roman god of gateways" width="240">
</p>

# Janus

Janus is the Roman god of doorways: two faces, one looking back and one looking
forward. This Janus is a gateway in that same sense. A request can arrive as
OpenAI and leave as Anthropic, or the other way around — one conversation
beginning in one dialect and ending in another, while the path in between
goes to whichever provider you actually run.

It is a self-hosted LLM proxy in **Java 25 + GraalVM native-image (Micronaut 5)**.
Point an existing OpenAI or Anthropic client at it (change `base_url`, use a
Janus-issued key) and it routes, load-balances, governs every key, and prices
every token. One native binary, sub-100 ms startup, configuration entirely in
TOML — no code changes to add a provider, model, or policy. In-memory by
default; PostgreSQL when you want shared state across nodes.

## Scope

Janus is a focused dual-face proxy you operate as one binary: OpenAI and
Anthropic chat faces, a stateless Responses face, routing, virtual keys,
budgets, and exact cost accounting. It does not ship teams/orgs, an admin
UI, embeddings, or a kitchen-sink of vendor-specific APIs. The HTTP surface
and the not-implemented list are in
[`docs/compatibility.md`](./docs/compatibility.md).

## Features

- **Two faces, any provider** — OpenAI `POST /v1/chat/completions` and Anthropic `POST /v1/messages`
  (streaming + non-streaming), with **cross-format translation** (system prompts, tools, streaming
  deltas, stop reasons, usage) so an OpenAI SDK can call an Anthropic model and vice-versa.
- **OpenAI Responses face** — `POST /v1/responses` (stateless, streaming + non-streaming;
  `store:true` / retrieval / `previous_response_id` → named 400s). Details in
  [`docs/responses.md`](./docs/responses.md).
- **Multimodal / vision** — user messages accept OpenAI `image_url` parts and Anthropic `image`
  blocks (data URL ↔ base64, https ↔ url); cross-face conversion is first-class
  (`docs/providers.md`).
- **Reasoning models** — request `reasoning` / Anthropic `thinking`, stream `reasoning_content` on
  deltas, optional `Usage.reasoningTokens` from `completion_tokens_details` (pricing still uses
  prompt + completion rates).
- **Structured outputs** — OpenAI `response_format` (`json_object` / `json_schema`) is first-class on
  the OpenAI-compatible leg (dropped on Anthropic encode).
- **Pluggable providers** — `ProviderAdapter` SPI declared in TOML: DeepSeek, Anthropic, OpenRouter,
  xAI, Ollama, … (`docs/adding-a-provider.md`, `docs/providers.md`).
- **Routing & resilience** — six load-balancing strategies (round-robin, least-inflight, latency,
  cost, weighted, session-affinity via `x-janus-session-id`), retries with exponential backoff + jitter, ordered fallback chains, passive health
  tracking, and a streaming-safe circuit breaker (`docs/routing.md`).
- **Governance** — virtual keys (`sk-janus-…`, hashed at rest, shown exactly once), per-key model
  scopes, rate limits (RPM/TPM) with `Retry-After`, budgets (hard cap → 429 pre-dispatch, soft cap →
  warning headers/webhook), and per-model pricing with **exact integer micro-USD cost accounting**
  (`docs/governance.md`).
- **Observability** — Prometheus `/metrics`, Tier-1 privacy contract: never prompt text, response
  text, model alias or request id in any series.
- **Optional store** — in-memory default (zero dependencies); PostgreSQL via plain JDBC (no ORM) for
  durable single-node state or **multi-node clustering** with exact shared keys/limits/budgets/spend
  (`docs/clustering.md`).
- **Packaged for production** — GraalVM native image (`-O2`, bounded heap), Docker image, Compose
  profiles, Kubernetes manifests, systemd unit (`docs/ops.md`, `docs/production-checklist.md`,
  `config.production.example.toml`).

## Architecture

**How things are laid out** is documented in full in
[`docs/architecture.md`](./docs/architecture.md) — request path, module boundaries (Gradle +
ArchUnit), faces/codecs, timeouts, and side rails. The README diagram is a pocket map; that doc is
the system map.

Six Gradle modules. Dependency direction is one-way toward the core:

| Module | Role |
|--------|------|
| `janus-core` | Canonical messages + OpenAI/Anthropic codecs (no Micronaut) |
| `janus-provider` | `ProviderAdapter` SPI + upstream HTTP adapters |
| `janus-router` | LB strategies, retry, fallback, health, breaker (`ChatBackend` only — never provider types) |
| `janus-store` | Keys, pricing, limits, spend, call records (memory or Postgres) |
| `janus-gateway` | Micronaut HTTP: faces, auth, governance, metrics, DI wiring |
| `janus-cli` | JVM composition root (`janus-cli --config …`); native boots `JanusApplication` via `MICRONAUT_CONFIG_FILES` |

```
                          ┌────────────────────────────────┐
                          │            Clients             │
                          │  OpenAI SDK · Anthropic SDK ·  │
                          │  curl / any HTTP client        │
                          └──────────┬──────────────┬──────┘
                         OpenAI face │              │ Anthropic face
                 POST /v1/chat/completions   POST /v1/messages
                 POST /v1/responses            (SSE + JSON)
                 GET  /v1/models
                                     ▼              ▼
                 ┌──────────────────────────────────────────────┐
                 │                 janus-gateway                │
                 │   KeyAuthFilter — virtual keys · scopes      │
                 │   Governance — RPM/TPM/budget pre-dispatch   │
                 │                                              │
                 │   ┌────────────────────────────────────────┐ │
                 │   │  janus-core: canonical message model   │ │
                 │   │  records · sealed types · codecs       │ │
                 │   │  OpenAI ⇄ canonical ⇄ Anthropic        │ │
                 │   └───────────────┬────────────────────────┘ │
                 │                   ▼                          │
                 │   ┌────────────────────────────────────────┐ │
                 │   │  janus-router: LB strategy · retry ·   │ │
                 │   │  fallback · health · circuit breaker   │ │
                 │   └───────────────┬────────────────────────┘ │
                 │                   ▼                          │
                 │   ┌────────────────────────────────────────┐ │
                 │   │  janus-provider: ProviderAdapter SPI   │ │
                 │   │  OpenAI-compatible · Anthropic · …     │ │
                 │   └───────────────┬────────────────────────┘ │
                 └───────────────────┼──────────────────────────┘
                                     ▼
                 ┌────────────────────────────────────────────────┐
                 │         Upstream providers (any vendor)        │
                 │   DeepSeek · Anthropic · OpenRouter · xAI ·    │
                 │   Ollama · Meta · …                            │
                 └────────────────────────────────────────────────┘

  Side rails:
    janus-store   keys (hashed) · pricing · limits · spend ledger · call records
                  — in-memory (zero deps) or PostgreSQL (multi-node, shared DB)
    janus-gateway /metrics — Prometheus, Tier-1 privacy (no prompt/response text)
    config.toml   [janus.*] sections — the canonical annotated config reference
    janus-cli     composition root — janus-cli --config config.toml (JVM CLI; the
                  native image boots via MICRONAUT_CONFIG_FILES instead — see below)
```

Pipeline: two ingress faces → auth → canonical model → router (load-balance / retry / fallback /
breaker) → provider adapters → upstreams, with store, metrics, and config as side rails. Deeper
write-up: [`docs/architecture.md`](./docs/architecture.md).

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| Java / GraalVM | **25** (GraalVM CE **25.0.2** preferred) | also the native-image provider |
| [mise](https://mise.jdx.dev/) | optional | pins the JDK via `mise.toml` |
| Gradle | wrapper only | `./gradlew` (9.6.1); CI verifies the distribution digest via `scripts/pin-gradle-digest.sh` (run it locally too) |
| python3 | any | optional to *run* Janus; used by the `verify-artifacts` legs of `./gradlew build` (PyYAML — and python3 itself — optional locally: the YAML/py_compile legs skip with an install hint, but are mandatory in CI) |

```bash
# Recommended: use the pinned GraalVM CE 25 from mise
mise install
mise trust   # once, if prompted
# JAVA_HOME should now point at graalvm-community-25.0.2
```

If Gradle cannot find JDK 25, set `JAVA_HOME` (mise does this) or put
`org.gradle.java.installations.paths=…` in `~/.gradle/gradle.properties` / `GRADLE_OPTS`
(project-local property files are not used for toolchain resolution at Gradle startup).

## Quickstart

Every command below is copy-paste verifiable — the committed walkthrough
(`scripts/smoke/readme/run.sh`) executes them **verbatim**, offline, against a
local fake upstream.

```bash
# 1. Build + unit tests
export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null)}"
./gradlew build

# 2. Configure: the repo's config.toml needs a real provider key (DEEPSEEK_API_KEY).
#    For a zero-network try-out, point JANUS_CONFIG at the walkthrough config, which
#    routes the deepseek-v4-flash alias to a local fake upstream — no keys needed:
#      export JANUS_CONFIG="$(pwd)/scripts/smoke/readme/config.walkthrough.toml"
export JANUS_MASTER_KEY="$(openssl rand -hex 24)"          # admin API auth (see Governance)
export JANUS_CONFIG="${JANUS_CONFIG:-$(pwd)/config.toml}"   # config file for the gateway

# 3. Run the gateway (JVM) — health at http://127.0.0.1:8080/health
./gradlew :janus-cli:run --args="--config $JANUS_CONFIG"
```

In a second terminal:

```bash
# 4. Health
curl -s http://127.0.0.1:8080/health
# {"status":"UP", ...}

# 5. Admin: issue a virtual key with the master key (the full sk-janus-… key is
#    returned exactly once)
JANUS_KEY="$(curl -s -X POST http://127.0.0.1:8080/key/generate \
  -H "x-api-key: $JANUS_MASTER_KEY" -H 'Content-Type: application/json' \
  -d '{"name":"quickstart","models":["deepseek-v4-flash"]}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')"

# 6. First chat — OpenAI face, non-streaming
curl -s http://127.0.0.1:8080/v1/chat/completions \
  -H "x-api-key: $JANUS_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"hello"}]}'

# 7. Streaming (SSE) — include_usage so the terminal frame carries token counts
curl -sN http://127.0.0.1:8080/v1/chat/completions \
  -H "x-api-key: $JANUS_KEY" -H 'Content-Type: application/json' \
  -d '{"model":"deepseek-v4-flash","stream":true,"stream_options":{"include_usage":true},"messages":[{"role":"user","content":"hello"}]}'
# data: {...}  data: {...}...  data: [DONE]

# 8. Admin: redacted key list (no full key, no hash, no salt)
curl -s http://127.0.0.1:8080/key/list -H "x-api-key: $JANUS_MASTER_KEY"

# 9. Ops: Tier-1 Prometheus metrics
curl -s http://127.0.0.1:8080/metrics | grep janus_requests_total
```

The Anthropic face works the same way: point an Anthropic SDK at
`http://127.0.0.1:8080/v1/messages` with the same `sk-janus-…` key
(`Authorization: Bearer` or `x-api-key`).

### JVM fat jar (optional)

Single-file runnable JAR (same main class as native — `JanusApplication`):

```bash
./gradlew :janus-gateway:shadowJar
MICRONAUT_CONFIG_FILES="$JANUS_CONFIG" JANUS_MASTER_KEY="$JANUS_MASTER_KEY" \
  java -jar janus-gateway/build/libs/janus-*.jar
```

CI uploads this jar as the `janus-jvm-jar` Actions artifact on every `main` build.
Tagged releases (`v*`) attach the versioned jar plus native binaries (`linux-amd64`, `macos-arm64`)
and a unified `SHA256SUMS` via `.github/workflows/release.yml`.

### Native image (optional, requires GraalVM with native-image)

```bash
./gradlew :janus-gateway:nativeCompile
MICRONAUT_CONFIG_FILES="$JANUS_CONFIG" JANUS_MASTER_KEY="$JANUS_MASTER_KEY" \
./janus-gateway/build/native/nativeCompile/janus
```

Cold boot → `/health` is about **42 ms**; binary about **86 MiB**; lifetime max
RSS about **75 MiB** (see `docs/benchmarks.md`). The native image (and the fat jar) read config via
`MICRONAUT_CONFIG_FILES` (main class is `JanusApplication`, not the CLI), and a stray
`--config`/`-c` argument against them is rejected at boot with a usage line naming that
variable — the JVM CLI alone takes `--config`.

## Configuration

Configuration is **TOML-first**: the committed `config.toml` is the canonical **annotated** reference
— every `[janus.*]` section documents its keys, defaults and rationale inline. Secrets are **never**
in TOML: keys reference env-var *names* (`api-key-env`, `master-key-env`, `jdbc-url-env`).
Micronaut binds kebab-case TOML keys to record components (underscores silently bind null).

| Section | Purpose | Key highlights |
|---|---|---|
| `[[janus.model-list]]` | model alias → provider entry (two entries with the same `name` = backends in one candidate list) | `name`, `provider`, `api-key-env`, `base-url` |
| `[janus.providers.<name>]` | declarative per-provider hints | `wire-format` (`openai-compatible` \| `anthropic`), `base-url`, `api-key-env`, `connect-timeout-seconds`, `header-timeout-seconds`, `body-read-timeout-seconds`, `stream-idle-timeout-seconds` |
| `[janus.router]` | routing + resilience | `strategy`, `latency-alpha`, `weights`, `max-retries`, `backoff-base-ms`, `backoff-max-ms`, `jitter`, `allowed-fails`, `cooldown-time`, `breaker-failure-threshold`, `breaker-window-seconds`, `breaker-cooldown-seconds` |
| `[janus.timeouts]` | upstream deadlines (absent = code defaults: 10 s connect / 60 s header / 300 s body-read / 60 s stream-idle) | `connect-timeout-seconds`, `header-timeout-seconds`, `body-read-timeout-seconds`, `stream-idle-timeout-seconds` — the same four keys override per `[[janus.model-list]]` entry (highest) and per `[janus.providers.<name>]` block; the stream-idle watchdog resolves per dispatch by the serving provider |
| `[janus.keys]` | governance — **auth on by default**; missing master key fails fast | `auth` (`on` \| `off`), `master-key-env` (env var NAME) |
| `[[janus.pricing.models]]` | per-alias USD-per-1K pricing | `name`, `input-per-1k`, `output-per-1k`, `cache-read-per-1k`, `cache-creation-per-1k`, `default-max-tokens`, `long-context-threshold`, `long-input-per-1k`, `long-output-per-1k` |
| `[janus.limits]` | limiter variant + soft tier | `window` (`fixed` \| `sliding`), `soft-cap-fraction`, `notifier-webhook-url`, `ledger-retention` |
| `[janus.store]` | optional backend (absent = in-memory) | `type` (`memory` \| `postgres`), `jdbc-url-env`, `user-env`, `password-env`, `max-pool-size`, `retention` |

Full references: [`config.toml`](./config.toml) (canonical, annotated) ·
[`docs/routing.md`](./docs/routing.md) (router semantics + tuning) ·
[`docs/governance.md`](./docs/governance.md) (keys/limits/pricing/metrics + auth-off warning) ·
[`docs/clustering.md`](./docs/clustering.md) (store + multi-node).

## Endpoints

| Plane | Endpoint | Description |
|---|---|---|
| Data | `POST /v1/chat/completions` | OpenAI face — JSON + SSE, cross-format translation |
| Data | `POST /v1/messages` | Anthropic face — JSON + SSE, cross-format translation |
| Data | `GET /v1/models` | configured model aliases (config order) |
| Data | `GET /v1/models/{id}` | retrieve one alias — 404 `model_not_found` for unknown ids |
| Data | `POST /v1/responses` | OpenAI Responses face (stateless; JSON + SSE) |
| Data | `GET`/`DELETE /v1/responses/{id}` | envelope 404 stubs (stateless contract) |
| Admin | `POST /key/generate` | create a virtual key — master-key-authed; full key shown exactly once |
| Admin | `POST /key/delete` | revoke by `key_id` or full key — master-key-authed, idempotent |
| Admin | `GET /key/list` | redacted key list — master-key-authed (no full key/hash/salt) |
| Ops | `GET /health` · `/health/readiness` · `/health/liveness` | aggregated / readiness / liveness probes |
| Ops | `GET /metrics` | Prometheus text exposition, Tier-1 (path pinned; `/prometheus` 404s) |

The admin API is specified in [`docs/openapi.yaml`](./docs/openapi.yaml). Error envelopes are
OpenAI-styled: `400 invalid_request_error`, `401 authentication_error`, `403 permission_error`
(revoked/scope-denied), `429 rate_limit_error` + `Retry-After` (limits/budgets, pre-dispatch).

## Modules

Same six modules as the Architecture table above. Module boundaries are enforced by
**Gradle** dependencies and by **ArchUnit** on every `./gradlew build`
(`ArchitectureTest` + `CliArchitectureTest` — see [`docs/architecture.md`](./docs/architecture.md)).

| Module | Role | Depends on |
|---|---|---|
| `janus-core` | canonical message model + wire codecs (records, sealed types) | nothing internal |
| `janus-provider` | `ProviderAdapter` SPI + upstream adapters | `core` |
| `janus-router` | load balancing, retries, fallback, health, breaker | `core` |
| `janus-store` | keys, pricing, limits, spend, call records | `core` |
| `janus-gateway` | Micronaut HTTP app — faces, admin, metrics | `core` + `provider` + `router` + `store` |
| `janus-cli` | composition root — `janus-cli --config …` (JVM CLI only; the native binary boots via `MICRONAUT_CONFIG_FILES`, and `--config` against it fails fast) | `gateway` |

## Deployment

One binary serves data + admin + ops on a single listener. Choose a topology per
[`docs/clustering.md`](./docs/clustering.md) (single-node memory / single-node + Postgres /
multi-node + Postgres), then:

- **Docker** — `deploy/Dockerfile` (multi-stage, distroless runtime; image ~119 MiB); released images on tags:
  `ghcr.io/amscotti/janus:<version>` (multi-arch linux/amd64 + linux/arm64, `:latest` tracking newest —
  each arch compiled natively on its own runner).
- **Compose** — `deploy/docker-compose.yml` with memory / postgres / fake-upstream / multi-node
  profiles (`docker compose up -d` brings up Janus alone, no Postgres). The memory
  config is auth-on: set `JANUS_MASTER_KEY` in the repo-root `.env` or the container
  fails fast.
- **Kubernetes** — `deploy/k8s/` manifests (stateless Deployment, probes, optional Postgres +
  NetworkPolicy; `kubeconform -strict` validated).
- **systemd** — `deploy/systemd/janus.service` (example unit; first Linux-host step:
  `systemd-analyze verify`).

Full runbook — health checks, backup (`pg_dump`), upgrade/rollback, secret rotation,
troubleshooting, monitoring: [`docs/ops.md`](./docs/ops.md).

## Docs

| Guide | What it covers |
|---|---|
| [`docs/architecture.md`](./docs/architecture.md) | **system map** — modules, request path, codecs, timeouts, boundaries |
| [`docs/adding-a-provider.md`](./docs/adding-a-provider.md) | ProviderAdapter SPI + worked example |
| [`docs/providers.md`](./docs/providers.md) | provider/model cookbook (reasoning, vision, knobs) |
| [`docs/production-checklist.md`](./docs/production-checklist.md) | day-1 production checklist |
| [`config.production.example.toml`](./config.production.example.toml) | production-oriented sample config |
| [`docs/routing.md`](./docs/routing.md) | strategies, retry / fallback / health / breaker |
| [`docs/governance.md`](./docs/governance.md) | keys, limits, budgets, pricing, metrics |
| [`docs/clustering.md`](./docs/clustering.md) | store backends, multi-node topologies |
| [`docs/ops.md`](./docs/ops.md) | health, backup, upgrade, rotation, troubleshooting |
| [`docs/benchmarks.md`](./docs/benchmarks.md) | load benchmarks + JMH microbenchmarks |
| [`docs/compatibility.md`](./docs/compatibility.md) | HTTP surface and scope |
| [`docs/responses.md`](./docs/responses.md) | Stateless Responses face |
| [`docs/openapi.yaml`](./docs/openapi.yaml) | admin API OpenAPI |
| [`scripts/live-provider/README.md`](./scripts/live-provider/README.md) | live / native / multi-node opt-in tests |

## Benchmarks (summary)

Same box, same mock, back-to-back legs, raw tool outputs committed — full methodology and numbers in
[`docs/benchmarks.md`](./docs/benchmarks.md):

| Build | Throughput (req/s) | p50 (ms) | p95 (ms) | Streams ok | Warm /health (ms) | RSS post-warmup |
|---|---|---|---|---|---|---|
| **Native** | 3376 | 2.0 | 3.0 | 20/20 | 12.7 | 128 MiB |
| **JVM** | 1462 | 6.0 | 11.0 | 20/20 | 13.8 | 292 MiB |

Native is about 2.3× JVM throughput at about 44% of the RSS. Cold boot → `/health` is
**42.2 ms**; binary **86.1 MiB**; lifetime max RSS **75 MiB**.

## Development

```bash
./gradlew build          # compile, spotless check, tests
./gradlew spotlessApply  # format Java (Palantir)
./gradlew test           # unit tests only
```

Conventional commits (`feat:`, `fix:`, `test:`, `docs:`, `chore:`). Hard rules for
working in this repo (module boundaries, test-first, native-image discipline,
scope): [`AGENTS.md`](./AGENTS.md).

Offline smoke harnesses (fake upstreams) live under `scripts/smoke/`:

| Directory | What it proves |
|---|---|
| `openai-face/` | Unmodified OpenAI SDK against the OpenAI face |
| `cross-format/` | Both faces, all four translate directions |
| `routing/` | Failover, retries, breaker, health, fairness |
| `governance/` | Keys, limits, budgets, exact cost, metrics |
| `store/` | In-memory store + two-node Postgres |
| `readme/` | README quickstart, verbatim |
| `docker/` | Compose memory / Postgres / fail-fast (TLS egress probes opt-in: `JANUS_TLS_PROBE=1`) |
| `cluster/` | Three nodes + Postgres + HAProxy (offline + optional live/agents) |

Load/startup comparison is `scripts/bench/`. Each harness has a `run.sh`.

## License

[MIT](./LICENSE) — Copyright (c) 2026 Anthony Scotti.
