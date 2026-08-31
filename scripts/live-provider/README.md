# Live & production-shape tests (opt-in)

Real-network and production-shape drills. **None of these run under**
`./gradlew build` / default `test` — no accidental token spend.

| Suite | How to run | Spends money? | Default `test`? |
|-------|------------|---------------|-----------------|
| JVM live providers (incl. flagships) | `JANUS_LIVE=1 ./gradlew :janus-gateway:liveTest` | Yes (if keys set) | **No** (`@Tag("live")`) |
| Native + real APIs | `JANUS_LIVE=1 bash scripts/live-provider/run_native_live.sh` | Yes | **No** — now includes a Responses-face check (`object=response`, `store:false`, `completed`) |
| Multi-node Postgres | `./gradlew :janus-gateway:twoNodeTest` (or full `test`) | **No** (fake upstream) | **Yes** when Docker is up |
| JMH microbenches | `./gradlew :janus-core:jmh` | No | **No** |
| Store layer multi-node gate | `bash scripts/smoke/store/run.sh` | No (fake) | **No** (manual gate) |

CI/CD: `.github/workflows/ci.yml` schedules the **JVM live** task
(`:janus-gateway:liveTest`) on every push/PR to `main`, gated so there is no
accidental spend — `JANUS_LIVE=1` is always set (class enabled) but without repo
secrets every method is SKIPPED via per-key `Assumptions` (green by skip). Real
spend happens only when the repo secrets `DEEPSEEK_API_KEY` / `ANTHROPIC_API_KEY` /
`OPENAI_API_KEY` / `XAI_API_KEY` / `OPENROUTER_API_KEY` are configured. The
native-live, JMH and multi-node gates below stay ad-hoc (opt-in local commands).

---

## JVM live (`LiveProviderIT`)

Real Janus (production DI) → real upstreams. Three gates:

1. `@Tag("live")` excluded from default `test` / `build`
2. `JANUS_LIVE=1` class-level opt-in
3. Per-provider key `Assumptions` → SKIPPED when unset

```bash
set -a && source .env && set +a
export JANUS_LIVE=1
./gradlew :janus-gateway:liveTest
```

One opt-in only (`JANUS_LIVE=1`). Additional frontier models (`deepseek-v4-pro`,
`gpt-5.6`, `claude-opus-5`) run in the same suite; they skip only when that
provider's key is unset — there is no second spend tier.

Note: **DeepSeek's current flagship is `deepseek-v4-flash`** (newer than Pro).
`deepseek-v4-pro` remains in the suite as a second DeepSeek id, not as "newer than Flash."

### Coverage

| Area | Cases |
|------|--------|
| OpenAI face chat | Non-stream + stream per provider with a key; stream framing (`data:` + `[DONE]`) |
| OpenRouter matrix | Luna + **Kimi K3** + **MiniMax M3** + **Qwen3.8 Max** + **GLM 5.3** (`z-ai/glm-5.3`): chat, stream, tools, multi-round (GLM 5.3: chat + stream) |
| Meta Muse Spark | **muse-spark-1.2** via `META_API_KEY` / `api.meta.ai`: chat, stream, tools (`tool_choice=auto`), multi-round |
| Multi-round chat | Codeword plant → recall (DeepSeek; Claude faces; OpenRouter Kimi/Qwen; Meta Muse) |
| System message | System role survives OpenAI codec and influences reply (DeepSeek) |
| Tool calling | Single-tool multi-turn (DeepSeek / OpenAI / Anthropic / OpenRouter / Meta); stream tool deltas; cross-face tools |
| Multi-tool | Parallel tools (`get_weather` + `get_time`) + multi-result follow-up (DeepSeek / OpenAI); Anthropic multi `tool_use` |
| Thinking | DeepSeek with `thinking: enabled`; Claude `thinking.type=adaptive` + `output_config.effort` |
| Structured outputs | OpenAI `json_schema` on Luna and Grok; DeepSeek `json_object`; Responses `text.format` json_schema → Luna |
| Prompt cache | Write+hit on Luna chat/Responses and Sonnet (native + both cross-format directions); stream write then hit (chat, Anthropic, Responses); Anthropic last-tool `cache_control`; Anthropic `ttl: "1h"`; two Anthropic breakpoints; implicit (no marker) write+hit on DeepSeek Flash, Grok 4.6 (cache warms asynchronously — the hit call is retried with spacing), OpenRouter Kimi K3 and MiniMax M3 (OpenRouter round-robins backends and implicit caches are per-backend — the hit is retried and asserted when routing permits); OpenRouter Luna GPT-5.6 breakpoints; OpenRouter Qwen3.8 Max `cache_control` accepted (OpenRouter reports 0 cached tokens for that id) |
| Anthropic face | Non-stream, stream, tool_use, tool_result multi-turn, multi-round, multi-tool, native `web_search_20250305` |
| Models list | `GET /v1/models` lists configured aliases |
| Cross-format | OpenAI face → Claude; Anthropic face → DeepSeek |
| Failure modes | Unscoped unknown model → 404; scoped → 403; empty messages → 400 |
| Auth | Garbage virtual key → 401 |
| Governance | RPM 429; **TPM** preflight 429; budget preflight 429 (no spend when estimate > cap) |
| Spend settle | Response usage → `janus_key_*` metrics match pricing (`CostCalculator` micro-USD; DeepSeek Flash + Grok 4.6) |
| Responses face | non-stream text+usage (`object:response`, `store:false` echo); **store:true → named 400**; two-cycle tool-call **replay** (function_call → function_call_output → text) |
| Responses cross-format matrix | `/v1/responses` → **OpenAI** (non-stream, stream w/ usage-on-completed, reasoning_effort), **Anthropic** (non-stream + stream — effort→thinking live; **web_search** hosted tool with `web_search_call` items back), **xAI** (reasoning low), **OpenRouter** (kimi-k3); "vice versa": Anthropic face `/v1/messages` → gpt-5.6 |
| Agent loop | Multi-tool two-cycle history (DeepSeek: tools → results → tools again) |
| Stream settle | `stream_options.include_usage` terminal usage; **client abort** after first `data:` frame |
| Multimodal / vision | OpenAI-face `image_url` data-URL → Luna, Grok, Claude, Kimi K3, DeepSeek vision-exp; Anthropic-face `image` block → Claude and vision-exp; HTTPS image URLs (Luna + Claude on a W3C JPEG; Grok on a jsDelivr-hosted image — Wikimedia began 429-rate-limiting xAI's crawler and each provider's crawler has a different allowlist) |
| Failover | Dead primary (`127.0.0.1:1`) → live DeepSeek secondary |
| Extra frontier ids | `deepseek-v4-pro` (older DeepSeek tier), `gpt-5.6`, `claude-opus-5`, `grok-4.5` (same `JANUS_LIVE` gate) |

