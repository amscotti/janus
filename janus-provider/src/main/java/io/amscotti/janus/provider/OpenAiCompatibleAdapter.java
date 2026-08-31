package io.amscotti.janus.provider;

import io.amscotti.janus.core.codec.OpenAiCodecException;
import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * OpenAI-compatible chat-completions provider — an OpenAI-format passthrough generalized
 * from the original {@link DeepSeekAdapter} to any OpenAI-style upstream (DeepSeek, OpenRouter,
 * xAI, Ollama — the reference implementation "second OpenAI-compatible provider is essentially free"
 * argument: different base URL + credential env only). The name is a constructor
 * parameter, so one class serves every OpenAI-format upstream; {@link DeepSeekAdapter}
 * is the thin named subclass that keeps the call sites working unchanged.
 *
 * <p>Translates canonical model types to/from the upstream's OpenAI-compatible wire
 * format purely through {@link OpenAiMessageCodec}, performs the upstream HTTP call
 * with the JDK {@link HttpClient} (zero new dependencies, native-image-clean) and exposes
 * typed {@link ProviderException}s for the router and gateway.
 *
 * <p><b>Endpoint.</b> {@code POST {baseUrl}/v1/chat/completions} with the reference base-URL
 * normalization rule (see {@link BaseUrls}): the caller-supplied base is the full prefix
 * and a trailing {@code /v1} is stripped, so {@code "https://api.deepseek.com"} and
 * {@code "https://api.deepseek.com/v1"} produce the same endpoint. <b>Full-endpoint
 * opt-out:</b> a base that already ends with {@code /chat/completions} (case-insensitive)
 * is the operator pinning the exact upstream endpoint — it is used verbatim, for
 * versionless OpenAI-compatible hosts (e.g. Perplexity's {@code
 * https://api.perplexity.ai/chat/completions}) that the base-plus-versioned-path rule
 * cannot express. Headers: {@code
 * Authorization: Bearer <apiKey>}, {@code Content-Type: application/json}, {@code
 * Accept: text/event-stream} on the streaming path and {@code Accept: application/json}
 * on the non-streaming path (OpenAI accepts both; strict SSE-requesting OpenAI-compatible
 * upstreams — DeepSeek's documented curl shape among them — want the event-stream media
 * type advertised for streaming).
 *
 * <p><b>Wire decisions.</b> The adapter owns the upstream streaming decision regardless of
 * the canonical {@code stream} flag: {@link #complete(ChatRequest)} always sends a
 * {@code stream=false}-shaped body (no {@code stream} member) and {@link
 * #stream(ChatRequest)} always sends {@code "stream":true}, preserving
 * {@code stream_options} on the streaming path.
 *
 * <p><b>Errors.</b> Non-2xx statuses map 401/403→{@code auth} (403 parity with the
 * Anthropic adapter — OpenAI-format upstreams return 403 for bad credentials),
 * 429→{@code rate_limited}, 5xx→{@code upstream_5xx}, everything else→{@code
 * upstream_4xx} (statusCode carried);
 * the non-2xx body is probed (bounded read) for the OpenAI error envelope's
 * {@code error.type} on the non-streaming and streaming paths alike to refine the
 * status-based default.
 * Streaming error frames ({@code data: {"error":...}}) classify by their {@code type} /
 * {@code status} members (via the minimal {@link JsonProbe} — no Jackson dependency).
 * Transport failures map to {@code network}, request timeouts to {@code timeout}, and
 * upstream payloads that fail codec parsing or SSE framing to {@code
 * bad_upstream_payload} (with the codec/parse exception as cause) — as is a stream that
 * ends cleanly without the {@code [DONE]} sentinel (a truncated stream, parity with the
 * Anthropic adapter's {@code message_stop} rule; see docs/adding-a-provider.md).
 * Client-side canonical
 * validation failures surface as {@link OpenAiCodecException} — caller bugs, not provider
 * failures.
 *
 * <p><b>Construction.</b> The composition root (gateway/CLI, /) constructs with
 * explicit arguments: {@link #OpenAiCompatibleAdapter(String, String, String)} — name,
 * resolved base URL, API key — or, when {@code [janus.timeouts]} is configured, the
 * timeout-aware form {@link #OpenAiCompatibleAdapter(String, String, String, Duration,
 * Duration, Duration)}. The no-arg constructor exists only for ServiceLoader
 * discovery (AGENTS.md: explicit, checked registrations) and yields an instance with
 * {@link #NAME}, a <b>blank base URL</b> and no credentials — it must not be used for
 * calls: the adapter omits the {@code Authorization} header when the secret is blank,
 * and {@link #endpoint} fails fast with an {@link IllegalStateException} if the blank
 * base is ever dispatched (a discovery instance is inert by shape, never armed).
 *
 * <p><b>Timeouts.</b> The JDK {@code HttpClient} request timeout covers <b>header
 * arrival only</b> — on {@link #complete(ChatRequest)} and {@link #stream(ChatRequest)}
 * alike. Once response headers have arrived, the non-streaming body (the success body
 * and the non-2xx error body alike) is read under {@code bodyReadTimeout} (default
 * {@value #DEFAULT_BODY_READ_TIMEOUT} s) so a stalled upstream cannot pin a worker
 * thread forever; the streaming body is read incrementally and is not wall-clock-bounded
 * by the adapter (the gateway owns the streaming deadline).
 *
 * <p>Thread-safe: one {@link HttpClient} and one {@link OpenAiMessageCodec} (both
 * thread-safe) built at construction; a single instance serves concurrent requests.
 */
public class OpenAiCompatibleAdapter implements ProviderAdapter {

    /** ServiceLoader discovery name — the generic OpenAI-format provider key. */
    public static final String NAME = "openai-compatible";

    /**
     * Versioned OpenAI chat path. Base URLs are normalized to strip a trailing
     * {@code /v1}, so both {@code https://api.openai.com} and
     * {@code https://api.openai.com/v1} resolve to the same endpoint. DeepSeek accepts
     * either layout; OpenAI / xAI / OpenRouter require the versioned path.
     */
    public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    /**
     * Full-endpoint opt-out suffix: a base ending with the <em>unversioned</em>
     * {@code /chat/completions} (which also covers the versioned {@code
     * /v1/chat/completions}) names the exact upstream endpoint and is dispatched
     * verbatim — the versioned path is never appended on top. For versionless
     * OpenAI-compatible hosts (Perplexity) that the base-plus-versioned-path
     * rule cannot express.
     */
    private static final String FULL_ENDPOINT_SUFFIX = "/chat/completions";

    private static final String DONE_SENTINEL = "[DONE]";
    /** Connect deadline (docs/architecture.md timeouts table) — pinned by TimeoutContractTest. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // Header arrival only — a long reasoning completion is not cut while headers are
    // still pending (the body read is bounded separately by bodyReadTimeout).
    /** Header-arrival deadline (docs/architecture.md timeouts table) — pinned by TimeoutContractTest. */
    public static final Duration HEADER_ARRIVAL_TIMEOUT = Duration.ofSeconds(60);
    /** Default non-stream body-read deadline after headers arrive (stall → TYPE_TIMEOUT). */
    public static final Duration DEFAULT_BODY_READ_TIMEOUT = Duration.ofSeconds(300);

    private final String name;
    private final String baseUrl;
    private final ProviderAuth auth;
    private final OpenAiMessageCodec codec;
    private final HttpClient httpClient;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration bodyReadTimeout;

    /** Composition-root form: adapter name + resolved base URL + API key. */
    public OpenAiCompatibleAdapter(String name, String baseUrl, String apiKey) {
        this(name, baseUrl, apiKey, CONNECT_TIMEOUT, HEADER_ARRIVAL_TIMEOUT, DEFAULT_BODY_READ_TIMEOUT);
    }

    /**
     * ServiceLoader discovery form — no credentials; must not be used for calls. The
     * generic form has no default base URL, so the instance is inert (blank base, blank
     * secret → no {@code Authorization} header, and the {@link #endpoint} guard fails
     * fast on a call); the constructor body does not delegate to the validating form
     * because it rejects a blank base URL  while ServiceLoader must still
     * instantiate the discovery instance. {@link DeepSeekAdapter} shares this inert shape
     * via the name-parameterized discovery constructor.
     */
    public OpenAiCompatibleAdapter() {
        this(NAME);
    }

    /**
     * Discovery-form shared state (name-parameterized so the {@link DeepSeekAdapter}
     * discovery instance is inert too): blank base + blank secret. Must not be used for
     * calls — {@link #endpoint} throws an {@link IllegalStateException} on the blank
     * base.
     */
    protected OpenAiCompatibleAdapter(String name) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = "";
        this.auth = new ProviderAuth(ProviderAuth.TYPE_BEARER, "");
        this.codec = OpenAiMessageCodec.create();
        this.connectTimeout = CONNECT_TIMEOUT;
        this.requestTimeout = HEADER_ARRIVAL_TIMEOUT;
        this.bodyReadTimeout = DEFAULT_BODY_READ_TIMEOUT;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** Test/internal form with explicit connect + header-arrival timeouts (default body deadline). */
    protected OpenAiCompatibleAdapter(
            String name, String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout) {
        this(name, baseUrl, apiKey, connectTimeout, requestTimeout, DEFAULT_BODY_READ_TIMEOUT);
    }

    /**
     * Timeout-aware composition-root form: explicit connect, header-arrival and
     * body-read deadlines — the {@code [janus.timeouts]} threading leg the {@code
     * RouterFactory} calls (a present-but-default section reproduces the three
     * constants exactly). Public since made the deadlines operator-tunable; the
     * same form remains the test/internal explicit-timeout constructor. Each
     * {@link Duration} must be positive (fail-fast at construction, the validating
     * constructor below).
     */
    public OpenAiCompatibleAdapter(
            String name,
            String baseUrl,
            String apiKey,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration bodyReadTimeout) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must be non-blank");
        }
        Objects.requireNonNull(apiKey, "apiKey");
        this.name = name;
        this.baseUrl = BaseUrls.normalize(baseUrl);
        this.auth = new ProviderAuth(ProviderAuth.TYPE_BEARER, apiKey);
        this.codec = OpenAiMessageCodec.create();
        Duration connect = Objects.requireNonNull(connectTimeout, "connectTimeout");
        if (connect.isZero() || connect.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be > 0: " + connect);
        }
        this.connectTimeout = connect;
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be > 0: " + requestTimeout);
        }
        this.bodyReadTimeout = Objects.requireNonNull(bodyReadTimeout, "bodyReadTimeout");
        if (bodyReadTimeout.isZero() || bodyReadTimeout.isNegative()) {
            throw new IllegalArgumentException("bodyReadTimeout must be > 0: " + bodyReadTimeout);
        }
        this.httpClient = HttpClient.newBuilder().connectTimeout(connect).build();
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final String baseUrl() {
        return baseUrl;
    }

    @Override
    public final ProviderAuth auth() {
        return auth;
    }

    // ------------------------------------------------- timeout observability
    //
    // Read accessors for the effective deadlines — no behavior; they exist so the
    // composition-root tests (janus-gateway RouterFactoryTest) can pin that the
    // configured [janus.timeouts] values reached the timeout-aware constructor on
    // every adapter leg (a leg that silently reverts to the default-timeout
    // constructor must fail the build). DeepSeekAdapter inherits them with the
    // fields they read.

    /** Effective connect deadline this adapter was constructed with. */
    public final Duration connectTimeout() {
        return connectTimeout;
    }

    /** Effective header-arrival deadline — the JDK client's per-request timeout. */
    public final Duration headerTimeout() {
        return requestTimeout;
    }

    /** Effective non-stream body-read deadline (bounded read after headers arrive). */
    public final Duration bodyReadTimeout() {
        return bodyReadTimeout;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        // ofInputStream so the request timeout bounds header arrival only — a long
        // reasoning-model completion must not be cut while headers are still pending.
        // After headers, the body is read under bodyReadTimeout (default 300s).
        HttpResponse<InputStream> response = send(request, false, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (!HttpSupport.isSuccess(status)) {
            // The error body is read only to refine the status mapping via the OpenAI
            // error envelope's error.type; the body text itself never reaches an exception
            // message. The read is bounded (bodyReadTimeout) and capped (readErrorBody),
            // and a stalled/failed read must not discard the head-derived status +
            // Retry-After.
            throw errorStatusException(
                    response, bodyReadTimeout, status, "upstream returned HTTP " + status + " for chat completion");
        }
        String body;
        try {
            body = HttpSupport.readBody(response.body(), bodyReadTimeout);
        } finally {
            // Success path: reading to EOF returns an HTTP/1.1 keep-alive connection to
            // the pool in practice, but the close makes the release contract explicit —
            // a future decode that short-circuits must not pin the connection until GC.
            HttpSupport.closeQuietly(response.body());
        }
        ChatResponse decoded;
        try {
            decoded = codec.decodeResponse(body);
        } catch (OpenAiCodecException e) {
            throw new ProviderException(
                    ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                    "upstream returned an invalid chat completion body",
                    null,
                    e);
        }
        // meta passes through untouched (gateway-internal; never serialized, never read).
        return new ChatResponse(
                decoded.id(),
                decoded.object(),
                decoded.created(),
                decoded.model(),
                decoded.choices(),
                decoded.usage(),
                decoded.stopReason(),
                decoded.hostedToolCalls(),
                decoded.extras(),
                request.meta());
    }

    @Override
    public Stream<StreamChunk> stream(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        HttpResponse<InputStream> response = send(request, true, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (!HttpSupport.isSuccess(status)) {
            // The streaming non-2xx body is probed for the envelope's error.type just
            // like complete does (parity + LiteLLM's type-first mapping): a streaming
            // 403 carrying {"error":{"type":"authentication_error"}} refines to auth,
            // a streaming 500 carrying rate_limit_error to rate_limited. The connection
            // is released either way, and a stalled/failed read preserves the head-derived
            // status + Retry-After.
            throw errorStatusException(
                    response,
                    bodyReadTimeout,
                    status,
                    "upstream returned HTTP " + status + " for streaming chat completion");
        }
        Iterator<StreamChunk> iterator = chunkIterator(response);
        Spliterator<StreamChunk> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
        // The caller (gateway ) must close the stream; onClose releases the connection.
        return StreamSupport.stream(spliterator, false).onClose(() -> HttpSupport.closeQuietly(response.body()));
    }

    // ------------------------------------------------------------------ HTTP

    private <T> HttpResponse<T> send(ChatRequest request, boolean stream, HttpResponse.BodyHandler<T> handler) {
        HttpRequest httpRequest = httpRequest(request, stream);
        try {
            return httpClient.send(httpRequest, handler);
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderException.TYPE_TIMEOUT, "upstream request timed out", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // A locally interrupted wait is not an upstream fault — a
            // retryable network type would make the router burn a retry slot (the
            // interrupt flag is sticky, so the immediate retry re-throws immediately).
            // Classify as the terminal non-retryable catch-all; the client-visible
            // mapping (502 / api_error) is unchanged from the network type.
            throw new ProviderException(
                    ProviderException.TYPE_UPSTREAM_4XX, "interrupted while waiting for upstream", null, e);
        } catch (IOException e) {
            throw new ProviderException(
                    ProviderException.TYPE_NETWORK, "upstream request failed: " + e.getMessage(), null, e);
        }
    }

    private HttpRequest httpRequest(ChatRequest request, boolean stream) {
        String body = codec.encodeRequest(withStream(request, stream));
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint())
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                // The streaming path advertises SSE so strict OpenAI-compatible upstreams
                // (DeepSeek's documented shape among them) treat it as a stream; the
                // non-streaming path keeps application/json.
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth.secret() != null && !auth.secret().isBlank()) {
            builder.header("Authorization", "Bearer " + auth.secret());
        }
        return builder.build();
    }

    private URI endpoint() {
        if (baseUrl.isBlank()) {
            // Discovery instance misused for a call (no base URL) — fail fast with a
            // clear typed error instead of a raw IllegalArgumentException from a relative
            // URI (SPI contract: caller bugs are loud, not cryptic).
            throw new IllegalStateException(
                    "this provider adapter (" + name + ") has no base URL and must not be used for calls; "
                            + "construct it via the composition root with a base URL");
        }
        // Full-endpoint opt-out: a base that already ends with the chat-completions
        // path is the operator pinning the exact upstream endpoint (versionless
        // OpenAI-compatible hosts — Perplexity serves /chat/completions with no /v1,
        // and that shape cannot be expressed as "base + versioned path"). Used
        // verbatim, case-insensitively, so the appended-path rule never doubles it.
        if (regionMatchesIgnoreCase(baseUrl, FULL_ENDPOINT_SUFFIX)) {
            return URI.create(baseUrl);
        }
        return URI.create(baseUrl + CHAT_COMPLETIONS_PATH);
    }

    private static boolean regionMatchesIgnoreCase(String value, String suffix) {
        return value.length() >= suffix.length()
                && value.regionMatches(true, value.length() - suffix.length(), suffix, 0, suffix.length());
    }

    /**
     * Copy with the upstream wire decision forced: {@code stream=false} drops the
     * meaningless {@code stream_options}; {@code stream=true} keeps them. The adapter owns
     * the streaming decision regardless of the canonical {@code stream} flag; {@code meta}
     * passes through untouched.
     */
    private static ChatRequest withStream(ChatRequest request, boolean stream) {
        return new ChatRequest(
                request.model(),
                request.messages(),
                request.system(),
                request.tools(),
                request.toolChoice(),
                request.temperature(),
                request.topP(),
                request.topK(),
                request.maxTokens(),
                request.stop(),
                request.seed(),
                request.n(),
                request.frequencyPenalty(),
                request.presencePenalty(),
                request.logitBias(),
                request.responseFormat(),
                stream,
                stream ? request.streamOptions() : null,
                request.reasoning(),
                request.cacheControl(),
                request.hostedTools(),
                request.extras(),
                request.meta());
    }

    // ------------------------------------------------------------- streaming

    private Iterator<StreamChunk> chunkIterator(HttpResponse<InputStream> response) {
        SseFrameParser parser = new SseFrameParser(response.body());
        return new Iterator<>() {
            private StreamChunk next;
            private boolean exhausted;
            private boolean terminated;

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                if (exhausted) {
                    return false;
                }
                next = advance(parser);
                if (next == null) {
                    exhausted = true;
                }
                return next != null;
            }

            @Override
            public StreamChunk next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                StreamChunk chunk = next;
                next = null;
                return chunk;
            }

            /**
             * One data frame → a canonical chunk (or the terminal marker). Tracks the
             * {@code [DONE]} termination: a clean EOF <em>without</em> it is a truncated
             * stream (parity with the Anthropic adapter's {@code message_stop} rule and
             * the docs/adding-a-provider.md streaming contract — a cleanly closed but
             * sentinel-less stream must surface as {@code bad_upstream_payload}, never
             * as a complete response).
             */
            private StreamChunk advance(SseFrameParser parser) {
                try {
                    String frame;
                    while ((frame = parser.nextFrame()) != null) {
                        if (frame.isEmpty()) {
                            continue; // empty data frames carry nothing
                        }
                        if (DONE_SENTINEL.equals(frame.strip())) {
                            terminated = true;
                            return null; // terminal sentinel — stream ends cleanly (n3: tolerate trailing space)
                        }
                        ErrorFrame error = parseErrorFrame(frame);
                        if (error != null) {
                            throw errorFrameException(error);
                        }
                        return codec.decodeChunk(frame);
                    }
                    if (!terminated) {
                        throw new ProviderException(
                                ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                                "upstream stream ended without the [DONE] sentinel (truncated stream)",
                                null,
                                null);
                    }
                    return null;
                } catch (OpenAiCodecException e) {
                    throw new ProviderException(
                            ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                            "upstream sent an invalid streaming chunk",
                            null,
                            e);
                } catch (SseParseException e) {
                    throw new ProviderException(
                            ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                            "upstream sent a truncated SSE frame",
                            null,
                            e);
                } catch (IOException e) {
                    throw new ProviderException(
                            ProviderException.TYPE_NETWORK, "streaming connection failed: " + e.getMessage(), null, e);
                }
            }
        };
    }

    // ------------------------------------------------------- error mapping

    static ProviderException statusException(int status, String message) {
        return statusException(status, message, null);
    }

    static ProviderException statusException(int status, String message, Long retryAfterSeconds) {
        String type =
                switch (status) {
                    // 401/403 both mean the upstream rejected our credentials/permissions
                    // (OpenAI-format upstreams — OpenAI/OpenRouter/xAI — return 403 for
                    // credential and permission failures; parity with the Anthropic
                    // adapter's 401/403 → auth mapping).
                    case 401, 403 -> ProviderException.TYPE_AUTH;
                    case 429 -> ProviderException.TYPE_RATE_LIMITED;
                    default ->
                        status >= 500 ? ProviderException.TYPE_UPSTREAM_5XX : ProviderException.TYPE_UPSTREAM_4XX;
                };
        return new ProviderException(type, message, status, null, retryAfterSeconds);
    }

    /**
     * Non-2xx classification: status-based default, refined by the OpenAI error
     * envelope's {@code error.type} when the body carries one (parity with the Anthropic
     * adapter); non-JSON or envelope-less bodies fall back to the status mapping. The
     * body is probed structurally — its text is never placed in the exception message.
     * The upstream {@code Retry-After} (429s) is carried on the exception so the
     * gateway's passthrough can forward the provider's backoff window.
     */
    private static ProviderException statusException(int status, String body, String message, Long retryAfterSeconds) {
        String envelopeType = errorEnvelopeType(body);
        if (envelopeType != null) {
            String mapped = envelopeTypeToProviderType(envelopeType);
            if (mapped != null) {
                return new ProviderException(
                        mapped,
                        "upstream error envelope: " + envelopeType + " (HTTP " + status + ")",
                        status,
                        null,
                        retryAfterSeconds);
            }
        }
        return statusException(status, message, retryAfterSeconds);
    }

    /**
     * Non-2xx classification with an error-body probe that may fail or be capped: the
     * status mapping and the upstream {@code Retry-After} are derived from the response
     * <b>head</b>, which is already available before the body is read — a stalled or
     * mid-read-failed error body must not discard them (a 429 with a stalled
     * body stays rate_limited with the provider's backoff window, a 401 stays auth and
     * non-retryable — the router must not burn a retry slot on an auth failure). The
     * probe is best-effort: on success it may refine the status default via the
     * envelope's {@code error.type}; on any read failure the status mapping is raised
     * unchanged. The connection is released either way.
     */
    private static ProviderException errorStatusException(
            HttpResponse<InputStream> response, Duration bodyReadTimeout, int status, String message) {
        Long retryAfter = HttpSupport.retryAfterSeconds(response);
        String errorBody;
        try {
            errorBody = HttpSupport.readErrorBody(response.body(), bodyReadTimeout);
        } catch (ProviderException readFailure) {
            return statusException(status, message, retryAfter);
        } finally {
            HttpSupport.closeQuietly(response.body());
        }
        return statusException(status, errorBody, message, retryAfter);
    }

    private static ProviderException errorFrameException(ErrorFrame frame) {
        // Status wins only for a real non-2xx status; a 2xx status (or no status) falls
        // through to the type-based classification so a hypothetical 200-status
        // rate-limit frame is still mapped to rate_limited, not upstream_4xx.
        if (frame.status != null && !HttpSupport.isSuccess(frame.status)) {
            return statusException(frame.status, "upstream streaming error frame: HTTP " + frame.status);
        }
        String type = frame.type;
        String mapped = envelopeTypeToProviderType(type == null ? "" : type);
        String resolved = mapped == null ? ProviderException.TYPE_UPSTREAM_4XX : mapped;
        return new ProviderException(
                resolved, "upstream streaming error frame" + (type == null ? "" : ": " + type), frame.status, null);
    }

    /** OpenAI-compatible envelope {@code error.type} names → provider type; null when
     * unmapped (the caller decides the fallback — status default or {@code upstream_4xx}). */
    private static String envelopeTypeToProviderType(String envelopeType) {
        return switch (envelopeType) {
            case "rate_limit_error", "insufficient_quota", "rate_limit_exceeded" -> ProviderException.TYPE_RATE_LIMITED;
            case "authentication_error", "invalid_api_key", "permission_error" -> ProviderException.TYPE_AUTH;
            case "server_error", "api_error", "overloaded_error" -> ProviderException.TYPE_UPSTREAM_5XX;
            default -> null;
        };
    }

    /**
     * OpenAI error-envelope probe: {@code {"error":{"type":...,"message":...}}} — the
     * nested {@code error.type} name, or null when the body is not an error envelope
     * (via the minimal {@link JsonProbe}; no Jackson dependency).
     */
    private static String errorEnvelopeType(String body) {
        String errorObject = JsonProbe.jsonMemberValue(body, "error");
        if (errorObject == null || "null".equals(errorObject)) {
            return null;
        }
        return JsonProbe.jsonStringMember(errorObject, "type");
    }

    private record ErrorFrame(String type, Integer status) {}

    // ------------------------------------------------- error frame probing
    //
    // OpenAI-compatible upstreams signal streaming errors with
    // `data: {"error": {"type":..., "status":...}}` frames; the minimal JsonProbe
    // recognizes them without pulling Jackson into janus-provider (AGENTS.md).

    private static ErrorFrame parseErrorFrame(String data) {
        String errorObject = JsonProbe.jsonMemberValue(data, "error");
        if (errorObject == null || "null".equals(errorObject)) {
            // "error": null is a regular chunk, not an error frame (n1 hardening)
            return null;
        }
        if (JsonProbe.jsonMemberValue(data, "choices") != null) {
            // A real chunk always carries a choices member; a real error frame never
            // does: a forward-incompatible upstream that adds a benign top-level
            // "error" member to regular chunks must decode as a chunk, not abort the
            // stream as bad_upstream_payload.
            return null;
        }
        String type = JsonProbe.jsonStringMember(errorObject, "type");
        Integer status = JsonProbe.jsonNumberMember(errorObject, "status");
        return new ErrorFrame(type, status);
    }
}
