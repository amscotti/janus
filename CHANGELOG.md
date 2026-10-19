# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Current-model compatibility refresh (Oct 2026): OpenAI GPT-6 and Anthropic
Claude 5.5 / Fable 5.1.

### Added

- **`POST /v1/messages/count_tokens`** — Anthropic token counting proxied
  through the router to Anthropic-native backends (`ProviderAdapter` and
  `ChatBackend` gain an optional `countTokens` capability). Free and
  unmetered (no governance spend), virtual-key auth + per-key scope still
  apply; providers without an upstream endpoint get a typed `400`.
- **Current model lineup in examples** — `config.toml` /
  `config.production.example.toml` pricing rows for `gpt-6-astra`,
  `gpt-6.1-sol`, `gpt-6-luna` (272K long-context tier) and `claude-fable-5-1`,
  `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-haiku-5-5` (100K tier on
  Haiku) at official published rates.

### Fixed

- **Signed thinking round-trip** — Anthropic `thinking`/`redacted_thinking`
  blocks and `thinking_delta`/`signature_delta` stream events were dropped
  in translation, breaking tool-loop replay on legacy
  `thinking:{"type":"enabled"}` upstreams and hiding reasoning cross-format.
  They now ride canonical `ReasoningContent` slots (`AssistantMessage`,
  `Delta`), re-emit on both faces, and cross to `reasoning_content` /
  Responses `reasoning` items. Unsigned foreign reasoning text is never
  forged into a signed block.
- **Anthropic structured outputs** — `response_format` `json_schema` now
  maps to `output_config.format` in both directions and tool-level `strict`
  round-trips, instead of being dropped on the Anthropic leg.
- **Effort vocabulary** — the Anthropic encode no longer 400s on `xhigh` /
  `max` (the current five levels are `low|medium|high|xhigh|max`), and
  thinking `{"type":"between_tools"}` passes through instead of dropping.
- **GPT-6 prompt caching** — the explicit-breakpoint version gate only
  matched `gpt-5.[6-9]…`, silently stripping `prompt_cache_options` /
  breakpoints for the whole GPT-6 generation. The gate now compares
  versions numerically (≥ 5.6).
- **1M-context request bodies** — the body ceiling is pinned at 32MB
  (`max-request-size` + `max-request-buffer-size`; Micronaut's 10MB
  defaults rejected legitimate 1M-context prompts), and oversize or
  wrong-media-type requests now get the per-face `413`/`415` envelope
  instead of a framework body or a 500.
- **Non-finite config doubles** — `PricingRate` and the budget
  soft-fraction checks used `< 0` range tests that `NaN` slips past
  (TOML admits `nan`/`inf`): a NaN rate silently priced at $0 (budget
  bypass) and a NaN soft fraction zeroed the soft cap. All sites now
  require finite values.

### Changed

- **Dependencies** — Micronaut platform 5.1.0 → 5.2.2 (Jackson 3.2.3,
  jackson-annotations pin 2.22), JUnit BOM 5.14.4 → 6.1.3, Testcontainers
  1.21.4 → 2.0.5 (renamed artifacts `testcontainers-postgresql` /
  `testcontainers-junit-jupiter`, `org.testcontainers.postgresql` package),
  HikariCP 6.3.3 → 7.1.0, PostgreSQL driver 42.7.14, ArchUnit 1.5.1,
  Spotless 8.10.4, JaCoCo 0.8.15.

## [0.1.0] — 2026-08-30

Initial release. Janus is a self-hosted, dual-protocol LLM proxy gateway in
Java 25 + GraalVM native-image (Micronaut 5): one binary, sub-100 ms startup,
configured entirely in TOML.

### Added

- **Two faces, any provider** — OpenAI `POST /v1/chat/completions` and
  Anthropic `POST /v1/messages` (streaming + non-streaming) with full
  cross-format translation: system prompts, tools, streaming deltas, stop
  reasons, and usage cross between the dialects. A stateless OpenAI Responses
  face (`POST /v1/responses`) rounds out the surface.
- **Multimodal, reasoning, structured outputs** — OpenAI `image_url` parts and
  Anthropic `image` blocks (data URL ↔ base64, https ↔ url), `reasoning` /
  `thinking` with `reasoning_content` streaming, and `response_format`
  `json_object` / `json_schema` on the OpenAI-compatible leg.
- **Pluggable providers** — `ProviderAdapter` SPI declared in TOML; verified
  live against DeepSeek, Anthropic, OpenAI, xAI, OpenRouter, Meta, Fireworks,
  Groq, Perplexity (versionless-endpoint support), and Google Gemini.
- **Routing & resilience** — six load-balancing strategies (round-robin,
  least-inflight, latency, cost, weighted, session-affinity), retries with
  exponential backoff + jitter, ordered fallback chains, passive health
  tracking, streaming-safe circuit breaker.
- **Governance** — virtual keys (hashed at rest, shown exactly once), per-key
  model scopes, RPM/TPM rate limits with `Retry-After`, hard/soft budgets,
  and exact integer micro-USD cost accounting per model (including cache
  reads/writes and long-context tiers).
- **Observability** — Prometheus `/metrics` under a Tier-1 privacy contract
  (labels are `face` / `status` / `key_id` / `provider` only — never prompt
  text, response text, model alias, or request id).
- **Store** — in-memory default (zero dependencies) or PostgreSQL via plain
  JDBC for durable single-node state and multi-node clustering with exact
  shared keys/limits/budgets/spend.
- **Packaging** — GraalVM native image (about 42 ms cold boot, 86 MiB binary),
  distroless Docker image (~119 MiB), Compose profiles, Kubernetes manifests,
  systemd unit.
- **Tooling** — offline smoke harnesses for every surface (faces, cross-format,
  routing, governance, store, cluster), opt-in live-provider integration tests,
  load/startup benchmarks with committed raw outputs, and a `verify-artifacts`
  gate (YAML/bash/python parse checks, compose config, kubeconform).
