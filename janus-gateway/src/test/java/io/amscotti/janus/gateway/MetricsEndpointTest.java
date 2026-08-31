package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The Prometheus endpoint at {@code GET /metrics}
 * (the {@code [endpoints.prometheus] path = "/metrics"} override pins the
 * design decision away from Micronaut's default {@code /prometheus}. The exposition
 * text must be Prometheus {@code text/plain; version=0.0.4} with {@code # HELP} /
 * {@code # TYPE} lines (the micrometer-core JVM binders guarantee content even with
 * no Janus traffic yet — a bare scrape is never the "no series" empty case).
 */
@MicronautTest
class MetricsEndpointTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void metricsServesPrometheusTextFormat() {
        HttpResponse<String> http = client.toBlocking().exchange(HttpRequest.GET("/metrics"), String.class);

        assertEquals(HttpStatus.OK, http.getStatus());
        assertTrue(
                http.getContentType()
                        .map(t -> t.toString().startsWith("text/plain"))
                        .orElse(false),
                "content type must be text/plain (Prometheus exposition)");
        assertTrue(http.body().contains("# HELP"), "exposition must carry # HELP lines:\n" + http.body());
        assertTrue(http.body().contains("# TYPE"), "exposition must carry # TYPE lines:\n" + http.body());
    }

    @Test
    void defaultPrometheusPathIsPinnedAway() {
        HttpClientResponseException exception = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/prometheus"), String.class));
        assertEquals(
                HttpStatus.NOT_FOUND,
                exception.getResponse().getStatus(),
                "the default /prometheus route must be gone once path=/metrics is pinned");
    }
}
