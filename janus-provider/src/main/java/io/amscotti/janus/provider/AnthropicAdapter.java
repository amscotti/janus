package io.amscotti.janus.provider;

import io.amscotti.janus.core.codec.AnthropicCodecException;
import io.amscotti.janus.core.codec.AnthropicMessageCodec;
import io.amscotti.janus.core.codec.AnthropicStreamDecoder;
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
 * Anthropic Messages provider — the native Anthropic
 * upstream (Claude). Translates canonical model types to/from the Anthropic wire
 * format purely through {@link AnthropicMessageCodec}, performs the upstream
 * HTTP call with the JDK {@link HttpClient} (zero new dependencies, native-image-clean)
 * and exposes typed {@link ProviderException}s for the router and gateway.
 *
 * {@code anthropic.rs} (references only).
 *
 * <p><b>Endpoint.</b> {@code POST {baseUrl}/v1/messages} with the reference base-URL
 * normalization rule (see {@link BaseUrls}): a trailing {@code /v1} is stripped from the
 * caller-supplied base and the messages path is appended, so {@code
 * "https://api.anthropic.com"} and {@code "https://api.anthropic.com/v1"} produce the
 * same endpoint. Headers: {@code x-api-key: <apiKey>} (omitted when blank — Anthropic
 * authenticates with {@code x-api-key}, not {@code Authorization: Bearer}; the SPI
 * reports {@link ProviderAuth#TYPE_X_API_KEY}), {@code anthropic-version} (default
 * {@value #DEFAULT_ANTHROPIC_VERSION}, overridable), {@code Content-Type: application/json},
 * {@code Accept: application/json}.
 *
 * <p><b>Wire decisions.</b> The adapter owns the upstream streaming decision regardless of
 * the canonical {@code stream} flag: {@link #complete(ChatRequest)} always sends a
 * {@code stream=false}-shaped body (no {@code stream} member) and {@link
 * #stream(ChatRequest)} always sends {@code "stream":true}. Anthropic has no
 * {@code stream_options}; the codec drops the canonical flag on Anthropic-outbound
 * encode and {@link #withStream} keeps the copy streamOptions-free as a
 * belt-and-suspenders wire decision. {@code max_tokens} is defaulted to 4096 by the
 * codec (Anthropic requires it).
 *
 * <p><b>Streaming in.</b> Anthropic SSE is event-typed and has no {@code data: [DONE]}:
 * the stream terminates on the {@code event: message_stop} frame (tolerating EOF
 * immediately after it without a trailing blank line — real upstreams close right
 * after). No-op events ({@code content_block_stop}, {@code ping}, text-block
 * {@code content_block_start}, unknown event types) decode to null and are skipped.
 * A per-stream stateful decoder merges the prompt-side usage from
 * {@code message_start} with the completion side from {@code message_delta}, so the
 * canonical terminal chunk carries the full token count (the stateless decode would
 * report {@code promptTokens=0}). A
 * stream that ends cleanly <em>without</em> {@code message_stop} is a truncation →
 * {@code bad_upstream_payload} (mirroring the OpenAI adapter's truncated-frame handling).
 *
 * <p><b>Errors.</b> Non-2xx statuses map 401/403→{@code auth}, 429→{@code
 * rate_limited}, 5xx and 529 (overloaded — documented retryable)→{@code upstream_5xx},
 * everything else→{@code upstream_4xx} (statusCode carried). The Anthropic error
 * envelope's {@code error.type} ({@code authentication_error}, {@code rate_limit_error},
 * {@code permission_error}, {@code not_found_error}, {@code api_error}, {@code
 * overloaded_error}) refines the status-based default when present (via the minimal
 * {@link JsonProbe} — no Jackson dependency); non-JSON error bodies fall back to the
 * status mapping. Streaming {@code event: error} frames classify by their envelope type.
 * Transport failures map to {@code network}, request timeouts to {@code timeout}, and
 * upstream payloads that fail codec parsing or SSE framing to {@code
 * bad_upstream_payload} (with the codec/parse exception as cause).
 *
 * <p><b>Construction.</b> The composition root (gateway/CLI, /) constructs with
 * explicit arguments: {@link #AnthropicAdapter(String, String)} — or, when
 * {@code [janus.timeouts]} is configured, the timeout-aware {@link
 * #AnthropicAdapter(String, String, Duration, Duration, Duration)}. The no-arg
 * constructor exists only for ServiceLoader discovery (AGENTS.md: explicit, checked
 * registrations) and yields an inert instance — <b>blank base URL</b>, no credentials —
 * that must not
 * be used for calls: the adapter omits the {@code x-api-key} header when the secret is
 * blank, and {@link #endpoint} fails fast with an {@link IllegalStateException} if the
 * blank base is ever dispatched.
 *
 * <p><b>Timeouts.</b> The JDK {@code HttpClient} request timeout covers <b>header
 * arrival only</b> — on {@link #complete(ChatRequest)} and {@link #stream(ChatRequest)}
 * alike. Once response headers have arrived, the non-streaming body (the success body
 * and the non-2xx error body alike) is read under {@code bodyReadTimeout} (default
 * {@value #DEFAULT_BODY_READ_TIMEOUT} s) so a stalled upstream cannot pin a worker
 * thread forever; the streaming body is read incrementally and is not wall-clock-bounded
 * by the adapter (the gateway owns the streaming deadline). The non-streaming
 * {@code complete} therefore never cuts a long Claude completion (extended thinking)
 * at the short header-arrival deadline; only the generous 300 s {@code bodyReadTimeout}
 * bounds the body read.
 *
 * <p>Thread-safe: one {@link HttpClient} and one {@link AnthropicMessageCodec} (both
 * thread-safe) built at construction; a single instance serves concurrent requests.
 */
public final class AnthropicAdapter implements ProviderAdapter {

    public static final String NAME = "anthropic";
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";
    public static final String MESSAGES_PATH = "/v1/messages";

    private static final String MESSAGE_STOP = "message_stop";
    /** Connect deadline (docs/architecture.md timeouts table) — pinned by TimeoutContractTest. */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** Header-arrival deadline (docs/architecture.md timeouts table) — pinned by TimeoutContractTest. */
    public static final Duration HEADER_ARRIVAL_TIMEOUT = Duration.ofSeconds(60);
    /** Default non-stream body-read deadline after headers (same as OpenAI-compatible). */
    public static final Duration DEFAULT_BODY_READ_TIMEOUT = Duration.ofSeconds(300);

    private final String baseUrl;
    private final String anthropicVersion;
    private final ProviderAuth auth;
    private final AnthropicMessageCodec codec;
    private final HttpClient httpClient;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final Duration bodyReadTimeout;

    /** Composition-root form: resolved base URL + API key. */
    public AnthropicAdapter(String baseUrl, String apiKey) {
        this(
                baseUrl,
                apiKey,
                DEFAULT_ANTHROPIC_VERSION,
                CONNECT_TIMEOUT,
                HEADER_ARRIVAL_TIMEOUT,
                DEFAULT_BODY_READ_TIMEOUT);
    }

    /**
     * Timeout-aware composition-root form: explicit connect, header-arrival and
     * body-read deadlines — the {@code [janus.timeouts]} threading leg the {@code
     * RouterFactory} calls (a present-but-default section reproduces the three
     * constants exactly), with the default {@code anthropic-version}. Public since
     * made the deadlines operator-tunable; positivity is validated by the
     * internal full form it delegates to.
     */
    public AnthropicAdapter(
            String baseUrl, String apiKey, Duration connectTimeout, Duration requestTimeout, Duration bodyReadTimeout) {
        this(baseUrl, apiKey, DEFAULT_ANTHROPIC_VERSION, connectTimeout, requestTimeout, bodyReadTimeout);
    }

    /**
     * ServiceLoader discovery form — inert by shape: blank base URL, no credentials
     * (the blank base makes {@link #endpoint} fail fast with an {@link
     * IllegalStateException} if the instance is ever misused for a call). Must not be
     * used for calls.
     */
    public AnthropicAdapter() {
        this.baseUrl = "";
        this.anthropicVersion = DEFAULT_ANTHROPIC_VERSION;
        this.auth = new ProviderAuth(ProviderAuth.TYPE_X_API_KEY, "");
        this.codec = AnthropicMessageCodec.create();
        this.connectTimeout = CONNECT_TIMEOUT;
        this.requestTimeout = HEADER_ARRIVAL_TIMEOUT;
        this.bodyReadTimeout = DEFAULT_BODY_READ_TIMEOUT;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** Test/internal form with explicit version + timeouts (default body deadline). */
    AnthropicAdapter(
            String baseUrl, String apiKey, String anthropicVersion, Duration connectTimeout, Duration requestTimeout) {
        this(baseUrl, apiKey, anthropicVersion, connectTimeout, requestTimeout, DEFAULT_BODY_READ_TIMEOUT);
    }

    /** Test/internal form with explicit connect, header-arrival, and body-read timeouts. */
    AnthropicAdapter(
            String baseUrl,
            String apiKey,
            String anthropicVersion,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration bodyReadTimeout) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must be non-blank");
        }
        Objects.requireNonNull(apiKey, "apiKey");
        if (anthropicVersion == null || anthropicVersion.isBlank()) {
            throw new IllegalArgumentException("anthropicVersion must be non-blank");
        }
        this.baseUrl = BaseUrls.normalize(baseUrl);
        this.anthropicVersion = anthropicVersion;
        this.auth = new ProviderAuth(ProviderAuth.TYPE_X_API_KEY, apiKey);
        this.codec = AnthropicMessageCodec.create();
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
    public String name() {
        return NAME;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public ProviderAuth auth() {
        return auth;
    }

    // ------------------------------------------------- timeout observability
    //
    // Read accessors for the effective deadlines — no behavior; they exist so the
    // composition-root tests (janus-gateway RouterFactoryTest) can pin that the
    // configured [janus.timeouts] values reached the timeout-aware constructor
    // (mirrors OpenAiCompatibleAdapter, which DeepSeekAdapter inherits).

    /** Effective connect deadline this adapter was constructed with. */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** Effective header-arrival deadline — the JDK client's per-request timeout. */
    public Duration headerTimeout() {
        return requestTimeout;
    }

    /** Effective non-stream body-read deadline (bounded read after headers arrive). */
    public Duration bodyReadTimeout() {
        return bodyReadTimeout;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        // Header arrival only via request timeout; body under bodyReadTimeout (default 300s).
        HttpResponse<InputStream> response = send(request, false, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (!HttpSupport.isSuccess(status)) {
            // The error body is read only to refine the status mapping via the Anthropic
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
            // the pool in practice, but the close makes the resource-release contract
            // explicit — a future decode that short-circuits must not pin the connection.
            HttpSupport.closeQuietly(response.body());
        }
        ChatResponse decoded;
        try {
            decoded = codec.decodeResponse(body);
        } catch (AnthropicCodecException e) {
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
            // like complete does (parity + LiteLLM's type-first mapping); the
            // connection is released either way, and a stalled/failed read preserves the
            // head-derived status + Retry-After.
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
                .header("Accept", "application/json")
                .header("anthropic-version", anthropicVersion)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth.secret() != null && !auth.secret().isBlank()) {
            builder.header("x-api-key", auth.secret());
        }
        Object beta = request.meta().get("anthropic-beta");
        if (beta instanceof String header && !header.isBlank()) {
            builder.header("anthropic-beta", header);
        }
        return builder.build();
    }

    private URI endpoint() {
        if (baseUrl.isBlank()) {
            // Discovery instance misused for a call (no base URL) — fail fast with a
            // clear typed error instead of a raw IllegalArgumentException from a relative
            // URI (SPI contract: caller bugs are loud, not cryptic).
            throw new IllegalStateException(
                    "this provider adapter (" + name() + ") has no base URL and must not be used for calls; "
                            + "construct it via the composition root with a base URL");
        }
        return URI.create(baseUrl + MESSAGES_PATH);
    }

    /**
     * Copy with the upstream wire decision forced (mirrors {@link
     * OpenAiCompatibleAdapter#withStream}; Anthropic has no {@code stream_options}, so the
     * copy drops them). The adapter owns the streaming decision regardless of the
     * canonical {@code stream} flag; {@code meta} passes through untouched.
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
                null,
                request.reasoning(),
                request.cacheControl(),
                request.hostedTools(),
                request.extras(),
                request.meta());
    }

    // ------------------------------------------------------------- streaming

    private Iterator<StreamChunk> chunkIterator(HttpResponse<InputStream> response) {
        SseFrameParser parser = new SseFrameParser(response.body());
        // A per-stream stateful decoder — Anthropic splits usage across
        // message_start (prompt side) and message_delta (completion side), and the
        // decoder merges them so the canonical terminal chunk carries the full usage.
        AnthropicStreamDecoder decoder = codec.newStreamDecoder();
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
             * One event frame → a canonical chunk (or the terminal marker). Tracks the
             * {@code message_stop} termination: a clean EOF <em>without</em> it is a
             * truncated stream.
             */
            private StreamChunk advance(SseFrameParser parser) {
                try {
                    SseEventFrame frame;
                    while ((frame = parser.nextEventFrame()) != null) {
                        if (MESSAGE_STOP.equals(frame.event())) {
                            terminated = true;
                            return null; // terminal event — stream ends cleanly
                        }
                        if ("error".equals(frame.event())) {
                            // Classified before the empty-data skip: an error frame
                            // with an empty payload is still an error — it falls back to
                            // the default upstream_4xx classification instead of being
                            // silently ignored (and surfacing as a truncation later).
                            throw errorFrameException(frame.data());
                        }
                        if (frame.data().isEmpty()) {
                            continue; // empty data frames carry nothing
                        }
                        StreamChunk chunk = decoder.decodeChunk(frame.event(), frame.data());
                        if (chunk != null) {
                            return chunk;
                        }
                        // no-op events (block stop, ping, text block start, unknown) — skip
                    }
                    if (!terminated) {
                        throw new ProviderException(
                                ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                                "upstream stream ended without the message_stop terminal event (truncated stream)",
                                null,
                                null);
                    }
                    return null;
                } catch (AnthropicCodecException e) {
                    throw new ProviderException(
                            ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                            "upstream sent an invalid streaming event",
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

    /**
     * Non-2xx classification: status-based default, refined by the Anthropic error
     * envelope's {@code error.type} when the body carries one (non-JSON bodies fall back
     * to the status mapping). 529 (overloaded) is Anthropic's documented retryable
     * status → {@code upstream_5xx}. The upstream {@code Retry-After} (429s — Anthropic's
     * tiered over-limit window) is carried on the exception so the gateway's passthrough
     * can forward the provider's backoff window.
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

    /** Status-only mapping (the body probe produced no envelope type): 401/403 → auth,
     * 429 → rate_limited, 529 and 5xx → upstream_5xx, everything else → upstream_4xx. */
    static ProviderException statusException(int status, String message, Long retryAfterSeconds) {
        String type =
                switch (status) {
                    case 401, 403 -> ProviderException.TYPE_AUTH;
                    case 429 -> ProviderException.TYPE_RATE_LIMITED;
                    case 529 -> ProviderException.TYPE_UPSTREAM_5XX;
                    default ->
                        status >= 500 ? ProviderException.TYPE_UPSTREAM_5XX : ProviderException.TYPE_UPSTREAM_4XX;
                };
        return new ProviderException(type, message, status, null, retryAfterSeconds);
    }

    /** Streaming {@code event: error} frame — classified purely by envelope type. */
    private static ProviderException errorFrameException(String data) {
        String envelopeType = errorEnvelopeType(data);
        String mapped = envelopeType == null ? null : envelopeTypeToProviderType(envelopeType);
        String type = mapped == null ? ProviderException.TYPE_UPSTREAM_4XX : mapped;
        return new ProviderException(
                type, "upstream streaming error frame" + (envelopeType == null ? "" : ": " + envelopeType), null, null);
    }

    /**
     * Anthropic error-envelope probe: {@code {"type":"error","error":{"type":...,"message":...}}}
     * — the nested {@code error.type} name, or null when the body is not an error
     * envelope (via the minimal {@link JsonProbe}; no Jackson dependency).
     */
    private static String errorEnvelopeType(String body) {
        String errorObject = JsonProbe.jsonMemberValue(body, "error");
        if (errorObject == null || "null".equals(errorObject)) {
            return null;
        }
        return JsonProbe.jsonStringMember(errorObject, "type");
    }

    /** Envelope {@code error.type} names → provider type; null when unmapped (status default). */
    private static String envelopeTypeToProviderType(String envelopeType) {
        return switch (envelopeType) {
            case "authentication_error" -> ProviderException.TYPE_AUTH;
            case "rate_limit_error" -> ProviderException.TYPE_RATE_LIMITED;
            case "overloaded_error", "api_error" -> ProviderException.TYPE_UPSTREAM_5XX;
            case "permission_error", "not_found_error" -> ProviderException.TYPE_UPSTREAM_4XX;
            default -> null;
        };
    }

    // --------------------------------------------------------------- helpers

    /**
     * Non-2xx classification with an error-body probe that may fail or be capped: the
     * status mapping and the upstream {@code Retry-After} are derived from the response
     * <b>head</b>, which is already available before the body is read — a stalled or
     * mid-read-failed error body must not discard them (a 429 with a stalled
     * body stays rate_limited with the provider's backoff window, a 401 stays auth and
     * non-retryable). The probe is best-effort: on success it may refine the status
     * default via the envelope's {@code error.type}; on any read failure the status
     * mapping is raised unchanged. The connection is released either way.
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
}
