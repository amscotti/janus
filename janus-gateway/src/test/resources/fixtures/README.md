# Gateway fixture copies

Verbatim copies of the janus-core golden fixtures — the gateway test classpath cannot see
core *test* resources (core is exposed as `implementation`, main-only), so the capture
step copies these here:

- `errors/deepseek.401.json`, `errors/deepseek.400.json`, `errors/deepseek.429.json` —
  consumed by `ErrorFixtureTest` (real upstream error bodies → `ProviderException` →
  `ErrorMapper` OpenAI envelope).
- `errors/anthropic.400.json` — the Anthropic 400 (`invalid_request_error`, unknown
  model) — consumed by the `ErrorFixtureTest` upstream-4xx row.
- `stream/chat.stream.sse` — consumed by `SseFixtureReplayTest` (captured canonical
  chunks replayed through the real `/v1/chat/completions` SSE endpoint).

**Copy rule:** on re-capture, copy the three files from
`janus-core/src/test/resources/fixtures/openai/errors/`, the Anthropic 400 from
`janus-core/src/test/resources/fixtures/anthropic/errors/anthropic.400.json` and the
stream capture from `janus-core/src/test/resources/fixtures/openai/chat.stream.sse`
here verbatim. `GatewayFixtureParityTest` now enforces byte-for-byte equality
between these copies and the core originals, reading core's committed fixtures off the
repo tree (no core test-resource visibility needed) — a re-capture that changes core's
corpus fails the gateway suite instead of leaving stale copies green.