Source: `janus-gateway/src/test/java/io/amscotti/janus/gateway/live/LiveProviderIT.java`

### Models (current API ids)

| Provider | Model id | Notes |
|----------|----------|--------|
| DeepSeek | `deepseek-v4-flash` | **current DeepSeek flagship** (primary live path; newer than Pro) |
| DeepSeek | `deepseek-v4-pro` | prior DeepSeek tier (still exercised for coverage) |
| DeepSeek | `deepseek-v4-flash-vision-exp` | vision (experimental); OpenAI `image_url` + Anthropic `image` |
| DeepSeek Anthropic API | `deepseek-v4-flash` via `https://api.deepseek.com/anthropic` | `DeepSeekAnthropicLiveIT` — Anthropic adapter, same API key |
| Anthropic | `claude-sonnet-5` | Sonnet 5 |
| Anthropic | `claude-opus-5` | flagship |
| Anthropic | `claude-haiku-4-5` | Haiku 4.5 |
| OpenAI | `gpt-5.6-luna` | cost tier |
| OpenAI | `gpt-5.6` | flagship |
| xAI | `grok-4.6` | **current flagship** (reasoning defaults to `high`; live cases pin `low`) |
| xAI | `grok-4.5` | prior flagship |
| OpenRouter | `openai/gpt-5.6-luna` | Luna via OR |
| OpenRouter | `moonshotai/kimi-k3` | Kimi K3 (reasoning; tests pin `reasoning.effort=none`) |
| OpenRouter | `minimax/minimax-m3` | MiniMax M3 |
| OpenRouter | `qwen/qwen3.8-max` | Qwen3.8 Max (tools use `tool_choice=auto`) |
| Meta | `muse-spark-1.2` | Muse Spark 1.2 (`META_API_KEY`, base `https://api.meta.ai`) |
| Fireworks | `accounts/fireworks/models/glm-5p3` | GLM 5.3 (`FIREWORKS_API_KEY`, base `https://api.fireworks.ai/inference`) |
| Groq | `openai/gpt-oss-120b` | gpt-oss-120b serverless (`GROQ_API_KEY`, base `https://api.groq.com/openai`; reasoning-capable — 512-token headroom) |
| Perplexity | `sonar` | search-grounded (`PERPLEXITY_API_KEY`); VERSIONLESS endpoint — base-url ends with `/chat/completions` (adapter full-endpoint opt-out) |
| Google Gemini | `gemini-3.7-flash` | direct OpenAI-compat (`GEMINI_API_KEY`, base `https://generativelanguage.googleapis.com/v1beta/openai`); thinking model — 512-token headroom |
| OpenRouter | `z-ai/glm-5.3` | GLM 5.3 (reasoning mandatory upstream — no effort pin; 512-token headroom) |
| Together | `openai/gpt-oss-20b` | serverless id (`TOGETHER_API_KEY`; skips when unset) |
| DeepSeek | `deepseek-v4-flash` | current flagship; primary live path |
| DeepSeek | `deepseek-v4-pro` | prior tier; live failover alias (dead + live) |

Secrets / env: also `META_API_KEY` for Muse Spark (passed through by the `liveTest` Gradle task).

---

## Native + real providers

```bash
set -a && source .env && set +a
export JANUS_LIVE=1
# uses DEEPSEEK_API_KEY or ANTHROPIC_API_KEY
bash scripts/live-provider/run_native_live.sh
# binary already built:
bash scripts/live-provider/run_native_live.sh --skip-build
```

Boots `janus-gateway/build/native/nativeCompile/janus` with
`MICRONAUT_CONFIG_FILES=scripts/live-provider/config.native-live.toml`, mints a
virtual key, non-stream + stream chat, and a stateless `/v1/responses` create.
First exec after `nativeCompile` can sit in dyld for tens of seconds on macOS;
the harness waits up to 60s for `/health`. Log: `scripts/live-provider/.run-native-live.log`.

Distinct from `scripts/smoke/store/drill_native.py` (native + **golden fake** + Postgres).

---

## Multi-node Postgres (no live API spend)

Automated in **`TwoNodeIntegrationTest`** (Docker + Testcontainers):

- Two production-wired EmbeddedServers, one Postgres
- Key generate on A → auth on B (both faces, stream + non-stream)
- Shared RPM / budget hard caps
- Spend aggregates + `CallRecord`s in shared DB

```bash
./gradlew :janus-gateway:twoNodeTest # or ./gradlew twoNodeTest
# also runs as part of:
./gradlew :janus-gateway:test # skips cleanly without Docker
```

Process-level gate (two real binaries + drills): `scripts/smoke/store/run.sh`.
