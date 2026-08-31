# Anthropic golden fixtures (Anthropic Messages wire shapes) — 

Representative Anthropic `/v1/messages` wire-shape fixtures — the missing counterpart to
's `openai/` corpus, consumed by the matrix (`matrix/oa`, `matrix/aa` upstream
legs are verbatim copies of `chat.response.json` / `chat.stream.sse`) and by the 
gate's SDK smoke. **Status: hand-authored from the documented Anthropic wire shapes —
NOT a verbatim real-upstream capture** (the C1 review finding; a real capture needs
a live Anthropic key, and the capture harness + Gradle task below are ready for it). CI
never touches the network (AGENTS.md).

## Provenance (honest)

- **C1 correction :** the ids, timestamps and usage in these files are
 synthetic stand-ins that follow the documented shapes; they do not claim to be
 captured bytes. The semantic replay tests pin *shape fidelity*, not
 *real-capture fidelity*.
- The request fixtures (`chat.request.json`, `chat.request.stream.json`) are
 **byte-golden codec output** — `AnthropicMessageCodec.encodeRequest` of the
 `matrix/canonical` tools/stream shapes — machine-emitted by
 `MatrixFixtureGeneratorTest` (offline, network-free).
- **D1 (fixed, test-first):** the corpus canonical carries `stream_options.include_usage`
 (OpenAI-idiomatic), but the Anthropic wire must NOT — real Anthropic rejects the field
 (400 `invalid_request_error`). stripped it in `AnthropicMessageCodec.encodeRequest`
 (documented non-idempotence) and regenerated `chat.request.stream.json` (and the
 matrix `oa`/`aa` stream requests); `GoldenMatrixTest` pins the absence so the fix
 cannot regress.
- Re-capture (when a real key is available):
 `JANUS_FIXTURE_CAPTURE=1 ANTHROPIC_API_KEY=<throwaway-key>./gradlew :janus-core:captureFixtures`
 (one-time, manual, network; the `captureFixtures` task is the ONLY way the
 capture-tagged test runs — `excludeTags 'capture'` in the default `test` task).
- After a real capture: redact the 401 body's echoed key to `<redacted>`, update the
 manifest's expected file set / frame counts (`AnthropicFixtureManifestTest`,
 `GoldenMatrixTest` — two coordinated spots), and the matrix cells re-copy the
 upstream legs (`MatrixFixtureGeneratorTest` does this on the next run).

## Files and equality levels

| File | Kind | Equality level |
|---|---|---|
| `chat.request.json` | Janus-generated outbound non-streaming request as-sent to Anthropic (system + user + assistant tool call + tool result + tools + `tool_choice {"type":"auto"}`; `max_tokens` 4096 — the codec default) | **byte-golden** |
| `chat.request.stream.json` | Janus-generated streaming request (`stream: true`, **no** `stream_options` — D1, `max_tokens` 4096) | **byte-golden** |
| `chat.response.json` | Non-streaming response shape (`id`/`type`/`role`/`model`/`content`/`stop_reason`/`usage`) for the corpus conversation ("What is the weather in Paris?", 14/12 tokens) | semantic |
| `chat.stream.sse` | SSE stream shape: 11 `event:`-named frames (message_start → content_block_start → empty text_delta → 5 × content_block_delta → content_block_stop → message_delta → message_stop), **no `[DONE]` anywhere**; 8 content-bearing frames. **C01 (real Anthropic usage shapes):** `message_start` carries `{"input_tokens":14,"output_tokens":0}` (the prompt count) and `message_delta` carries `{"output_tokens":12}` only — Anthropic's terminal event never repeats `input_tokens`. The C01 per-stream decoder merges the two into the canonical terminal usage (14/12/26). | semantic |
| `extended-thinking.stream.sse` | 12 `event:`-named frames exercising **unknown block/delta types** : `thinking_delta`/`signature_delta` (extended thinking) and a `server_tool_use` block start interleaved with text — the codec drops them instead of aborting the stream (Anthropic's versioning contract), and the stream uses the real usage shapes (message_start `input_tokens:25`, message_delta `output_tokens:12` → merged 25/12/37). | semantic |
| `errors/anthropic.401.json` | 401 envelope shape (`error.type: authentication_error`) | reference |
| `errors/anthropic.400.json` | 400 envelope shape (`error.type: invalid_request_error`, unknown model — the OpenAI harness precedent, C17) | reference |
| `errors/anthropic.429.json` | 429 envelope shape (`error.type: rate_limit_error`) | reference |

The fixtures use the `deepseek-v4-flash` model alias (the `config.toml` default) so the
matrix cells can copy them verbatim — model remapping is the router's concern, out of
scope for the codec corpus. The stream frames carry the corpus-wide synthetic chunk id
(`chatcmpl-2f4e…`) and the usage values (14/12/26) — the canonical stream chunks the
matrix shares, now in the real Anthropic shape : prompt tokens live on
`message_start` and `message_delta` carries the completion tokens only. The content
frames (including the empty `text_delta` the encoder emits for the OpenAI role chunk's
empty content) are the encoder's canonicalized shape; real Anthropic streams may omit
the empty `text_delta`, and the live SDK smoke is the real-bytes arbiter.

## Error classification (adapter → gateway envelope)

| Fixture | Adapter classification (janus-provider, pinned by `AnthropicAdapterTest`) | Gateway envelope |
|---|---|---|
| 400 | `ProviderException TYPE_UPSTREAM_4XX` (HTTP 400) | 400 `api_error` |
| 401 | `ProviderException TYPE_AUTH` (HTTP 401) | 401 `authentication_error` |
| 429 | `ProviderException TYPE_RATE_LIMITED` (HTTP 429) | 429 `rate_limit_error` |
| 5xx / 529 | `ProviderException TYPE_UPSTREAM_5XX` (retryable) | 5xx `upstream_5xx` |
| other 4xx | `ProviderException TYPE_UPSTREAM_4XX` | 4xx `upstream_4xx` |

The Anthropic envelope's `error.type` (`authentication_error`, `rate_limit_error`,
`permission_error`, `not_found_error`, `api_error`, `overloaded_error`) refines the
status-based default. Classification lives in the provider loopback tests, never
re-derived here.

## Equality rules

- **Byte-golden (Janus-generated request fixtures):** `encodeRequest(canonical)` must
 reproduce the committed bytes exactly. The canonical shapes are round-trip idempotent
 (exactly one system message, `max_tokens` carried explicitly, no
 `system`-field + `SystemMessage` duplication).
- **Semantic (upstream-shaped):** decode → canonical → re-encode, then compare JSON
 trees order-insensitively (`JsonSupport.treeEquals`; explicit null ≡ absent). The
 Anthropic-format fields (`object` from the wire `type`, `created` 0) are documented in
 the matrix README — values survive, format fields do not.
- **Reference (errors):** parse + vocabulary assertions only; classification lives in
 the provider loopback tests.

## Copy rule (matrix + gateway)

`matrix/oa/*` and `matrix/aa/*` upstream legs are verbatim copies of these files
(`MatrixFixtureGeneratorTest` performs the copy on regeneration — no hand-editing). A
real capture changes the bytes in both places at once; the manifest guards pin the file
sets on both sides.
