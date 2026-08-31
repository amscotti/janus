package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * C1 — sliding-window ({@code [janus.limits] window = "sliding"}) TPM enforcement
 * end-to-end through the filter + controllers, via {@link TestGovernanceFactory}'s
 * {@value TestGovernanceFactory#WINDOW_PROPERTY} property. The token-bucket pre-check
 * must mirror the fixed-window boundary — deny iff {@code estimate > tokens available}
 * (consumed + estimate > capacity) — not the inverted {@code tokens + estimate >
 * capacity}: a light user after 15 real tokens must be allowed a 16-token estimate
 * (the inverted check would spuriously 429: 85 + 16 = 101 > 100), and a request whose
 * estimate crosses the remaining tokens is 429'd before dispatch (no upstream call).
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
@Property(name = "janus.test.governance", value = "true")
@Property(name = "janus.test.governance.window", value = "sliding")
class GovernanceSlidingWindowTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    KeyStore keyStore;

    @Test
    void slidingTpmPreCheckMirrorsFixedWindowBoundaryEndToEnd() {
        KeyStore.CreatedKey created = keyStore.create(new KeyStore.KeyCreateRequest(
                "sliding-tpm", List.of("deepseek-v4-flash"), null, null, null, null, 100));
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        TestRouterFactory.BACKEND.completeCalls.clear();

        // Request 1: estimate 10 ≤ 100 fits; dispatches; the 10/5 = 15 real tokens
        // accumulate at finalize ⇒ 85 remain (fixed clock, no refill yet).
        assertEquals(HttpStatus.OK, postOpenAi(created.fullKey(), 10).getStatus());
        assertEquals(1, TestRouterFactory.BACKEND.completeCalls.size());

        // Request 2: 16 > 85? No — 15 consumed + 16 = 31 ≤ 100. The inverted pre-check
        // (85 + 16 = 101 > 100) would spuriously 429 a light user here; the fixed
        // mirror must allow it (this is the C1 regression: a fresh key that has used
        // *few* tokens gets its next request denied).
        assertEquals(HttpStatus.OK, postOpenAi(created.fullKey(), 16).getStatus());
        assertEquals(
                2,
                TestRouterFactory.BACKEND.completeCalls.size(),
                "a light user's next request must dispatch (estimate 16 ≤ 85 remaining)");

        // Request 3: after 30 real tokens 70 remain; estimate 71 > 70 ⇒ 30 + 71 = 101
        // > 100 crosses — 429 before dispatch (the heavy-user denial the inverted check
        // missed).
        HttpResponse<?> denied = errorResponse(postRequest("/v1/chat/completions", created.fullKey(), openAiBody(71)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, denied.getStatus());
        assertTrue(
                denied.getBody(String.class).orElse("").contains("\"type\":\"rate_limit_error\""),
                denied.getBody(String.class).orElse(""));
        // The sliding variant's TPM Retry-After is the documented
        // conservative aligned-window upper bound (wouldExceed surfaces only a boolean,
        // so the true deficit÷rate refill seconds are not exposed) — pinned here as 60
        // (the fixed clock is epoch-aligned; the bucket may refill sooner, never later).
        assertEquals(
                "60",
                denied.getHeaders().get(HttpHeaders.RETRY_AFTER),
                "sliding TPM denial carries the aligned-window Retry-After upper bound");
        assertEquals(
                2,
                TestRouterFactory.BACKEND.completeCalls.size(),
                "a TPM-pre-checked request must never be dispatched upstream");
    }

    // ------------------------------------------------------------------------ helpers

    private HttpResponse<String> postOpenAi(String fullKey, int maxTokens) {
        return client.toBlocking()
                .exchange(postRequest("/v1/chat/completions", fullKey, openAiBody(maxTokens)), String.class);
    }

    private HttpRequest<String> postRequest(String path, String fullKey, String body) {
        return HttpRequest.POST(path, body)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", fullKey);
    }

    /** {@code exchange()} throws on non-2xx; return the exception's response (headers kept). */
    private HttpResponse<?> errorResponse(HttpRequest<?> request) {
        HttpClientResponseException exception = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class, () -> client.toBlocking().exchange(request, String.class));
        return exception.getResponse();
    }

    private static String openAiBody(int maxTokens) {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false,"
                + "\"max_tokens\":" + maxTokens + "}";
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }
}
