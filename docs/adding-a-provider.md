# Adding a Provider

> Janus, the Roman god of gates, has two faces — one looking inward toward the client,
> one outward toward the providers. A **provider adapter** is the outward-looking face:
> it renders the canonical request into the provider's wire format, parses the response
> back, streams chunks, and maps provider-native errors into the stable `ProviderException`
> taxonomy. This guide documents the SPI contract as it exists in the code,
> the declarative-vs-code split, the streaming and error contracts, and a worked example.
> Cross-references: `AGENTS.md` (module boundaries), the README module table, and the
> golden fixture matrix (`janus-core/src/test/resources/fixtures/matrix/`).

## The SPI contract (`janus-provider`)

Implement `io.amscotti.janus.provider.ProviderAdapter` (one interface, five methods):

| Member | Contract |
|---|---|
| `String name` | Router key: stable, unique, lower-case (e.g. `"deepseek"`, `"xai"`). |
| `String baseUrl` | Normalized base URL — no trailing slash, a trailing `/v1` stripped (`BaseUrls.normalize`). Each adapter appends its own full versioned path (`/v1/chat/completions`, `/v1/messages`) — except the OpenAI-compatible full-endpoint opt-out: a base ending with `/chat/completions` is dispatched verbatim (versionless hosts such as Perplexity). |
| `ProviderAuth auth` | The upstream authentication scheme + credential (see below). |
| `ChatResponse complete(ChatRequest)` | Non-streaming upstream completion. |
| `Stream<StreamChunk> stream(ChatRequest)` | Streaming completion as a lazily-parsed stream of canonical chunks. The upstream HTTP request is sent eagerly (transport/status failures throw before any chunk); SSE frames decode as the stream is consumed. |

`ProviderAuth` is a record: `type` is one of `"bearer"`, `"x-api-key"` (Anthropic
authenticates with a literal `x-api-key` header, not `Authorization: Bearer`) or
`"none"`; `secret` holds the credential (null for `"none"`, non-null — blank allowed,
omitting the header — for the credentialed types; the compact constructor rejects an
inconsistent pair). **The SPI never reads
environment variables or configuration files** — the composition root (gateway/CLI)
resolves the secret and constructs the adapter with explicit arguments.

Errors surface as unchecked `ProviderException` with a stable `type` discriminator, a
nullable `statusCode` and a `retryable` flag (the router reads it for its retry policy):

| Type | Meaning | Retryable |
|---|---|---|
| `auth` | upstream rejected our credentials (401/403) | no |
| `rate_limited` | upstream rate limited us (429) | yes |
| `upstream_5xx` | upstream server error (5xx, incl. Anthropic 529 overloaded) | yes |
| `upstream_4xx` | any other upstream client-error status | no |
| `network` | transport failure (connect refused/reset, DNS,...) | yes |
| `timeout` | upstream did not answer within the request timeout | yes |
| `bad_upstream_payload` | upstream body/chunk failed codec parsing or SSE framing | no |

Client-side canonical validation failures surface as the codec's exception
(`OpenAiCodecException`/`AnthropicCodecException`) — caller bugs, not provider failures.

Additional contract points:

- **`ChatRequest.meta` passthrough:** gateway-internal context (request id, key id,
 attempt,...) — never serialize it, never read it for wire decisions, pass it through
 to the returned `ChatResponse` untouched. Meta has a **whitelisted-reader rule**:
 readers touch only their own documented entries — the router's session-affinity
 strategy reads the `janus.session-id` entry (the gateway's fold of the inbound
 `x-janus-session-id` header; routing input only — never logged, never forwarded
 upstream), and the Anthropic adapter reads the `anthropic-beta` entry (the gateway
 copies the inbound client header there so the adapter can forward it as the upstream
 `anthropic-beta` header — a per-request client opt-in). Nothing writes meta
 downstream of the gateway fold, and no other entry is ever read: meta values must
 never reach a log line or an upstream payload beyond those whitelisted.
- **Thread-safety/statelessness:** a single adapter instance serves concurrent requests
 once the router and gateway wire it. State that is not thread-safe (a client, a codec,
 a connection pool) must be held in effectively-final fields built at construction.
- **Stream close releases the connection:** the caller **must** close the returned
 `Stream` (try-with-resources or a finally block) — closing is the only way the
 upstream connection is released, even after the stream has been fully consumed (the
 gateway owns the lifecycle; pins the contract with a test).
- **Timeout semantics:** the JDK `HttpClient` request timeout covers header arrival
  only — on `complete` and `stream` alike. Once response headers have arrived, the
  non-streaming body (the success body and the non-2xx error body alike) is read under a
  wall-clock deadline (`bodyReadTimeout`, default 300 s) so a stalled upstream cannot pin
  a worker thread forever; the streaming body is read incrementally and is not
  wall-clock-bounded by the adapter (the gateway owns the streaming deadline). A long
  reasoning-model `complete` is never cut at the header-arrival deadline once headers
  have arrived; only the generous 300 s body-read deadline applies. The three deadlines
  are constructor parameters the composition root resolves from the per-backend
  timeout config — `[[janus.model-list]]` entry overrides merged over the
  `[janus.providers.<name>]` block over `[janus.timeouts]` (an absent key at every
  level reproduces the code default).
