package io.amscotti.janus.provider;

import io.amscotti.janus.core.codec.DecisionCodecException;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Shared HTTP + error-mapping base for the two decision dialects — the
 * complete-only slice of {@link OpenAiCompatibleAdapter}'s contract (decision
 * calls never stream): header-arrival request timeout, bounded body reads,
 * status-based error classification refined by an OpenAI-shaped error
 * envelope's {@code error.type} when present (plain-string error bodies —
 * Ollama's {@code {"error":"…"}} — keep the status mapping), and the three
 * constructor forms (composition-root, timeout-aware, inert ServiceLoader
 * discovery with a blank base that fails fast on a call).
 *
 * <p>Subclasses supply only the dialect: endpoint resolution, request
 * encoding, and response decoding through their codec.
 */
abstract class AbstractDecisionAdapter implements DecisionAdapter {

    /** Connect deadline (docs/architecture.md timeouts table). */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** Header-arrival deadline — the JDK client's per-request timeout. */
    static final Duration HEADER_ARRIVAL_TIMEOUT = Duration.ofSeconds(60);

    /** Default non-stream body-read deadline after headers arrive (stall → timeout). */
    static final Duration DEFAULT_BODY_READ_TIMEOUT = Duration.ofSeconds(300);

    private final String name;
    private final String baseUrl;
    private final ProviderAuth auth;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final Duration bodyReadTimeout;
    private final Duration connectTimeout;

    /** Composition-root form: adapter name + resolved base URL + API key (blank = no auth header). */
    protected AbstractDecisionAdapter(String name, String baseUrl, String apiKey) {
        this(name, baseUrl, apiKey, CONNECT_TIMEOUT, HEADER_ARRIVAL_TIMEOUT, DEFAULT_BODY_READ_TIMEOUT);
    }

