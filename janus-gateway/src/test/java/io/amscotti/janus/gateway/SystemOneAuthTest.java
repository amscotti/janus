package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Auth <b>on</b> for the decision face: {@code POST /v1/systemone} is gated by
 * the same {@code Face}-driven virtual-key filter as the chat faces (a missing
 * key is 401, a key scoped elsewhere is 403 {@code permission_error} in the
 * OpenAI envelope), the per-key scope check runs against the client alias, and
 * neither rejection ever reaches a backend — pinned here in JUnit, not only in
 * the opt-in smoke drill.
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
class SystemOneAuthTest {

    private static final String REQUEST = """
            {"model":"jev-latest","state":"I was charged twice.","questions":{
              "refund":{"type":"noul","instructions":"Is the customer asking for money back?"}}}
            """;

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    KeyStore keyStore;

    @BeforeEach
    void resetSharedBackend() {
        TestRouterFactory.DECISION_BACKEND.reset();
    }

    @Test
    void missingKeyIs401BeforeAnyDispatch() {
        HttpClientResponseException e = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/v1/systemone", REQUEST).contentType(MediaType.APPLICATION_JSON),
                                String.class));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
        assertTrue(TestRouterFactory.DECISION_BACKEND.calls.isEmpty(), "401 never reaches a backend");
    }

    @Test
    void missingKeyWithMalformedBodyIs401Not400() {
        HttpClientResponseException e = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/v1/systemone", "not json").contentType(MediaType.APPLICATION_JSON),
                                String.class));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus(), "auth runs before body decode");
        assertTrue(TestRouterFactory.DECISION_BACKEND.calls.isEmpty());
    }

    @Test
    void scopedKeyDeniedModelIs403OpenAiEnvelope() {
        String key = createKey("app-a", List.of("d3"));

        HttpResponse<String> http = errorResponse(HttpRequest.POST("/v1/systemone", REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", key));

        assertEquals(HttpStatus.FORBIDDEN, http.getStatus());
        assertTrue(http.body().contains("\"error\":{\"message\""), http.body());
        assertTrue(http.body().contains("\"type\":\"permission_error\""), http.body());
        assertTrue(TestRouterFactory.DECISION_BACKEND.calls.isEmpty(), "403 never reaches a backend");
    }

    @Test
    void scopedKeyCallingItsOwnAliasSucceeds() {
        TestRouterFactory.DECISION_BACKEND.decideReturns(new DecisionResponse(
                "jev-latest",
                Map.of("refund", new DecisionAnswer.NoulAnswer(0.9)),
                new Usage(1, 0, 1),
                Map.of(),
                Map.of()));
        String key = createKey("app-a", List.of("jev-latest"));

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/systemone", REQUEST)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", key),
                        String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertEquals(1, TestRouterFactory.DECISION_BACKEND.calls.size());
    }

    private String createKey(String owner, List<String> models) {
        return keyStore.create(new KeyStore.KeyCreateRequest(owner, models, null, null, null, null, null))
                .fullKey();
    }

    private HttpResponse<String> errorResponse(HttpRequest<String> request) {
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = (HttpResponse<String>) e.getResponse();
            return response;
        }
    }
}
