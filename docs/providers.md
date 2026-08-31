# Provider cookbook

Practical knobs for models Janus is known to exercise in live tests. The SPI is
generic (`openai-compatible` / Anthropic); **wire quirks live on the model**, not
in adapter classes.

See also: [`adding-a-provider.md`](./adding-a-provider.md), live suite
`scripts/live-provider/README.md`.

## Auth & base URLs

| Provider | Env key | Typical base URL | Wire format |
|----------|---------|------------------|-------------|
| DeepSeek | `DEEPSEEK_API_KEY` | `https://api.deepseek.com` | openai-compatible |
| DeepSeek (Anthropic format) | `DEEPSEEK_API_KEY` | `https://api.deepseek.com/anthropic` | anthropic |
| Anthropic | `ANTHROPIC_API_KEY` | `https://api.anthropic.com` | anthropic |
| OpenAI | `OPENAI_API_KEY` | `https://api.openai.com` | openai-compatible |
| xAI | `XAI_API_KEY` | `https://api.x.ai` | openai-compatible |
| OpenRouter | `OPENROUTER_API_KEY` | `https://openrouter.ai/api` | openai-compatible |
| Together | `TOGETHER_API_KEY` | `https://api.together.xyz` | openai-compatible |
| Meta Model API | `META_API_KEY` | `https://api.meta.ai` | openai-compatible |
| Fireworks | `FIREWORKS_API_KEY` | `https://api.fireworks.ai/inference` | openai-compatible |
| Groq | `GROQ_API_KEY` | `https://api.groq.com/openai` | openai-compatible |
| Perplexity | `PERPLEXITY_API_KEY` | `https://api.perplexity.ai/chat/completions` | openai-compatible (versionless endpoint) |
| Google Gemini | `GEMINI_API_KEY` | `https://generativelanguage.googleapis.com/v1beta/openai` | openai-compatible |

`base-url` may include or omit trailing `/v1` — adapters normalize. The one
exception is the **full-endpoint opt-out**: an OpenAI-compatible `base-url`
that already ends with `/chat/completions` is dispatched verbatim (Perplexity
serves a versionless endpoint — an appended `/v1` would 404 there). Secrets are
always **env var names** in TOML (`api-key-env`), never values.

## DeepSeek

| Model id | Role | Notes |
|----------|------|--------|
| `deepseek-v4-flash` | **Current flagship** (newer than Pro) | Primary live path. Defaults to thinking; set `"thinking": {"type": "disabled"}` for short deterministic answers and tools. |
| `deepseek-v4-pro` | Prior tier | Still valid; use when you explicitly want Pro. Live failover uses this id (dead primary + live secondary) because Janus sends the client alias upstream with no remap. |
| `deepseek-v4-flash-vision-exp` | Vision (experimental) | OpenAI `image_url` (data URL or https) and Anthropic `image` blocks. JPEG/PNG/GIF/WebP. Images billed as input tokens (same peak rates as Flash). Only this DeepSeek id accepts images; Flash/Pro return 400. |

DeepSeek also speaks the Anthropic Messages API at
`https://api.deepseek.com/anthropic` (same `DEEPSEEK_API_KEY`, `x-api-key`).
Point a model-list row at that base with `wire-format = "anthropic"` so Janus
uses the Anthropic adapter. Use the real DeepSeek id as the Janus alias
(Janus does not remap). Unknown names on that endpoint are mapped **by
DeepSeek** to Flash.

```toml
[janus.providers.deepseek-anthropic]
wire-format = "anthropic"
base-url = "https://api.deepseek.com/anthropic"
api-key-env = "DEEPSEEK_API_KEY"

[[janus.model-list]]
name = "deepseek-v4-flash"
provider = "deepseek-anthropic"
api-key-env = "DEEPSEEK_API_KEY"
base-url = "https://api.deepseek.com/anthropic"
```

Note: an `anthropic`-family fallback backend dispatches under the family's fixed
backend name `anthropic` (not the block key `deepseek-anthropic`), so a
`stream-idle-timeout-seconds` override on this block keys `anthropic` and applies
to every `anthropic` backend — a boot warning names the shared key.