    /** ServiceLoader discovery form — blank base + blank secret; inert, must not be used for calls. */
    protected AbstractDecisionAdapter(String name) {
        this.name = Objects.requireNonNull(name, "name");
        this.baseUrl = "";
        this.auth = new ProviderAuth(ProviderAuth.TYPE_BEARER, "");
        this.connectTimeout = CONNECT_TIMEOUT;
        this.requestTimeout = HEADER_ARRIVAL_TIMEOUT;
        this.bodyReadTimeout = DEFAULT_BODY_READ_TIMEOUT;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /** Timeout-aware composition-root form (the {@code [janus.timeouts]} threading leg). */
    protected AbstractDecisionAdapter(
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
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
        this.bodyReadTimeout = requirePositive(bodyReadTimeout, "bodyReadTimeout");
        this.httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    private static Duration requirePositive(Duration value, String what) {
        Objects.requireNonNull(value, what);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(what + " must be > 0: " + value);
        }
        return value;
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

    /** Effective connect deadline (composition-root test observability, as on the chat adapters). */
    public final Duration connectTimeout() {
        return connectTimeout;
    }

    /** Effective header-arrival deadline. */
    public final Duration headerTimeout() {
        return requestTimeout;
    }

    /** Effective body-read deadline. */
    public final Duration bodyReadTimeout() {
        return bodyReadTimeout;
    }

    /** The dispatch endpoint for {@code baseUrl} (the dialect's path rule). */
    protected abstract URI endpoint(String baseUrl);

    /** Canonical request → this dialect's wire body (may throw {@code DecisionCodecException}). */
    protected abstract String encode(DecisionRequest request);

    /** Dialect-specific request headers (e.g. TypeSafe's danger-zone beta gates). Default: none. */
    protected void addHeaders(HttpRequest.Builder builder, DecisionRequest request) {}

    /** This dialect's success body → canonical (may throw {@code DecisionCodecException}). */
    protected abstract DecisionResponse decode(String body);

    @Override
    public final DecisionResponse decide(DecisionRequest request) {
        Objects.requireNonNull(request, "request");
        if (baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "this decision adapter (" + name + ") has no base URL and must not be used for calls; "
                            + "construct it via the composition root with a base URL");
        }
        String outbound;
        try {
            outbound = encode(request);
        } catch (DecisionCodecException e) {
            // A per-backend dialect/cap rejection (e.g. OpenAI's 400-question cap) is
            // not a client-input error at the face: encode it as a retryable
            // capability failure so a mixed pool fails over instead of returning a
            // round-robin-position-dependent 400. No HTTP request was sent.
            throw new ProviderException(ProviderException.TYPE_UNSUPPORTED_REQUEST, e.getMessage(), null, e);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(baseUrl))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(outbound));
        if (auth.secret() != null && !auth.secret().isBlank()) {
            builder.header("Authorization", "Bearer " + auth.secret());
        }
        addHeaders(builder, request);
        HttpRequest authorized = builder.build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(authorized, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new ProviderException(ProviderException.TYPE_TIMEOUT, "upstream request timed out", null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(
                    ProviderException.TYPE_UPSTREAM_4XX, "interrupted while waiting for upstream", null, e);
        } catch (IOException e) {
            throw new ProviderException(
                    ProviderException.TYPE_NETWORK, "upstream request failed: " + e.getMessage(), null, e);
        }
        int status = response.statusCode();
        if (!HttpSupport.isSuccess(status)) {
            throw errorStatusException(response, status, "upstream returned HTTP " + status + " for decision request");
        }
        String body;
        try {
            body = HttpSupport.readBody(response.body(), bodyReadTimeout);
        } finally {
            HttpSupport.closeQuietly(response.body());
        }
        DecisionResponse decoded;
        try {
            decoded = decode(body);
        } catch (DecisionCodecException e) {
            throw new ProviderException(
                    ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                    "upstream returned an invalid decision response body",
                    null,
                    e);
        }
        requireCompleteAnswers(request, decoded);
        // meta passes through untouched (gateway-internal; never serialized, never read).
        return new DecisionResponse(
                decoded.model(), decoded.answers(), decoded.usage(), decoded.extras(), request.meta());
    }

    /**
     * One answer per question: a truncated/partial upstream response must be a
     * typed upstream failure, never a 200 with missing keys (strict Jev SDKs read a
     * missing key as a refusal, so an upstream bug would masquerade as a decision).
     * Counts only in the message — question names are client content.
     */
    private static void requireCompleteAnswers(DecisionRequest request, DecisionResponse response) {
        Set<String> expected =
                request.questions() == null ? Set.of() : request.questions().keySet();
        Set<String> actual =
                response.answers() == null ? Set.of() : response.answers().keySet();
        if (!actual.containsAll(expected)) {
            long missing =
                    expected.stream().filter(name -> !actual.contains(name)).count();
            throw new ProviderException(
                    ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                    "upstream omitted " + missing + " of " + expected.size() + " requested answers",
                    null,
                    null);
        }
        if (!expected.containsAll(actual)) {
            throw new ProviderException(
                    ProviderException.TYPE_BAD_UPSTREAM_PAYLOAD,
                    "upstream returned an answer for a question that was not requested",
                    null,
                    null);
        }
    }

    /**
     * Non-2xx classification: status-based default refined by an envelope
     * {@code error.type} when the body carries an object envelope; the body
     * text itself never reaches an exception message. Head-derived status and
     * {@code Retry-After} survive a stalled error-body read (the chat
     * adapters' discipline).
     */
    private ProviderException errorStatusException(HttpResponse<InputStream> response, int status, String message) {
        Long retryAfter = HttpSupport.retryAfterSeconds(response);
        String errorBody;
        try {
            errorBody = HttpSupport.readErrorBody(response.body(), bodyReadTimeout);
        } catch (ProviderException readFailure) {
            return statusException(status, message, retryAfter);
        } finally {
            HttpSupport.closeQuietly(response.body());
        }
        String envelopeType = errorEnvelopeType(errorBody);
        if (envelopeType != null) {
            String mapped = envelopeTypeToProviderType(envelopeType);
            if (mapped != null) {
                return new ProviderException(
                        mapped,
                        "upstream error envelope: " + envelopeType + " (HTTP " + status + ")",
                        status,
                        null,
                        retryAfter);
            }
        }
        return statusException(status, message, retryAfter);
    }

    private static ProviderException statusException(int status, String message, Long retryAfterSeconds) {
        String type =
                switch (status) {
                    case 401, 403 -> ProviderException.TYPE_AUTH;
                    case 429 -> ProviderException.TYPE_RATE_LIMITED;
                    default ->
                        status >= 500 ? ProviderException.TYPE_UPSTREAM_5XX : ProviderException.TYPE_UPSTREAM_4XX;
                };
        return new ProviderException(type, message, status, null, retryAfterSeconds);
    }

    private static String envelopeTypeToProviderType(String envelopeType) {
        return switch (envelopeType) {
            case "rate_limit_error", "insufficient_quota", "rate_limit_exceeded" -> ProviderException.TYPE_RATE_LIMITED;
            case "authentication_error", "invalid_api_key", "permission_error" -> ProviderException.TYPE_AUTH;
            case "server_error", "api_error", "overloaded_error" -> ProviderException.TYPE_UPSTREAM_5XX;
            default -> null;
        };
    }

    private static String errorEnvelopeType(String body) {
        String errorObject = JsonProbe.jsonMemberValue(body, "error");
        if (errorObject == null || "null".equals(errorObject)) {
            return null;
        }
        return JsonProbe.jsonStringMember(errorObject, "type");
    }
}
