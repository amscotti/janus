# Day-1 production checklist

Minimal steps to run Janus usefully in production. Details: [`ops.md`](./ops.md),
[`governance.md`](./governance.md), [`providers.md`](./providers.md).

## 1. Binary & config

- [ ] Build native or JVM fat jar: `./gradlew :janus-gateway:nativeCompile` or
 `./gradlew :janus-gateway:shadowJar` then
 `MICRONAUT_CONFIG_FILES=… java -jar janus-gateway/build/libs/janus-*.jar`
 (or CLI: `./gradlew :janus-cli:run --args="--config …"`)
- [ ] Copy [`config.production.example.toml`](../config.production.example.toml) → your deploy config
- [ ] Set **env vars for secrets only** (`JANUS_MASTER_KEY`, provider `*_API_KEY`) — never put key values in TOML
- [ ] Native image: pass config via `MICRONAUT_CONFIG_FILES=/path/to/config.toml` (not `--config`)
- [ ] JVM: `janus-cli --config /path/to/config.toml` (the `janus-cli` dist script — the
 `janus`-named native binary / fat jar boot `JanusApplication` and reject `--config`)

## 2. Auth (do not leave open)

- [ ] `JANUS_MASTER_KEY` is set in the process environment
- [ ] Confirm `POST /stop` / management endpoints stay locked (defaults: `endpoints.all.sensitive=true`, stop/refresh disabled)
- [ ] Issue virtual keys only via `POST /key/generate` with the master key
- [ ] Scope keys to the model aliases you intend (`models` list)

## 3. Models & providers

- [ ] Every client-facing alias has a `[[janus.model-list]]` row
- [ ] Multi-backend aliases share the same `name` (failover order = config order)
- [ ] OpenRouter / Meta / DeepSeek base URLs match [`providers.md`](./providers.md)
- [ ] For reasoning models (Flash, Kimi, Muse), document client knobs or set safe defaults in clients

## 4. Pricing & budgets

- [ ] `[[janus.pricing.models]]` rows exist for every alias you bill (else metering is $0 + log)
- [ ] Per-key `budget_usd` / `rpm` / `tpm` set where needed
- [ ] Soft-cap webhook configured if you rely on budget warnings (`docs/governance.md`)

## 5. Store

- [ ] Single node, no durability: leave store as memory (default)
- [ ] Multi-node or durable keys/spend: Postgres + `jdbc-url-env` + fail-fast if DB down
- [ ] Backup plan for Postgres (`docs/clustering.md`, `docs/ops.md`)

## 6. Health & metrics

- [ ] `GET /health` (and readiness/liveness) reachable from the orchestrator
- [ ] Scrape `GET /metrics` (Prometheus); never expect prompt/response text in labels.
      `/metrics` is unauthenticated on the main listener and publishes per-`key_id`
      counters and upstream `base_url` labels — restrict scraping at the ingress /
      NetworkPolicy layer to the scraper only (one listener serves data + admin + ops;
      see `docs/governance.md`)
- [ ] Alert on 5xx rate, upstream breaker open, store errors

## 7. Smoke

```bash
# Offline unit gate
./gradlew build

# Optional real-provider matrix (spends money)
set -a && source.env && set +a
export JANUS_LIVE=1
./gradlew :janus-gateway:liveTest
```

- [ ] One OpenAI-face chat + one Anthropic-face chat against a real key
- [ ] Stream returns `data:` frames and `[DONE]`
- [ ] Bad virtual key → 401; scoped unknown model → 403

## 8. Deploy shape

- [ ] Docker Compose or k8s manifests under `deploy/` reviewed for your registry/secrets
- [ ] Resource limits: native RSS ~75–150 MiB class; heap bound if using native `-R:MaxHeapSize`
- [ ] TLS terminated at ingress or Compose TLS profile as needed
