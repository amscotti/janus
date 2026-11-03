# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **System One decision models** — `POST /v1/systemone`, the Jev-shaped
  decisions face (`choice`/`noul`/`score`/`box` questions over a `state`,
  typed probability answers, non-streaming). One client dialect routes by
  model id to TypeSafe (Jev), OpenRouter, Ollama local/cloud decision models
  (`d3`, `clef`, `nimble-v3`, `tev1`), Cloudflare Clef (generic `systemone`
  wire), and OpenAI Decisions (`gpt-6-luna`, translated). Configure via
  `[[janus.decision-model-list]]` + `[janus.decision-providers.*]` (with
  `upstream-model` remap and a `vision` gate for image evidence); virtual
  keys, scopes, budgets, pricing, round-robin/failover routing, and Tier-1
  metrics (`face="systemone"`) apply. See `docs/system-one.md`; offline gate
  in `scripts/smoke/decisions/`.
- **TypeSafe danger-zone support** — `TypeSafe-Danger-Zone-Images` is
  forwarded from the client and derived from `assets`/Box bodies;
  `TypeSafe-Danger-Zone-Long-Context` is forwarded; `assets` ride the
  `systemone` leg; Box questions/answers are supported end to end. Image
  evidence in any dialect (`images[]`, TypeSafe `assets`, Box) routes to
  `vision = true` backends.
- **Structured TypeSafe entries** — `instructions` and criteria values accept
  strings, objects, arrays, or null on the `systemone` leg; the OpenAI leg
  folds structure to text (documented loss). TypeSafe's single-option
  choice/level minimums are accepted at the face; OpenAI's 2–255/2–10 caps
  are enforced per picked backend.
- **Cloudflare Workers AI envelope unwrap** — `/ai/run/` responses are
  unwrapped from `{result, success, errors, messages}`; `success:false` is a
  typed upstream failure instead of a 502 decode error on every success.
- **TypeSafe SDK request compatibility** — `POST /v1/systemone` accepts
  zstd-compressed bodies (the SDK default for bodies ≥ 1 KiB; decompressed
  under the 32 MiB ceiling) and `multipart/form-data` uploads (JSON `request`
  part + image file parts inlined into `assets` as `data_base64`/`format`).
  Image parts nested in `state` are detected for `vision` routing.
- **OpenRouter decision catalog** — the generic `systemone` provider routes
  OpenRouter's decision models by `upstream-model` (Jev, Cloudflare Clef
  variants, Liquid, Perplexity, Microsoft, Together, OpenAI Luna Decisions,
  and others). OpenRouter's Clef facade uses `state`-array `image_url` parts.

### Fixed

- **Cloudflare Clef** — a successful Workers AI call no longer becomes a
  502 because the model answer is nested under `result`.
- **Upstream usage is validated** — non-negative integral token counts,
  overflow-checked totals, and typed 502s for malformed/negative/out-of-range
  usage. A bad upstream can no longer charge `Long.MAX_VALUE` micro-USD and
  permanently exhaust a key's budget.
- **Decisions are input-only at settle** — the OpenAI leg's cached/cache-write
  input is folded back into the input term, output/cache are zeroed, and a
  response without `usage` settles the pre-dispatch estimate instead of $0.
  The call ledger's cache columns stay null, matching the docs.
- **Decision config fails boot** — a startup probe resolves the decision
  router and price table, so an unknown provider, blank base URL, invalid
  `wire-format`, dead block, or missing `require-priced` decision row refuses
  the node instead of passing `/health` and failing the first request.
- **Config binding no longer drops invalid decision entries** — validation
  moved out of the bound record constructors (Micronaut silently discarded a
  map element whose constructor threw) into `DecisionModelListFactory`, with
  messages naming the entry/key. A literal secret in `api-key-env` is no
  longer echoed in the missing-env warning.
- **The decision estimate prices the serialized request** — compact
  state/questions/extras bytes ÷ 4, plus 1024 per `images[]`/`assets` entry,
  including unmodeled extras; the old content-only sum under-gated TPM/budget
  by ~6–20× and could estimate 0.
- **Ledger hygiene** — client-controlled model/provider strings are capped
  and control-stripped before persistence; `LogSafe` no longer allocates a
  full-size builder before truncating.
- **Codec trust boundary** — non-finite/out-of-range numbers are typed
  errors (never re-emitted as `"Infinity"` strings); raw Jackson conversion
  failures are typed 502s; duplicate JSON keys are rejected; `state` must be
  string/object/array; `stream` is a typed 400; raw `images[]` must be
  base64 (URLs/data-URLs are typed 400s); malformed OpenAI answers
  (missing probabilities/score, non-numeric confidence) are typed 502s
  instead of fabricated 0.0/`{}` decisions; OpenAI `input_json` accepts an
  object or array of objects (scalar arrays become text), with depth/value/
  byte caps; OpenAI usage preserves the wire `total_tokens`.
- **Vision/asset routing** — `assets`/Box requests route to `vision = true`
  backends; the client response echoes the client alias, never the
  `upstream-model` remap; per-backend cap failures fail over to another
  candidate instead of returning a round-robin-position-dependent 400.
- **Router failure handling** — after every candidate has had one shot the
  router throws the last failure instead of re-picking candidate 0; a cached
  exception instance can no longer trigger `Self-suppression not permitted`;
  a throwing dispatch observer is logged and dropped; a classifier `Error`
  no longer replaces the backend failure; blank aliases are rejected at
  construction.
- **Base URLs with a query/fragment** are rejected at boot instead of
  appending the versioned path into the query.
- **Reservation release is exactly-once** (the existing guard) and usage
  metrics are best-effort on every settled path (a throwing recorder no
  longer turns an accounted 200 into a 500 plus a second ERROR row).
- **Metrics privacy** — nested decision question/answer records no longer
  print instructions/criteria/answers in `toString` when content logging is
  off; the `face="systemone"` privacy test plants the marker in answers too.

### Changed

- The `face` metric label enumerations in `config.toml`,
  `docs/governance.md`, and `docs/ops.md` now list `systemone` (five
  values).
- `docs/system-one.md`, the provider cookbook, config reference, production
  example, ops runbook, production checklist, README, architecture, and
  adding-a-provider guide now cover decision models, the TypeSafe danger
  zone, the per-dialect image shapes, boot validation, and input-only
  billing.
- The production example's Jev price is the documented `$0.042/Mtok`
  (`0.000042`/1K), not `0.0005`.

### Added (tests)

- Decision governance (failure status table, input-only reservation,
  settle, exactly-once release, missing-usage estimate, input-only
  OpenAI usage), `DecisionRouterFactory` (constructor switch,
  ServiceLoader ↔ constructor coupling, wire-format/NAME literals,
  ignored-strategy warning), decision-face auth (401/403 before dispatch),
  privacy-contract cases for `face="systemone"`, 413 on the decision face,
  vision routing (assets/Box included), documented caps (400 questions /
  256 KiB / 2–10 levels / 2–255 choices), fixture decode in the manifest
  guard, ArchUnit placement rules for `DecisionBackend`/`DecisionAdapter`,
  the decision adapters' timeout constants, Cloudflare envelope unwrap,
  TypeSafe danger-zone header forwarding/derivation, local `images[]` and
  OpenAI `input_image` shapes, TypeSafe SDK zstd request bodies (round-trip +
  corrupt-body 400), multipart `request`/file-part inlining (missing/orphan/
  duplicate part typed 400s), and the codec trust-boundary regressions
  (non-finite/overflow/duplicate keys/structured entries/Box).

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
