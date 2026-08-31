# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
