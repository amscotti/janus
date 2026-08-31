# Architecture

Janus is a dual-protocol LLM **proxy gateway**: clients speak OpenAI and/or Anthropic
HTTP APIs; Janus authenticates virtual keys, applies governance, translates wire
formats through a canonical model, load-balances and retries across upstream
providers, and returns face-shaped responses (including SSE).

This document is the product-facing map of the running system. Configuration details
live in [`config.toml`](../config.toml) and the topic guides under `docs/`.

## High-level request path

```text
  Client (OpenAI SDK / Anthropic SDK / curl)
       │
       │  POST /v1/chat/completions  or  POST /v1/messages  or  POST /v1/responses
       │  GET  /v1/models
       ▼
  ┌─────────────────────────────────────────────────────────┐
  │  janus-gateway  (Micronaut HTTP)                        │
  │  • KeyAuthFilter — master key (admin) / virtual key     │
  │    (virtual-key auth is deferred to the blocking        │
  │     executor — JDBC never runs on the Netty IO thread)  │
  │  • Governance — RPM/TPM/budget preflight + settle       │
  │  • Controllers — face decode/encode, SSE publishers     │
  │  • RouterFactory / LoadBalancerFactory / MetricsFactory │
  └───────────────────────┬─────────────────────────────────┘
                          │ canonical ChatRequest
                          ▼
  ┌─────────────────────────────────────────────────────────┐
  │  janus-core                                             │
  │  • Sealed Message model + ChatRequest/Response/Stream   │
  │  • OpenAiMessageCodec / AnthropicMessageCodec           │
  │  • OpenAI ⇄ canonical ⇄ Anthropic (tools, stream, …)  │
  └───────────────────────┬─────────────────────────────────┘
                          │
                          ▼
  ┌─────────────────────────────────────────────────────────┐
  │  janus-router                                           │
  │  • Model alias → ordered backend list                   │
  │  • LoadBalancer (6 strategies) + observation hooks      │
  │  • Retry / passive health / circuit breaker             │
  │  • Stream wrap: usage → cost-based LB on clean close    │
  └───────────────────────┬─────────────────────────────────┘
                          │ ChatBackend
                          ▼
  ┌─────────────────────────────────────────────────────────┐
  │  janus-provider                                         │
  │  • ProviderAdapter SPI                                  │
  │  • OpenAiCompatibleAdapter (/v1/chat/completions)       │
  │  • AnthropicAdapter (/v1/messages)                      │
  │  • DeepSeekAdapter (compat + defaults)                  │
  └───────────────────────┬─────────────────────────────────┘
                          │
                          ▼
                   Upstream LLM APIs
```

The diagram is the *logical* pipeline, not the call graph: the gateway controllers
invoke the core codecs and the router, and the `ProviderAdapter` → `ChatBackend`
adaptation (`ProviderAdapterChatBackend`, which lives in **janus-gateway**) is the
gateway's job — the router never imports provider types, it sees only `ChatBackend`
(Module boundaries below).

**Side rails (not on the happy path, always in process):**

| Rail | Module | Role |
|------|--------|------|
| Store | `janus-store` | Keys (hashed), pricing, rate limits, spend ledger, call records; memory or Postgres |
| Metrics | `janus-gateway` | Prometheus `/metrics`, Tier-1 privacy labels only |
| Config | TOML + env | `config.toml` / `MICRONAUT_CONFIG_FILES`; secrets via env *names* |
| CLI | `janus-cli` | Composition root for JVM: `janus-cli --config …` |

Native image main class is `JanusApplication` (gateway), not the CLI — config via
`MICRONAUT_CONFIG_FILES`.

## Module boundaries

Enforced two ways:

1. **Gradle** multi-module dependencies (compile-time).
2. **ArchUnit** on every `./gradlew build`:
   `janus-gateway` → `ArchitectureTest` (module layers, Micrometer/Micronaut
   only in the gateway, no Spring/ORM, Jackson/SLF4J/JDBC locality, SPI and
   naming placement for codecs/adapters/LBs/controllers/factories/store
   seams, canonical model is records/sealed/enums, gateway root is boot +
   config only);
   `janus-cli` → `CliArchitectureTest` (composition root must not bypass
   gateway; the CLI package is `JanusCli` only).

| Module | May depend on |
|--------|----------------|
| `janus-core` | nothing internal |
| `janus-provider` | `core` only |
| `janus-router` | `core` only |
| `janus-store` | `core` only (+ JDBC stack for Postgres) |
| `janus-gateway` | `core` + `provider` + `router` + `store` + Micronaut/Micrometer |
| `janus-cli` | `gateway` |

Router never imports provider types: it sees only `ChatBackend`. The gateway adapts
`ProviderAdapter` → `ChatBackend`.

## Faces and codecs

| Face | Path | Codec | Notes |
|------|------|-------|-------|
| OpenAI | `POST /v1/chat/completions` | `OpenAiMessageCodec` | Token cap: `max_completion_tokens`; multimodal user parts; `response_format` |
| OpenAI | `POST /v1/responses` | `OpenAiResponsesCodec` | Stateless; streaming + non-streaming; `store:true` / retrieval → named 400s ([`responses.md`](./responses.md)) |
| OpenAI | `GET /v1/models` | — | Config aliases, config order |
| Anthropic | `POST /v1/messages` | `AnthropicMessageCodec` | Native content blocks, SSE event types, image blocks |

Cross-format: client face and upstream provider may differ (e.g. OpenAI SDK → Claude).
Translation is always face → canonical → upstream codec.

