# HTTP surface

Janus serves one listener (default port 8080). Data, admin, and ops share that
port — there is no split.

## Endpoints

| Plane | Endpoint | Status |
|---|---|---|
| Data | `POST /v1/chat/completions` | JSON + SSE; tools; cross-format translation |
| Data | `POST /v1/messages` | JSON + SSE; tools; cross-format translation |
| Data | `GET /v1/models` | Configured aliases, config order; `owned_by` is the backend name |
| Data | `GET /v1/models/{id}` | One alias; unknown id → 404 `model_not_found` |
| Data | `POST /v1/responses` | Stateless JSON + SSE; see [`responses.md`](./responses.md) |
| Data | `GET`/`DELETE /v1/responses/{id}` | Envelope 404 stubs (no stored retrieval) |
| Admin | `POST /key/generate` | Master-key-authed; full `sk-janus-…` shown once |
| Admin | `POST /key/delete` | Master-key-authed; by `key_id` or full key; idempotent |
| Admin | `GET /key/list` | Master-key-authed; redacted (no full key, hash, or salt) |
| Ops | `GET /health`, `/health/readiness`, `/health/liveness` | Probes |
| Ops | `GET /metrics` | Prometheus text; `/prometheus` is 404 |

Not implemented: embeddings, an admin dashboard, teams/orgs, `/sessions/*`,
`/cache/*`, `/user/*`, `/global/*`, `X-RateLimit-*` headers, per-request
parallelism caps.

## Features

| Feature | Where it lives |
|---|---|
| OpenAI-format ingress | `ChatCompletionsController` + `OpenAiMessageCodec` |
| Anthropic-format ingress | `MessagesController` + `AnthropicMessageCodec` |
| Responses-format ingress | `ResponsesController` + `OpenAiResponsesCodec` |
| Cross-format translation | Canonical model in `janus-core` (system, tools, deltas, stop, usage) |
| Streaming (SSE) | Face publishers; `stream_options.include_usage` is passed through (the Responses face forces it so `response.completed` always carries usage) |
| Tool calls | Both directions; golden fixtures under `fixtures/matrix/` |
| `developer` role | OpenAI wire keeps `"developer"`; Anthropic merges it into `system` |
| Vision / image input | User-message `ContentPart`s (data URL and https) |
| Prompt cache | OpenAI GPT-5.6+ `prompt_cache_breakpoint` object + `prompt_cache_key`/`prompt_cache_options`; Qwen OpenAI-compatible `cache_control` on content parts; Anthropic `cache_control` on content blocks (optional `ttl: "1h"`); translated both ways. Chat Completions usage re-emits `prompt_tokens_details.cached_tokens` / `prompt_cache_hit_tokens` |
| Structured outputs | `response_format` on the OpenAI-compatible leg; dropped on Anthropic encode |
| Reasoning | Request maps + `reasoning_content` deltas + optional `Usage.reasoningTokens` |
| Virtual keys | Hashed at rest; timing-safe compare |
| Per-key model scopes | Alias allowlist |
| Rate limits (RPM/TPM) | 429 + `Retry-After`; throttled requests never reach the upstream |
| Budgets | Hard cap reserve/settle; soft cap → `X-Janus-Budget-*` + optional webhook |
| Spend headers | `X-Janus-Cost-*-Micro-Usd` on non-stream success (input/output/cache/search/total) |
| Pricing | `[[janus.pricing.models]]` USD-per-1K → integer micro-USD |
| Load balancing | round-robin, least-inflight, latency-based, cost-based, weighted, session-affinity |
| Retries + fallback | Backoff + jitter; candidate list in config order |
| Health + circuit breaker | Passive health; closed → open → half-open; streaming-safe |
| Multi-node state | Shared Postgres (no gossip) |
| Prometheus | Tier-1 labels only (`face`, coarse `status`, `key_id`, `provider`) |

## Authentication

- **Master key** (`[janus.keys] master-key-env`) authenticates `/key/*` via
  `Authorization: Bearer` or `x-api-key`. A virtual key, a wrong master key, or
  a missing credential → `401 authentication_error`.
- **Virtual keys** (`sk-janus-…`) authenticate `/v1/chat/completions`,
  `/v1/messages`, and `/v1/responses`. Missing / invalid / expired → `401`.
  Revoked or scope-denied → `403 permission_error`.
- **Default is auth on.** A boot with no resolvable master key fails fast.
  `[janus.keys] auth = "off"` is the explicit development/benchmark opt-out
  (loudly logged).
- **Exempt from auth:** `/health*`, `/metrics`, `/v1/models`, and unlisted paths.

## Errors

| Condition | Behavior |
|---|---|
| Bad/missing key on model routes | `401 authentication_error` (OpenAI envelope `code: "invalid_api_key"`) |
| Revoked key or scope denial | `403 permission_error` (OpenAI envelope `code: "forbidden"`) |
| Rate limit | `429 rate_limit_error` + `Retry-After`; `code: "rate_limit_exceeded"` |
| Budget cap | `429 rate_limit_error` without `Retry-After`; `code: "insufficient_quota"` |
| Upstream 429/5xx/network/timeout | Retried, then fallback; terminal maps to the face envelope |
| Upstream 4xx | Never retried; propagated |
| Circuit breaker OPEN | Dispatch refused except one half-open probe |
| Postgres down at boot | Node refuses to start (error names the env var, never the URL) |
| Postgres down mid-run | Clean `500 api_error`; pool recovers on its own |
| Client aborts a stream | Upstream cancelled; no call record; request lands in the `4xx` metrics bucket (499) |
| Extended thinking | `thinking`/`redacted_thinking` blocks in assistant messages are dropped at decode (no canonical home yet) and `thinking_delta`/`signature_delta` stream events are not re-emitted. On current adaptive-thinking models (`claude-sonnet-5`, `claude-opus-5`, `claude-haiku-4-5`) a replayed assistant turn without thinking blocks is accepted; legacy `thinking:{"type":"enabled"}` upstreams may reject the second turn. Full thinking passthrough is deferred |

## Deliberate choices

- **One binary, one port.** Data, admin, and ops share a listener.
- **Exact cross-node governance.** With Postgres, RPM, budget, and spend are
  exact cluster-wide (atomic upserts).
- **Privacy-tiered metrics.** Labels never carry prompt text, response text,
  model alias, or request id.
- **Revoked keys are 403**, so a client can tell a bad key from a key that was
  taken away.

## Source of truth

- Endpoints: `janus-gateway` controllers and `application.toml`
- Governance: [`governance.md`](./governance.md) and `config.toml`
- Routing: [`routing.md`](./routing.md)
- Store / multi-node: [`clustering.md`](./clustering.md)
- Responses face: [`responses.md`](./responses.md)