Do not give the OpenAI-format Flash row and this Anthropic-format row the
same `name` unless you want them in one load-balance/failover pool. Peak
USD-per-1M (cache-miss / output) for Flash and Vision-Exp: `$0.44 / $1.32`
(Janus per-1K: `0.00044` / `0.00132`); cache-hit input `$0.014` / 1M
(`cache-read-per-1k = 0.000014`). Pro is 3× those rates. Off-peak is half.
Operator examples use peak unless you explicitly pick the off-peak band.

## OpenAI / OpenRouter

| Model id | Notes |
|----------|--------|
| `gpt-5.6-luna` | Cost tier. Function tools often need `"reasoning_effort": "none"`. |
| `gpt-5.6` | Full flagship. |
| `openai/gpt-5.6-luna` (OpenRouter) | Same Luna via OpenRouter prefix. |
| `moonshotai/kimi-k3` | Reasoning model; pin `"reasoning": {"effort": "none"}` for short replies. Multimodal (vision). Cache-read `$0.30` / 1M (`cache-read-per-1k = 0.0003`). |
| `minimax/minimax-m3` | Tools with `tool_choice: required` work. Cache-read `$0.06` / 1M. |
| `qwen/qwen3.8-max` | Rejects `tool_choice: required` in thinking mode — use **`auto`**. Janus forwards Anthropic-shaped `cache_control` on content parts (Qwen's OpenAI-compatible explicit-cache marker). OpenRouter's published explicit-cache list is `qwen3-max` / `qwen-plus` / coder variants — `qwen3.8-max` currently reports `cached_tokens: 0` even on a direct OpenRouter call. |

## Anthropic

| Model id | Notes |
|----------|--------|
| `claude-sonnet-5` | Default Anthropic live path; native face + OpenAI face both work. |
| `claude-opus-5` | Flagship. |

Vision: Anthropic image blocks (`type: image`, base64 or url source) and OpenAI
`image_url` parts both round-trip through the canonical model. HTTPS image URLs
map to `source.type=url` (Anthropic fetches the image). The host must be
publicly reachable and not blocked by robots.txt — Wikimedia currently 400s
with `Unable to download the file`.

## xAI

| Model id | Role | Notes |
|----------|------|--------|
| `grok-4.6` | **Current flagship** | Text + image → text, 500k context, function calling, structured outputs. Reasoning defaults to **`high`** and cannot be disabled — pin `"reasoning_effort": "low"` for short/latency-sensitive calls (`medium` / `high` / `xhigh` also valid). Official price $2.00 / $0.50 cached / $6.00 per 1M tokens below 200k prompt (Janus USD-per-1K: `0.002` / `0.0005` / `0.006`). At ≥200k the whole request doubles — set `long-context-threshold = 200000` plus the `long-*` rates on the same pricing row. |
| `grok-4.5` | Prior flagship | Same `reasoning_effort` contract without `xhigh` (unknown values treated as `high`). Same 200k long-context doubling (`$4 / $0.60 cached / $12` per 1M). Cache-read below 200k is `$0.30` / 1M (`cache-read-per-1k = 0.0003`). |

Vision: Grok accepts JPEG/PNG `image_url` data URLs (a 1×1 PNG is
`invalid_image` — use a real raster) and public `https://` image URLs. The image
host must allow xAI's fetcher (Wikimedia works; some W3C hosts 403). `detail` is optional (`high` is the
documented xAI spelling).

