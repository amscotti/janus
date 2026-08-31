package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C2 — {@code GET /v1/models} integration: the router's configured aliases are
 * listed in config insertion order with {@code owned_by} = backend name.
 * the OpenAI-face route is Tier-1 metered — a success lands in
 * {@code janus_requests_total{face="openai",status="2xx"}} (the openai series was
 * previously incomplete; the failure leg is pinned by {@link ModelsControllerMetricsTest}).
 */
@MicronautTest
@Property(name = "janus.test.metrics", value = "true")
class ModelsControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @BeforeEach
    void reset() {
        TestMetricsFactory.REGISTRY.clear();
    }

    @Test
    void listsConfiguredModelsInConfigOrder() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/v1/models"), String.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        String body = response.body();
        assertTrue(body.contains("\"object\":\"list\""), body);
        int first = body.indexOf("deepseek-v4-flash");
        int second = body.indexOf("deepseek-v4-pro");
        assertTrue(first >= 0 && second > first, "models must appear in config insertion order: " + body);
        assertTrue(body.contains("\"owned_by\":\"deepseek\""), body);
    }

    @Test
    void modelsRequestIsMeteredOnTheOpenAiFace() {
        // /v1/models was previously invisible in the openai series — a success must
        // land in the 2xx bucket (the route is auth-off surface, so never a per-key label).
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/v1/models"), String.class);
        assertEquals(HttpStatus.OK, response.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0"),
                "GET /v1/models must be metered on the openai face:\n" + scrape);
        assertTrue(
                scrape.contains("janus_request_duration_seconds_count{face=\"openai\"} 1"),
                "the latency histogram must record the models request:\n" + scrape);
    }

    // ------------------------------------------- GET /v1/models/{id} (retrieve)

    @Test
    void retrievesAKnownModelById() {
        // OpenAI's retrieve-model shape: a SINGLE model object (no list wrapper) —
        // same members as a list entry.
        HttpResponse<String> response =
                client.toBlocking().exchange(HttpRequest.GET("/v1/models/deepseek-v4-flash"), String.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        String body = response.body();
        assertTrue(body.contains("\"id\":\"deepseek-v4-flash\""), body);
        assertTrue(body.contains("\"object\":\"model\""), body);
        assertTrue(body.contains("\"owned_by\":\"deepseek\""), body);
        assertTrue(
                !body.contains("\"object\":\"list\""), "retrieve returns a single model object, not a list: " + body);
        assertTrue(!body.contains("\"data\""), body);
    }

    @Test
    void unknownModelIdIs404ModelNotFound() {
        // Real OpenAI: 404 invalid_request_error / code "model_not_found" — the exact
        // envelope UnknownModelException already maps to for chat; retrieve must agree
        // (an SDK probing capabilities reads the same error shape).
        HttpResponse<String> response = errorResponse(HttpRequest.GET("/v1/models/no-such-model"));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatus());
        String body = response.body();
        assertTrue(body.contains("\"invalid_request_error\""), body);
        assertTrue(body.contains("model_not_found"), body);
        assertTrue(body.contains("no-such-model"), "the unknown id is echoed in the message: " + body);
    }

    @Test
    void retrieveIsMeteredOnTheOpenAiFaceBothLegs() {
        client.toBlocking().exchange(HttpRequest.GET("/v1/models/deepseek-v4-pro"), String.class);
        errorResponse(HttpRequest.GET("/v1/models/no-such-model"));

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0"),
                "a successful retrieve must be metered 2xx:\n" + scrape);
        assertTrue(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"4xx\"} 1.0"),
                "a 404 retrieve must be metered 4xx (catch-mirrors-the-handler):\n" + scrape);
    }

    /** 4xx responses surface as {@code HttpClientResponseException} — read the body out. */
    private HttpResponse<String> errorResponse(HttpRequest<?> request) {
        io.micronaut.http.client.exceptions.HttpClientResponseException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        io.micronaut.http.client.exceptions.HttpClientResponseException.class,
                        () -> client.toBlocking().exchange(request, String.class));
        HttpResponse<?> response = exception.getResponse();
        return HttpResponse.status(response.getStatus())
                .body(response.getBody(String.class).orElse(""));
    }
}
