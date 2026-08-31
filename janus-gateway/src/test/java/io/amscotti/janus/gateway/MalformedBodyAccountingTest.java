package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.store.CallRecord;
import io.amscotti.janus.store.CallStatus;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The accounting side of a JSON-literal {@code null} (and empty) request
 * body on both chat faces: the codec used to NPE on the null DTO, so the gateway
 * answered 500 {@code api_error} with a 5xx metric bucket and an {@code ERROR_INTERNAL}
 * call-ledger row for purely client-malformed input. Both faces must 400
 * {@code invalid_request_error} with no 5xx bucket and no {@code ERROR_INTERNAL} row
 * (the correct client-error row, if any, is the 4xx class).
 */
@MicronautTest
@Property(name = "janus.test.metrics", value = "true")
@Property(name = "janus.test.governance", value = "true")
class MalformedBodyAccountingTest {

    @Inject
    @Client("/")
    HttpClient client;

    @BeforeEach
    void reset() {
        TestMetricsFactory.REGISTRY.clear();
    }

    @Test
    void openAiNullAndEmptyBodyAre400WithNo5xxBucketAndNoInternalCallRow() {
        assertMalformedRejected(HttpRequest.POST("/v1/chat/completions", "null"), "openai");
        assertMalformedRejected(HttpRequest.POST("/v1/chat/completions", ""), "openai");

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"4xx\"}"),
                "malformed bodies are client 400s on the openai face:\n" + scrape);
        assertFalse(
                scrape.contains("face=\"openai\",status=\"5xx\""),
                "a null body must never land in the openai 5xx bucket:\n" + scrape);
    }

    @Test
    void anthropicNullAndEmptyBodyAre400WithNo5xxBucketAndNoInternalCallRow() {
        assertMalformedRejected(HttpRequest.POST("/v1/messages", "null"), "anthropic");
        assertMalformedRejected(HttpRequest.POST("/v1/messages", ""), "anthropic");

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"anthropic\",status=\"4xx\"}"),
                "malformed bodies are client 400s on the anthropic face:\n" + scrape);
        assertFalse(
                scrape.contains("face=\"anthropic\",status=\"5xx\""),
                "a null body must never land in the anthropic 5xx bucket:\n" + scrape);
    }

    /**
     * POST the body (auth-off — no key, so no 429 interference), assert the 400
     * envelope, and assert no {@code ERROR_INTERNAL} call-ledger row was added.
     */
    private void assertMalformedRejected(io.micronaut.http.MutableHttpRequest<?> request, String face) {
        List<CallRecord> before = TestGovernanceFactory.CALLS.recentCalls(1000);

        HttpClientResponseException e = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(request.contentType(MediaType.APPLICATION_JSON), String.class));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus(), face);
        assertTrue(
                e.getResponse().getBody(String.class).orElse("").contains("\"type\":\"invalid_request_error\""),
                face + " body → " + e.getResponse().getBody(String.class).orElse(""));

        List<CallRecord> added = TestGovernanceFactory.CALLS.recentCalls(1000).stream()
                .filter(record -> !before.contains(record))
                .toList();
        assertFalse(
                added.stream().anyMatch(record -> record.status() == CallStatus.ERROR_INTERNAL),
                "a client-malformed body must never record ERROR_INTERNAL: " + added);
    }
}
