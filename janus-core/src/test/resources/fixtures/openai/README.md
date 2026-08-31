# OpenAI golden fixtures (OpenAI-compatible wire shapes) — 

Representative OpenAI Chat Completions wire-shape fixtures for the codec replay
tests. **Status: hand-authored from the DeepSeek/OpenAI documented wire shapes — NOT a
verbatim real-upstream capture** (the C1 review finding; a real capture needs a live
DeepSeek key, and the capture harness + Gradle task below are ready for it). CI never touches the network (AGENTS.md).

## Provenance (honest)

- **C1 correction:** the timestamps, ids and usage in these files are synthetic
 stand-ins that follow the documented shapes; they do not claim to be captured bytes.
 The semantic replay tests therefore pin *shape fidelity*, not *real-capture fidelity*.
- Re-capture (when a real key is available):
 `JANUS_FIXTURE_CAPTURE=1 DEEPSEEK_API_KEY=<throwaway-key>./gradlew :janus-core:captureFixtures`
 (one-time, manual, network; the `captureFixtures` task is the ONLY way the
 capture-tagged test runs — `excludeTags 'capture'` in the default `test` task would
 otherwise filter it out conjunctively with any `--tests` filter).
- After a real capture: redact the 401 body's echoed key to `<redacted>`, update the
 manifest's expected file set / frame counts (core `FixtureManifestTest`,
 `GoldenStreamFixtureTest`, gateway `SseFixtureReplayTest` — three coordinated spots,
 see "Copy rule"), and copy the error + stream files into
 `janus-gateway/src/test/resources/fixtures/` verbatim.

## Files and equality levels

| File | Kind | Equality level |
|---|---|---|
| `chat.request.json` | Janus-generated outbound non-streaming request as-sent to an OpenAI-compatible upstream (system + user + tools + `tool_choice`; **no `stream` member** — the adapter contract) | **byte-golden** |
| `chat.request.stream.json` | Janus-generated streaming request (`stream: true` + `stream_options.include_usage: true`) | **byte-golden** |
| `chat.response.json` | Non-streaming response shape (choices, usage, finish_reason, id/object/created/model) | semantic |
| `chat.response.cached.json` | Non-streaming response with `prompt_tokens_details.cached_tokens` | semantic |
| `chat.stream.sse` | SSE stream shape: 9 data frames (8 chunk frames + terminal `data: [DONE]`) | semantic |
| `errors/deepseek.401.json` | 401 envelope shape | reference |
| `errors/deepseek.400.json` | 400 envelope shape (unknown model) | reference |
| `errors/deepseek.429.json` | 429 envelope shape | reference |

The request/response/stream fixtures use the `deepseek-v4-flash` model alias (the 
`config.toml` default).

## Error classification (adapter → gateway envelope)

| Fixture | Adapter classification (janus-provider, pinned by `DeepSeekAdapterTest`) | Gateway OpenAI envelope |
|---|---|---|
| 401 | `ProviderException TYPE_AUTH` | 401 `authentication_error` |
| 400 | `ProviderException TYPE_UPSTREAM_4XX` (statusCode 400) | 400 `api_error` |
| 429 | `ProviderException TYPE_RATE_LIMITED` | 429 `rate_limit_error` |

`ErrorFixtureTest` (janus-gateway) consumes the README-declared class per fixture and
asserts the envelope carries the upstream `error.message` text verbatim.

## Equality rules

- **Byte-golden (Janus-generated requests only):** `encodeRequest(canonical)` must
 reproduce the committed bytes exactly. The request fixtures are constructed to be
 round-trip idempotent: exactly one system message, no `system`-field +
 `SystemMessage` duplication, and no tool
 `description` (it would fold into extras and re-emerge top-level, breaking byte
 identity). Do not "fix" these fixtures into a failing shape.
- **Semantic (upstream-shaped):** decode → canonical → re-encode, then compare JSON
 trees order-insensitively (object key order ignored, explicit null ≡ absent — the
 codec's `@JsonInclude(NON_NULL)` omits nulls on re-encode; array order kept —
 `JsonSupport.treeEquals`). Every fixture field's value survives; nested unknowns
 re-emerge at the top level.
 `OpenAiUsage` drops unknown members (documented scope), so the usage payloads
 carry only the modeled `prompt_tokens`/`completion_tokens`/`total_tokens` plus the C07
 cache members (`prompt_tokens_details.cached_tokens`, `prompt_cache_hit_tokens`).
- **Reference (errors):** parse + vocabulary assertions only; classification logic
 lives in the provider loopback tests, never re-derived here.

## Copy rule (janus-gateway)

`janus-gateway/src/test/resources/fixtures/` holds verbatim copies of the three error
envelopes (`errors/`) and the stream capture (`stream/chat.stream.sse`) — the gateway
test classpath cannot see core *test* resources (core is exposed as `implementation`,
main-only). Copy verbatim on re-capture. A byte-parity guard across modules is not
automated (no shared test-resource classpath); the re-capture checklist above names the
three coordinated edit spots.
