# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.2.0] — 2026-10-24

Current-model compatibility refresh: OpenAI GPT-6, Anthropic Claude 5.5 /
Fable 5.1, and DeepSeek V4.1-Flash.

### Added

- **`POST /v1/messages/count_tokens`** — Anthropic token counting proxied
  through the router to Anthropic-native backends (`ProviderAdapter` and
  `ChatBackend` gain an optional `countTokens` capability). Free and
  unmetered (no governance spend), virtual-key auth + per-key scope still
  apply; providers without an upstream endpoint get a typed `400`.
- **Current model lineup** — examples and live coverage for `deepseek-flash`
  (V4.1-Flash text + vision), `gpt-6-astra`, `gpt-6.1-sol`, `gpt-6-luna`,
  `claude-fable-5-1`, `claude-opus-5-5`, `claude-sonnet-5-5`, and
  `claude-haiku-5-5` at official published rates.
- **GPT-6 cache lifetimes** — canonical usage retains the 12-hour cache-write
  subset separately from the 30-minute class, and pricing supports
  `cache-creation-12h-per-1k` / `long-cache-creation-12h-per-1k`. OpenAI Chat
  and Responses usage re-emits `cache_write_tokens_details`.

### Fixed

- **DeepSeek V4.1 refresh** — production, Kubernetes, live-provider,
  real-upstream smoke, and operator examples now use `deepseek-flash`
  (V4.1-Flash-1017) with current peak rates ($0.30 input / $0.006 cache hit /
  $1.20 output per 1M), and Pro cache reads use $0.044 per 1M. Retired
  `deepseek-v4-flash` / vision-exp ids are documented as compatibility aliases.
  Native vision is attributed to Flash instead of the retired experimental row,
  and live vision coverage targets the current `deepseek-flash` id directly.
- **Anthropic → OpenAI structured outputs** — importing Anthropic
  `output_config.format` now restores the schema `name` (stable `response`)
  and `strict: true` required by OpenAI, instead of emitting a request OpenAI
  rejects.
- **Exact cache-write accounting** — mixed GPT-6 30-minute/12-hour writes no
  longer collapse onto one rate. Settle, cost headers, and pre-dispatch budget
  estimates use the 12-hour rate where applicable; responses without a split
  retain the previous behavior. TTL details are emitted only for a known
  OpenAI split (including an explicit zero 12-hour bucket); unknown or
  Anthropic 5-minute/1-hour lifetimes are never mislabeled as 30-minute writes.
- **GPT-6 long-context boundary** — examples and live pricing use
  `long-context-threshold = 272001`; 272,000 input tokens still bill at the
  short-context rate, matching OpenAI's inclusive boundary.
- **Adaptive thinking display** — Anthropic `thinking.display`
  (`summarized` / `omitted`) now survives decode → encode for adaptive/enabled
  thinking instead of being dropped.
- **Signed thinking round-trip** — Anthropic `thinking`/`redacted_thinking`
  blocks and `thinking_delta`/`signature_delta` stream events were dropped
  in translation, breaking tool-loop replay on legacy
  `thinking:{"type":"enabled"}` upstreams and hiding reasoning cross-format.
  They now ride canonical `ReasoningContent` slots (`AssistantMessage`,
  `Delta`), re-emit on both faces, and cross to `reasoning_content` /
  Responses `reasoning` items. Unsigned foreign reasoning text is never
  forged into a signed block.
- **Anthropic structured outputs** — `response_format` `json_schema` maps to
  `output_config.format` in both directions and tool-level `strict`
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
  require finite values, with TOML-binding and PostgreSQL tests.
- **Native Netty reflection** — registered `ReferenceCountUtil` so chunked
  native responses (SSE and `/metrics`) do not fail at runtime.

### Changed

- **Release gating** — Docker publication now requires the build, a tag-wide
  version verification, a full Gradle test gate, and the native PostgreSQL
  drill; each architecture is smoke-tested before its tags are pushed.
  macOS release builds run the native codec/SSE/metrics smoke, and CI/release
  execute the native PostgreSQL drill.
- **Dependencies** — Micronaut platform 5.1.0 → 5.2.2 (Jackson 3.2.3,
  jackson-annotations pin 2.22), JUnit BOM 5.14.4 → 6.1.3, Testcontainers
  1.21.4 → 2.0.5 (renamed artifacts `testcontainers-postgresql` /
  `testcontainers-junit-jupiter`, `org.testcontainers.postgresql` package),
  HikariCP 6.3.3 → 7.1.0, PostgreSQL driver 42.7.14, ArchUnit 1.5.1,
  Spotless 8.10.4, JaCoCo 0.8.15.
- **Smoke harnesses** — warmup batches now fail on request errors, missing
  results, or timed-out threads instead of silently sampling a cold baseline;
  the README walkthrough captures `/metrics` before grepping it, so `pipefail`
  no longer reports curl's early-close exit 23 after a successful scrape.

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