- **Streaming request `Accept` header:** the OpenAI-compatible streaming path sends
 `Accept: text/event-stream` (strict SSE-requesting upstreams such as DeepSeek want the
 media type advertised); the non-streaming path sends `application/json`. The Anthropic
 path always sends `application/json`.

## Declarative vs code split

Provider *definition* is declarative in TOML (`JanusConfig.ProviderEntry`); provider
*translation* is code. A `[janus.providers.<name>]` block keys on the adapter name and
carries three members:

```toml
[janus.providers.deepseek]
wire-format = "openai-compatible" # adapter-family hint
base-url = "https://api.deepseek.com"
api-key-env = "DEEPSEEK_API_KEY" # credentials from env only — never committed
```

- `wire-format` is `"openai-compatible"` or `"anthropic"` (rejected at binding time
 otherwise). It is a mapping *hint*: all actual field translation stays in the codecs
 (`OpenAiMessageCodec`, `AnthropicMessageCodec`). It also acts as the **construction
 fallback**: a model_list entry whose provider has no matching block falls
 back to the wire-format family's adapter (`ModelListFactory`). The backend keeps the
 **entry's provider name** for the `openai-compatible` family (the generic adapter is
 named by the entry's provider, so two distinct fallback providers under one alias
 stay distinguishable — e.g. `my-ollama` and `my-groq`), while the
 `anthropic` family adapter keeps its fixed name (one Anthropic upstream).
- `base-url`/`api-key-env` are per-provider *defaults* merged under matching
 `model_list` entries that omit them (the entry's own values win).
- A `model_list` entry names its provider and (optionally) overrides the base URL /
 api-key env; the router resolves the model alias → provider → adapter at request time.

## Streaming contract

Inbound upstream SSE is decoded by the package-private `SseFrameParser` (provider
module):

- `nextFrame` returns the joined `data:` payload of the next frame, or null at clean
 EOF. `nextEventFrame` additionally returns the frame's `event` name (last `event:`
 line wins per the SSE spec; `"message"` when absent) — the Anthropic adapter streams
 with it, because Anthropic SSE is event-typed.
- **Termination:** OpenAI-compatible upstreams end with a `data: [DONE]` sentinel;
 Anthropic upstreams end with `event: message_stop` (there is **no `[DONE]`** on the
 Anthropic wire). Both are terminal even at EOF without a trailing blank
 line (real upstreams close right after the sentinel). A stream
 that ends cleanly *without* its terminal marker is a truncation →
 `bad_upstream_payload`.
- **Data-less frames:** a frame with only `event: message_stop` and no
 `data:` line is still the terminal marker; a data-less frame naming any other
 non-default event is dispatched as that event (an empty-payload
 `event: error` still classifies as an error), while a data-less *default*
 frame (comment-only blank lines) is skipped. Unknown event types decode to
 null and are skipped.
- **`event: error` frames** classify by their envelope type (via `JsonProbe`) and throw
 as `ProviderException`.

## Error mapping tables (per wire format)

OpenAI-compatible (`OpenAiCompatibleAdapter`): 401/403 → `auth`, 429 →
`rate_limited`, 5xx → `upstream_5xx`, everything else → `upstream_4xx` (statusCode
carried). A non-2xx
response body — on the non-streaming and streaming paths alike — is probed (bounded read)
for the OpenAI error envelope's `error.type` to refine the status-based default, so a
streaming 403 carrying `authentication_error` surfaces as `auth`. Streaming error frames
(`data: {"error":...}`) classify by their `type`/`status` members
(`authentication_error`/`invalid_api_key` → `auth`, `server_error`/`api_error` →
`upstream_5xx`, rate-limit → `rate_limited`, else `upstream_4xx`). Non-JSON error bodies
fall back to the status mapping.

Anthropic (`AnthropicAdapter`): 401/403 → `auth`, 429 → `rate_limited`, 5xx **and 529
(overloaded — documented retryable)** → `upstream_5xx`, everything else →
`upstream_4xx`. The Anthropic envelope's `error.type` (`authentication_error`,
`rate_limit_error`, `permission_error`, `not_found_error`, `api_error`,
`overloaded_error`) refines the status-based default when present; non-JSON bodies fall
back to the status mapping; streaming `event: error` frames classify by envelope type.
The reference error envelopes live in `fixtures/anthropic/errors/`.

## Worked example: adding xAI (OpenAI-compatible)

xAI speaks the OpenAI Chat Completions wire format, so the adapter is a thin
named subclass of `OpenAiCompatibleAdapter` — same codec, same error mapping,
same streaming path, different base URL and credential env.

