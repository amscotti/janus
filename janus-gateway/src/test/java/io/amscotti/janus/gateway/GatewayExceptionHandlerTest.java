package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.provider.ProviderException;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

/**
 * {@link GatewayExceptionHandler} is path-aware: an exception escaping
 * {@code /v1/messages*} renders the Anthropic envelope, the same failure on
 * {@code /v1/chat/completions} keeps the OpenAI envelope (
 * note: "revisit if a second face needs a different envelope"). Both faces' byte shapes
 * are pinned by the integration suites ({@link MessagesControllerTest},
 * {@link ChatCompletionsControllerTest}); this unit test pins the dispatch itself on
 * synthetic requests.
 */
class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void messagesPathRendersAnthropicEnvelope() {
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/messages", "{}"),
                new ProviderException(ProviderException.TYPE_AUTH, "invalid api key"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatus());
        assertTrue(response.body().contains("\"type\":\"error\""), response.body());
        assertTrue(response.body().contains("\"type\":\"authentication_error\""), response.body());
        assertFalse(response.body().contains("\"error\":{\"message\""), "OpenAI wrapper leaked: " + response.body());
    }

    @Test
    void chatCompletionsPathRendersOpenAiEnvelope() {
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/chat/completions", "{}"),
                new ProviderException(ProviderException.TYPE_AUTH, "invalid api key"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatus());
        assertTrue(response.body().contains("\"error\":{\"message\""), response.body());
        assertTrue(response.body().contains("\"type\":\"authentication_error\""), response.body());
        assertFalse(response.body().contains("\"type\":\"error\""), "Anthropic envelope leaked: " + response.body());
    }

    // ---------------------------- path membership matches the filter's vocabulary

    @Test
    void trailingSlashMessagesPathRendersAnthropicEnvelope() {
        // The handler's Anthropic membership test runs on the same normalized path
        // the KeyAuthFilter uses — a trailing-slash /v1/messages/ still gets the
        // Anthropic envelope (the two vocabularies cannot diverge).
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/messages/", "{}"),
                new ProviderException(ProviderException.TYPE_AUTH, "invalid api key"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatus());
        assertTrue(response.body().contains("\"type\":\"error\""), response.body());
        assertTrue(response.body().contains("\"type\":\"authentication_error\""), response.body());
    }

    @Test
    void messagesPathSuffixRendersOpenAiEnvelope() {
        // Exact-match membership after normalization: a hypothetical /v1/messagesfoo
        // route is NOT the Anthropic face — it must render the OpenAI envelope (the
        // filter's protected set is exact-match too, so no latent drift if routes grow).
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/messagesfoo", "{}"),
                new ProviderException(ProviderException.TYPE_AUTH, "invalid api key"));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatus());
        assertTrue(response.body().contains("\"error\":{\"message\""), response.body());
        assertFalse(response.body().contains("\"type\":\"error\""), "Anthropic envelope leaked: " + response.body());
    }

    // ------------------------------------------------------ KeyAuthException dispatch

    @Test
    void keyAuthExceptionOnMessagesPathRendersAnthropicEnvelope() {
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/messages", "{}"),
                new KeyAuthException(KeyAuthException.Reason.SCOPE_DENIED, "k1"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatus());
        assertTrue(response.body().contains("\"type\":\"error\""), response.body());
        assertTrue(response.body().contains("\"type\":\"permission_error\""), response.body());
        assertFalse(response.body().contains("\"error\":{\"message\""), "OpenAI wrapper leaked: " + response.body());
    }

    @Test
    void keyAuthExceptionOnChatCompletionsPathRendersOpenAiEnvelope() {
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/chat/completions", "{}"),
                new KeyAuthException(KeyAuthException.Reason.REVOKED, "k1"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatus());
        assertTrue(response.body().contains("\"error\":{\"message\""), response.body());
        assertTrue(response.body().contains("\"type\":\"permission_error\""), response.body());
        assertFalse(response.body().contains("\"type\":\"error\""), "Anthropic envelope leaked: " + response.body());
    }

    @Test
    void missingKeyAuthOnAdminPathIs401OpenAiEnvelope() {
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/key/generate", "{}"), new KeyAuthException(KeyAuthException.Reason.MISSING, null));
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatus());
        assertTrue(response.body().contains("\"type\":\"authentication_error\""), response.body());
    }

    // ----------------------------------------- RateLimitExceededException + Retry-After

    @Test
    void rateLimitExceededSetsRetryAfterOnOpenAiEnvelope() {
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/chat/completions", "{}"),
                new RateLimitExceededException(RateLimitExceededException.Reason.RATE_LIMIT_EXCEEDED, 60L, "k1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatus());
        assertEquals("60", response.getHeaders().get(HttpHeaders.RETRY_AFTER), "Retry-After must be set");
        assertTrue(response.body().contains("\"error\":{\"message\""), response.body());
        assertTrue(response.body().contains("\"type\":\"rate_limit_error\""), response.body());
    }

    @Test
    void rateLimitExceededSetsRetryAfterOnAnthropicEnvelope() {
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/messages", "{}"),
                new RateLimitExceededException(RateLimitExceededException.Reason.RATE_LIMIT_EXCEEDED, 30L, "k1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatus());
        assertEquals("30", response.getHeaders().get(HttpHeaders.RETRY_AFTER));
        assertTrue(response.body().contains("\"type\":\"error\""), response.body());
        assertTrue(response.body().contains("\"type\":\"rate_limit_error\""), response.body());
    }

    @Test
    void budgetExceededHardCarriesNoRetryAfterHeader() {
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/chat/completions", "{}"),
                new RateLimitExceededException(RateLimitExceededException.Reason.BUDGET_EXCEEDED_HARD, null, "k1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatus());
        assertNull(response.getHeaders().get(HttpHeaders.RETRY_AFTER), "a budget does not refill on a timer");
        assertTrue(response.body().contains("\"type\":\"rate_limit_error\""), response.body());
        // The OpenAI envelope discriminates the budget denial with the code.
        assertTrue(response.body().contains("\"code\":\"insufficient_quota\""), response.body());
    }

    @Test
    void rateLimitExceededCarriesRateLimitExceededCode() {
        // The gateway-originated rate-limit denial carries the reference implementation semantics's
        // "rate_limit_exceeded" code — the budget denial is discriminated by its
        // "insufficient_quota" code, the rate-limit denial by "rate_limit_exceeded".
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/chat/completions", "{}"),
                new RateLimitExceededException(RateLimitExceededException.Reason.RATE_LIMIT_EXCEEDED, 60L, "k1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatus());
        assertTrue(response.body().contains("\"code\":\"rate_limit_exceeded\""), response.body());
    }

    @Test
    void upstreamRateLimitedPassthroughStaysHeaderlessWhenNoHeaderCaptured() {
        // The header path is additive: the upstream 429 passthrough row must keep
        // producing header-less responses (byte-identical / behavior) when the
        // adapter captured no Retry-After; the envelope still carries the new code.
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/chat/completions", "{}"),
                new ProviderException(ProviderException.TYPE_RATE_LIMITED, "slow down", 429, null));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatus());
        assertNull(response.getHeaders().get(HttpHeaders.RETRY_AFTER), "upstream passthrough stays header-less");
        assertTrue(response.body().contains("\"code\":\"rate_limit_exceeded\""), response.body());
    }

    @Test
    void upstreamRateLimitedPassthroughForwardsRetryAfter() {
        // An adapter that captured the upstream Retry-After on a 429 must
        // have it forwarded — LiteLLM forwards it and SDKs otherwise fall back to
        // default backoff, losing the provider's precise window.
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/chat/completions", "{}"),
                new ProviderException(ProviderException.TYPE_RATE_LIMITED, "slow down", 429, null, 120L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatus());
        assertEquals("120", response.getHeaders().get(HttpHeaders.RETRY_AFTER), "upstream Retry-After forwarded");
    }

    @Test
    void upstreamRateLimitedPassthroughForwardsRetryAfterOnAnthropicFace() {
        HttpResponse<String> response = handler.handle(
                HttpRequest.POST("/v1/messages", "{}"),
                new ProviderException(ProviderException.TYPE_RATE_LIMITED, "slow down", 429, null, 30L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatus());
        assertEquals("30", response.getHeaders().get(HttpHeaders.RETRY_AFTER), "upstream Retry-After forwarded");
        assertTrue(response.body().contains("\"type\":\"error\""), response.body());
    }
    // ---------------------------- the 5xx log line is secret-redacted

    @Test
    void fiveHundredLogLineRedactSecretShapes() {
        // The envelope path redacts sk-… before any client sees it; the gateway
        // log is the other audience — the WARN line must carry the same redaction so a
        // provider echoing our key ("Incorrect API key provided: sk-…") can never land
        // in the server log via the exception message.
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GatewayExceptionHandler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            handler.handle(
                    HttpRequest.POST("/v1/chat/completions", "{}"),
                    new ProviderException(
                            ProviderException.TYPE_UPSTREAM_5XX,
                            "upstream 531: Incorrect API key provided: sk-live-DEADBEEF123"));
            assertFalse(
                    appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("sk-live-DEADBEEF123")),
                    "the raw key shape must never reach the log");
            assertTrue(
                    appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("<redacted>")),
                    "the redacted form is logged instead");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