**Multimodal:** user messages may be string or ordered `ContentPart`s
(`TextContent`, `ImageUrlContent`, `ImageSourceContent`). OpenAI ↔ Anthropic image
shapes convert (data URLs ↔ base64 sources; https ↔ url sources).

**Reasoning:** request `reasoning` / Anthropic `thinking` maps are first-class;
stream `reasoning_content` rides `Delta.reasoning`; optional
`Usage.reasoningTokens` from `completion_tokens_details` when present.

**Structured outputs:** OpenAI `response_format` is first-class
`ChatRequest.responseFormat` (json_object / json_schema). Dropped on Anthropic encode.

## Routing and resilience

Configured under `[janus.router]`:

- **Strategies:** round-robin, least-inflight, latency-based, cost-based, weighted, session-affinity (`x-janus-session-id`)
- **Cost-based:** cumulative micro-USD from actual usage via the same `PriceTable` as
  governance (`LoadBalancerFactory.pricingCost`). It equalizes actual spend — the cheaper
  backend is picked more while the expensive one is still served until spend converges —
  it does not pick the statically cheapest price. A pricing row keyed by a
  backend/provider name overrides the client-alias row, so two providers serving one
  alias compare at their own rates. Empty pricing ⇒ zero costs ⇒ config-order ties (with
  a boot warning). Streaming contributes when a terminal usage chunk is observed.  
- **Retries / health / breaker:** passive consecutive failures, cooldown (seconds in TOML →
  millis in code), circuit breaker; retry classifier maps provider error types.

Multi-backend aliases: two `[[janus.model-list]]` rows with the same `name` form an ordered
candidate list (failover / LB pool).

## Governance

- **Virtual keys** `sk-janus-…`: hashed at rest; full string only on `POST /key/generate`.  
- **Scopes:** allowlist of models (empty allowlist = allow all).  
- **RPM/TPM:** fixed or sliding window; 429 + `Retry-After` before dispatch.  
- **Budgets:** hard cap reserve/settle in micro-USD; soft cap warnings.  
- **Pricing:** `[[janus.pricing.models]]` USD per 1K tokens → integer micro-USD.  

Auth is **on** by default (`auth = "on"`): a missing master key **fails the boot**.
Explicit `[janus.keys] auth = "off"` is the development/benchmark opt-out.

## Timeouts (operator view)

| Phase | Default | Source | Behavior |
|-------|---------|--------|----------|
| Connect | 10 s | `[janus.timeouts] connect-timeout-seconds` · provider block · model-list entry | Adapter HTTP client |
| Header arrival (non-stream + stream start) | 60 s | `[janus.timeouts] header-timeout-seconds` · provider block · model-list entry | Request timeout on JDK client |
| Non-stream body read | **300 s** | `[janus.timeouts] body-read-timeout-seconds` · provider block · model-list entry | After headers; stall → `timeout` / 504 |
| Non-stream body size | **64 MiB** | code constant (`HttpSupport.MAX_RESPONSE_BODY_BYTES`) | 2xx body cap; over-cap → `bad_upstream_payload` (OOM defense — same rationale as the 8 KiB error-body probe) |
| Stream idle (SSE) | 60 s | `[janus.timeouts] stream-idle-timeout-seconds` · provider block (per dispatch) | Gateway SSE watchdog |

> The four deadlines resolve per key with precedence **model-list entry >
> `[janus.providers.<name>]` block > `[janus.timeouts]`** (seconds; a null
> component at any level falls through — the `base-url`/`api-key-env` merge
> pattern). An absent section or key ⇒ the default shown — `TimeoutContractTest`
> pins the constants and ties them to the config defaults, so a default boot is
> byte-identical and the config override path is the only way to deviate. The
> adapter trio (connect / header / body-read) resolves statically per backend at
> boot; the stream-idle watchdog resolves **per dispatch** — each streaming
> request uses the deadline of the backend that actually served it, keyed by its
> resolved backend name (the dispatch-observer holder: the entry's provider name,
> or the fixed family name for a `wire-format = "anthropic"` fallback — a boot
> warning names the shared key), falling back to the global. The one row with no key
> is the 64 MiB body cap — a code constant by design (an OOM defense, not an
> operator deadline).

## Deployment shapes

| Shape | Store | Notes |
|-------|-------|-------|
| Single-node | memory (default) | Zero external deps |
| Single-node durable | Postgres | Shared keys/spend |
| Multi-node | Postgres | Shared counters/budgets; see `docs/clustering.md` |

Artifacts: native binary, Docker/Compose, k8s, systemd — `docs/ops.md`.

## Observability

- `GET /health`, readiness, liveness  
- `GET /metrics` — Prometheus text; labels: `face`, coarse `status`, `key_id`, `provider`
  (never prompt/response text, model alias, or request id)

## Related docs

| Doc | Topic |
|-----|--------|
| [`routing.md`](./routing.md) | Strategy semantics, tuning |
| [`governance.md`](./governance.md) | Keys, limits, budgets, metrics contract |
| [`clustering.md`](./clustering.md) | Topologies, CAP |
| [`adding-a-provider.md`](./adding-a-provider.md) | SPI contract |
| [`ops.md`](./ops.md) | Runbook |
| [`benchmarks.md`](./benchmarks.md) | Load + JMH |
| [`compatibility.md`](./compatibility.md) | HTTP surface and scope |
| [`responses.md`](./responses.md) | Stateless Responses face |
| [`providers.md`](./providers.md) | Provider/model cookbook (knobs, quirks) |
| [`production-checklist.md`](./production-checklist.md) | Day-1 production checklist |
| [`../config.production.example.toml`](../config.production.example.toml) | Production-oriented sample config |
