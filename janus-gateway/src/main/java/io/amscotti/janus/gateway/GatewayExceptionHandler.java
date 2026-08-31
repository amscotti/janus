package io.amscotti.janus.gateway;

import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.store.UnpricedModelException;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Global error-envelope handler, <b>path-aware</b> (
 * note: "revisit if a second face needs a different envelope"): every exception escaping
 * a route (including the eager upstream send in either streaming path — no stream was
 * started, so a plain HTTP error is correct) becomes a shaped JSON body — the Anthropic
 * envelope for {@code /v1/messages*} via {@link AnthropicErrorMapper}, the OpenAI
 * envelope for everything else via {@link ErrorMapper}. Mid-stream failures never reach
 * here — {@link SseChunkPublisher}/{@link AnthropicSsePublisher} emit them as SSE error
 * frames. Unknown paths still get Micronaut's default 404 body (not an envelope).
 *
 * <p>Micronaut 5.1 pattern: a {@code @Singleton} bean implementing {@link
 * ExceptionHandler} (the old {@code @ControllerAdvice}/{@code @ErrorHandler} annotations
 * no longer exist in 5.x). Dispatch uses an exact prefix match on
 * {@code HttpRequest.getUri.getPath} (the {@code /v1/messages} route is fixed, so a
 * raw prefix is not fragile; both faces' integration suites pin the split).
 *
 * <p><b>the first header-carrying mapping.</b> {@link RateLimitExceededException}
 * (the gateway-originated 429) carries {@code retryAfterSeconds} on
 * {@code RATE_LIMIT_EXCEEDED} (null on {@code BUDGET_EXCEEDED_HARD} — a budget cap does
 * not refill on a timer): the handler sets {@code Retry-After: <seconds>} on <b>both</b>
 * faces' envelopes when the value is present. The upstream 429 passthrough
 * ({@code ProviderException.TYPE_RATE_LIMITED}) <em>also</em> forwards the upstream's
 * {@code Retry-After} when the adapter captured one ({@link
 * io.amscotti.janus.provider.ProviderException#retryAfterSeconds}) — SDKs otherwise
 * fall back to default backoff, losing the provider's precise window; a
 * passthrough with no captured header stays header-less (byte-identical behavior).
 */
@Singleton
@Produces(MediaType.APPLICATION_JSON)
class GatewayExceptionHandler implements ExceptionHandler<Throwable, HttpResponse<String>> {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    private final ErrorMapper errorMapper;
    private final AnthropicErrorMapper anthropicErrorMapper;

    GatewayExceptionHandler() {
        this.errorMapper = new ErrorMapper();
        this.anthropicErrorMapper = new AnthropicErrorMapper();
    }

    @Override
    @SuppressWarnings("rawtypes") // ExceptionHandler declares raw HttpRequest in Micronaut 5.1
    public HttpResponse<String> handle(HttpRequest request, Throwable throwable) {
        if (isAnthropicPath(request)) {
            AnthropicErrorMapper.ErrorMapping mapping = anthropicErrorMapper.map(throwable);
            warnIfServerError(request, mapping.status(), throwable);
            return withRetryAfter(
                    HttpResponse.status(mapping.status())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(GatewayJson.anthropicErrorBody(mapping.envelope())),
                    throwable);
        }
        ErrorMapper.ErrorMapping mapping = errorMapper.map(throwable);
        warnIfServerError(request, mapping.status(), throwable);
        return withRetryAfter(
                HttpResponse.status(mapping.status())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(GatewayJson.errorBody(mapping.envelope())),
                throwable);
    }

    /**
     * Every response this handler will render as 5xx lands in the GATEWAY log (the
     * posture — the client envelope says "internal server error"/"api_error", the
     * specifics stay server-side). One line at WARN: class + message (operation
     * metadata; upstream-derived messages are secret-redacted by the mappers before
     * any envelope, and this log is server-side only). The full stack is DEBUG — a
     * typed upstream outage must not stack-trace-spam the log (the smoke gates' log
     * hygiene), while an unexpected untyped 500 is one debug-flag away from its trace.
     */
    private static void warnIfServerError(HttpRequest<?> request, HttpStatus status, Throwable throwable) {
        if (throwable instanceof RateLimitExceededException || throwable instanceof UnpricedModelException) {
            LOG.debug(
                    "expected client error ({} {}): {}",
                    request.getMethod(),
                    request.getUri().getPath(),
                    throwable.getClass().getSimpleName());
            return;
        }
        if (status.getCode() >= 500) {
            LOG.warn(
                    "request failed ({} {}): {}: {}",
                    request.getMethod(),
                    request.getUri().getPath(),
                    throwable.getClass().getName(),
                    // The envelope path redacts sk-… shapes;
                    // the gateway log must not become the leak path instead. The
                    // mappers never place upstream body text in messages today (the
                    // defense-in-depth note) — this is the same belt on the log side.
                    ErrorMapper.redactSecrets(throwable.getMessage()));
            LOG.debug("5xx failure stack", throwable);
        }
    }

    /**
     * Set {@code Retry-After} on the response iff the throwable carries the seconds: the
     * gateway-originated rate-limit denial ({@code RATE_LIMIT_EXCEEDED} — budget denials
     * carry null, a budget does not refill on a timer) or an upstream 429 passthrough
     * whose adapter captured the provider's {@code Retry-After}. Every other
     * mapping stays header-less, so a passthrough without a captured header is
     * byte-identical to the pre- behavior.
     */
    private static MutableHttpResponse<String> withRetryAfter(
            MutableHttpResponse<String> response, Throwable throwable) {
        Long retryAfterSeconds = retryAfterSecondsOf(throwable);
        if (retryAfterSeconds != null) {
            return response.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }
        return response;
    }

    /** The throwable's {@code Retry-After} delta-seconds, or null when it carries none. */
    private static Long retryAfterSecondsOf(Throwable throwable) {
        if (throwable instanceof RateLimitExceededException rateLimit) {
            return rateLimit.retryAfterSeconds();
        }
        if (throwable instanceof ProviderException provider) {
            return provider.retryAfterSeconds();
        }
        return null;
    }

    @SuppressWarnings("rawtypes") // ExceptionHandler declares raw HttpRequest in Micronaut 5.1
    private static boolean isAnthropicPath(HttpRequest request) {
        String path = request.getUri().getPath();
        if (path == null) {
            return false;
        }
        // The membership test runs on the same normalized path the KeyAuthFilter
        // uses, and both share the {@link Face} route vocabulary — the filter's
        // auth classification and the handler's envelope selection cannot diverge if
        // routes are added (e.g. a future /v1/messagesfoo route must render the
        // OpenAI envelope, never the Anthropic one).
        return Face.of(KeyAuthFilter.normalizePath(path))
                .filter(f -> f == Face.ANTHROPIC)
                .isPresent();
    }
}
