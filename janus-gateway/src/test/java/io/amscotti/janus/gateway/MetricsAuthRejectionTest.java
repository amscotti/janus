package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Auth <b>on</b> (master key set): filter-level rejections never reach the
 * controllers, so {@link KeyAuthFilter} records them itself — a missing/invalid key
 * on either face lands in {@code janus_requests_total{face,status="4xx"}} with no
 * per-key label (Tier-1 always on, even for rejected requests).
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
@Property(name = "janus.test.metrics", value = "true")
@Property(name = "janus.test.governance", value = "true")
class MetricsAuthRejectionTest {

    @Inject
    @Client("/")
    HttpClient client;

    @BeforeEach
    void reset() {
        TestMetricsFactory.REGISTRY.clear();
    }

    @Test
    void missingKeyOnOpenAiFaceRecords401Bucket() {
        HttpClientResponseException e = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/v1/chat/completions", "{}").contentType(MediaType.APPLICATION_JSON),
                                String.class));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"4xx\"} 1.0"),
                "filter 401 must be recorded:\n" + scrape);
        assertFalse(scrape.contains("janus_key_"), "a rejected request has no key label:\n" + scrape);
    }

    @Test
    void missingKeyOnAnthropicFaceRecords401Bucket() {
        HttpClientResponseException e = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/v1/messages", "{}").contentType(MediaType.APPLICATION_JSON),
                                String.class));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"anthropic\",status=\"4xx\"} 1.0"),
                "filter 401 must be recorded on the anthropic face:\n" + scrape);
        assertFalse(scrape.contains("janus_key_"), "a rejected request has no key label:\n" + scrape);
    }

    @Test
    void adminRouteRejectionRecordsAdminFaceNotOpenAi() {
        // A master-key rejection on /key/* never touched a model face — it must be
        // recorded under face="admin", not a fabricated "openai".
        HttpClientResponseException e = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/key/generate", "{}").contentType(MediaType.APPLICATION_JSON),
                                String.class));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"admin\",status=\"4xx\"} 1.0"),
                "an admin-route rejection must be recorded on the admin face:\n" + scrape);
        assertFalse(scrape.contains("face=\"openai\""), "a /key/* rejection is not an OpenAI-face request:\n" + scrape);
        assertFalse(scrape.contains("janus_key_"), "a rejected request has no key label:\n" + scrape);
    }

    @Test
    void wrongKeyModelRouteYieldsOneMetric4xxAndNoCallRecord() {
        // Documented decision: a pre-dispatch auth denial is a middleware
        // rejection, not a call — the filter records exactly one 4xx metric entry, and
        // the call ledger is deliberately NOT written (Governance.recordFailure is
        // controller-reachable only). Pins the documented asymmetry: the series counts
        // the 4xx, the calls table has no row.
        int callsBefore = TestGovernanceFactory.CALLS.recentCalls(1000).size();
        HttpClientResponseException e = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/v1/chat/completions", "{}")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("x-api-key", "sk-janus-abc-0123456789abcdef0123456789abcdef"),
                                String.class));
        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"4xx\"} 1.0"),
                "a wrong-key model-route request must be recorded exactly once in the 4xx bucket:\n" + scrape);
        assertFalse(scrape.contains("janus_requests_total{face=\"openai\",status=\"2xx\"}"), scrape);

        assertEquals(
                callsBefore,
                TestGovernanceFactory.CALLS.recentCalls(1000).size(),
                "a pre-dispatch auth denial must not write a call-ledger row (documented decision)");
    }
}