```java
package io.amscotti.janus.provider;

import java.time.Duration;

/** xAI (Grok) — an OpenAI-compatible upstream. */
public final class XaiAdapter extends OpenAiCompatibleAdapter {

 public static final String NAME = "xai";
 public static final String DEFAULT_BASE_URL = "https://api.x.ai";

 /** Composition-root form: resolved base URL + API key. */
 public XaiAdapter(String baseUrl, String apiKey) {
 super(NAME, baseUrl, apiKey);
 }

 /**
  * Timeout-aware composition-root form: the [janus.timeouts] threading leg —
  * public (the RouterFactory calls it with the resolved deadlines; a default
  * section reproduces the code constants). Positivity is validated by the
  * superclass form.
  */
 public XaiAdapter(
 String baseUrl,
 String apiKey,
 Duration connectTimeout,
 Duration requestTimeout,
 Duration bodyReadTimeout) {
 super(NAME, baseUrl, apiKey, connectTimeout, requestTimeout, bodyReadTimeout);
 }

 /** ServiceLoader discovery form — inert by shape: blank base, no credentials (the
 * blank base makes the superclass endpoint guard fail fast on a call). */
 public XaiAdapter {
 super(NAME);
 }

 /** Test/internal form with explicit connect + header timeouts (package-private,
 * the pre-short shape — the full public form above covers body-read). */
 XaiAdapter(String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout) {
 super(NAME, baseUrl, apiKey, connectTimeout, requestTimeout);
 }
}
```

Constructor pattern : every adapter exposes **three** forms — the plain
composition-root `(baseUrl, apiKey)`, a **public timeout-aware** `(baseUrl, apiKey,
connectTimeout, requestTimeout, bodyReadTimeout)` the `RouterFactory` calls with the
resolved `[janus.timeouts]` deadlines, and the package-private short test form. A
new adapter that skips the public timeout form silently drops operator timeout
config (the factory would fail to compile against it).

Wiring (the bounded touch-list — 5 spots, no core changes):

1. One `META-INF/services/io.amscotti.janus.provider.ProviderAdapter` line:
 `io.amscotti.janus.provider.XaiAdapter` — **and** extend the SPI drift guard
 `ProviderAdapterSpiTest` (it pins the exact adapter set in both directions; the
 services file and the test change together — explicit ServiceLoader registration).
2. One TOML block:

 ```toml
 [janus.providers.xai]
 wire-format = "openai-compatible"
 base-url = "https://api.x.ai"
 api-key-env = "XAI_API_KEY"
 ```

3. One `model_list` entry (e.g. `"grok-beta" → { provider = "xai",... }`).
4. Loopback tests with a `HttpServer` fake (the `DeepSeekAdapterTest`/
 `OpenAiCompatibleAdapterTest` pattern): request body capture, error-status mapping,
 stream frame parsing — no network.
5. Matrix replay: the golden matrix (`fixtures/matrix/`) already covers the
 OpenAI-compatible wire bytes in both directions; point the adapter tests at the
 committed fixtures instead of hand-writing shapes.

That's it: the gateway core, the codecs, the router, the faces and the build files are
untouched.

## A genuinely different wire format (sketch)

A provider that speaks neither wire format touches exactly these five things:

1. A new codec pair in `janus-core` (canonical ↔ the format's request/response/chunk
 shapes), following the mapper contract (snake_case or the format's naming,
 tolerant decode, `@JsonInclude(NON_NULL)`, declared-typed DTO lists).
2. An adapter implementing `ProviderAdapter` using that codec + the JDK `HttpClient`,
 with the format's auth scheme reported through `ProviderAuth`.
3. The `wire-format` family registration (the TOML hint + the construction fallback in
 `ModelListFactory`).
4. `META-INF/services` registration + the SPI drift guard update (same as above).
5. Golden fixtures + loopback tests (same as above).

## Testing + native-image guidance

- **No network in CI.** Fixtures are committed classpath resources
 (`fixtures/openai/`, `fixtures/anthropic/`, `fixtures/matrix/`); capture
 and regeneration are `@Tag("capture")` tests excluded from the default test task
 (`excludeTags 'capture'`, run only via `./gradlew :janus-core:captureFixtures` with
 env-gated keys).
- Loopback tests use JDK `HttpServer` fakes for adapter HTTP behavior; the golden
 matrix pins the codec bytes (byte-golden for Janus-generated legs, semantic for
 upstream-shaped legs, reference for errors).
- **ServiceLoader reachability:** provider registrations are explicit and checked (the
 SPI drift guard). New wire DTOs that Jackson must instantiate at runtime need
 explicit `reflect-config.json` registration (`janus-core`/`janus-gateway`
 native-image resource dirs) — no runtime reflection hacks; the
 `ReflectConfigDriftGuardTest` pins the core set.
- Adapters are stateless + thread-safe and use only the JDK `HttpClient` — nothing
 extra for native-image.

## Bounded touch-list (summary)

Adding a provider touches **only**: the adapter class, its codec if the wire format is
new, one `META-INF/services` line + the SPI drift guard, one TOML block, one
`model_list` entry, and the adapter's fixture/loopback tests. It must **not** touch the
gateway core, the pipeline, metrics, auth, or the build files.
