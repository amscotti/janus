package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.context.annotation.Property;
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
 * per-key model-scope enforcement in the controllers (auth on via the
 * test-only master-key property): a key scoped to {@code ["deepseek-v4-flash"]} is denied
 * (403 {@code permission_error} in the face-appropriate envelope) when it calls
 * another alias, and passes when it calls its own. The check runs against
 * {@code ChatRequest.model} — the client alias — per the documented scope
 * semantics (see {@link io.amscotti.janus.store.AccessPolicy}); auth-off (no key
 * attached) skips the check entirely, which is why the existing / suites pass
 * unchanged (no {@code janus.test.master-key} on those classes).
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
class KeyAuthScopeControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    KeyStore keyStore;

    @Test
    void scopedKeyDeniedModelIs403OpenAiEnvelope() {
        String key = createKey("app-a", List.of("deepseek-v4-flash"));
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-pro"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-api-key", key));
        assertEquals(HttpStatus.FORBIDDEN, http.getStatus());
        assertTrue(http.body().contains("\"error\":{\"message\""), http.body());
        assertTrue(http.body().contains("\"type\":\"permission_error\""), http.body());
        assertTrue(http.body().contains("key is not permitted to access the requested model"), http.body());
    }

    @Test
    void scopedKeyDeniedModelIs403AnthropicEnvelope() {
        String key = createKey("app-a", List.of("deepseek-v4-flash"));
        HttpResponse<String> http = errorResponse(HttpRequest.POST("/v1/messages", anthropicBody("deepseek-v4-pro"))
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", key));
        assertEquals(HttpStatus.FORBIDDEN, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"error\""), http.body());
        assertTrue(http.body().contains("\"type\":\"permission_error\""), http.body());
        assertTrue(http.body().contains("key is not permitted to access the requested model"), http.body());
    }

    @Test
    void scopedKeyDeniedModelOnStreamingRequestIs403PlainEnvelope() {
        // The streaming-safety claim must hold for a scoped key too — the scope
        // check runs after body decode, so on a stream:true request a denial must
        // still come back as a plain HTTP 403 JSON envelope, never an SSE frame.
        String key = createKey("app-a", List.of("deepseek-v4-flash"));
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBodyStreaming("deepseek-v4-pro"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-api-key", key));
        assertEquals(HttpStatus.FORBIDDEN, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"permission_error\""), http.body());
        assertFalse(http.body().contains("data:"), "a scope denial must not be an SSE frame");
    }

    @Test
    void scopedKeyCallingItsOwnAliasPasses() {
        String key = createKey("app-a", List.of("deepseek-v4-flash"));
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", key),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
    }

    @Test
    void unscopedKeyCanCallAnyAlias() {
        String key = createKey("app-b", List.of()); // empty scope = allow all
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-pro"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", key),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
    }

    // ---------------------------------------------------------------- helpers

    private String createKey(String owner, List<String> models) {
        return keyStore.create(new KeyStore.KeyCreateRequest(owner, models, null, null, null, null, null))
                .fullKey();
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new io.amscotti.janus.core.model.AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static String requestBody(String model) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
    }

    private static String requestBodyStreaming(String model) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";
    }

    private static String anthropicBody(String model) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private HttpResponse<String> errorResponse(HttpRequest<?> request) {
        HttpClientResponseException exception = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class, () -> client.toBlocking().exchange(request, String.class));
        HttpResponse<?> response = exception.getResponse();
        return HttpResponse.status(response.getStatus())
                .body(response.getBody(String.class).orElse(""));
    }
}