OpenAI-compatible encode passes `reasoning_effort` strings through, including
`xhigh`, `max`, and `ultra`. The Anthropic encode still 400s those spellings
(Anthropic's vocabulary is `low|medium|high|minimal|none`).

Prompt cache markers translate in both directions:

- **OpenAI GPT-5.6+** (Chat Completions and Responses): a content part carries
  `prompt_cache_breakpoint: {"mode": "explicit"}` (boolean `true` is invalid
  upstream and is rewritten to the object on encode). Request-level
  `prompt_cache_key` and `prompt_cache_options` (`mode` `implicit`/`explicit`,
  `ttl` `"30m"`) pass through on the OpenAI wire. Encode emits these fields only
  for the GPT-5.6 family (`gpt-5.6`, `gpt-5.6-luna`, `openai/gpt-5.6-luna`,
  later `gpt-5.x`); earlier models 400 them, so Janus strips the breakpoint and
  options. Minimum cacheable prefix is 1,024 tokens.
- **Qwen (OpenRouter / Alibaba):** OpenAI-compatible content parts carry
  `cache_control: {type: ephemeral}` (same shape as Anthropic). Janus decodes
  that marker on the OpenAI face and re-emits it for Qwen aliases; GPT-5.6
  breakpoints are not sent (those ids 400 them).
- **Anthropic**: `cache_control: {"type": "ephemeral"}` on a system or message
  text block (and request-level for automatic caching). Optional
  `ttl: "1h"` (default 5 minutes) round-trips on the Anthropic wire. A marker on
  an assistant `tool_use`/text block (the agent-loop caching pattern) has no
  per-block canonical home: it is captured to the request-level slot and re-emitted
  on the `system` block when one exists, else the last user message — the marker
  survives the round trip, though its position may move. OpenAI extras
  `prompt_cache_key` / `prompt_cache_options` have no Anthropic home and are
  dropped.
- **Cross-format:** OpenAI object breakpoint ↔ Anthropic block `cache_control`.
  A system-prefix marker becomes an Anthropic `system` text-block array; the
  reverse emits the OpenAI breakpoint on the leading system (or developer)
  message for GPT-5.6+ aliases.

Together is OpenAI-compatible at `https://api.together.xyz`. Replay of
`reasoning_content` extras is stripped on encode; request-level `thinking`
and `chat_template_kwargs` pass through.

## Meta Muse Spark

| Model id | Notes |
|----------|--------|
| `muse-spark-1.2` | Direct Meta API (`META_API_KEY`). Reasoning burns completion budget — use **`max_tokens` ≥ ~1024** or content can be empty with `finish_reason=length`. Only **`tool_choice: auto`** (not `required`). Prefer `"reasoning_effort": "low"`. |

## Fireworks / Groq / Perplexity / Gemini (direct)

Plain OpenAI-compatible upstreams — declare `wire-format = "openai-compatible"`
with the base URLs from the table above; the alias is sent upstream unchanged.

| Model id | Provider | Notes |
|----------|----------|-------|
| `accounts/fireworks/models/glm-5p3` | Fireworks | GLM 5.3; reasoning-capable — 512-token headroom for one-shot replies. |
| `openai/gpt-oss-120b` | Groq | gpt-oss serverless; reasoning-capable — 512-token headroom. |
| `sonar` | Perplexity | Search-grounded; versionless endpoint — see the full-endpoint opt-out above. |
| `gemini-3.7-flash` | Google Gemini | Thinking model — 512-token headroom for one-shot replies. |

## Reasoning (first-class)

- **Request:** `ChatRequest.reasoning` / OpenAI `reasoning` object / Anthropic
 `thinking` map are first-class maps (not silent drops).
- **Stream deltas:** DeepSeek-style `reasoning_content` rides `Delta.reasoning` and
 re-emits inside the delta.
- **Usage:** `Usage.reasoningTokens` from OpenAI
 `completion_tokens_details.reasoning_tokens` when present (display/accounting
 transparency). **Pricing** still uses prompt + completion rates; reasoning
 tokens are typically already counted inside `completion_tokens` by providers.

## Structured outputs

- OpenAI `response_format` (`json_object` / `json_schema`) is a first-class
 `ChatRequest.responseFormat` map and round-trips on the OpenAI-compatible leg.
- Anthropic has no equivalent field — the map is dropped on Anthropic encode
 (documented non-idempotence), same as other OpenAI-only knobs.

## Multimodal / vision

- User messages may carry **string** content or an **array of parts**
 (`text`, `image_url` on OpenAI; `text`, `image` on Anthropic).
- Data URLs (`data:image/png;base64,…`) convert to Anthropic base64 sources and
 back. `https://` image URLs map to Anthropic `source.type=url`.
- Assistant / system / tool messages remain string content only.
