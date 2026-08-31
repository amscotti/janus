package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression pin: the shipped {@code application.toml} must expose
 * exactly the public surface — {@code GET /health*}, {@code GET /metrics},
 * {@code GET /v1/models} — and nothing else, <b>unauthenticated</b> (this test runs
 * auth-off, the packaged default). A shipped {@code [endpoints.all] sensitive =
 * false} would flip the whole Micronaut management surface open: {@code POST /stop}
 * (remote shutdown), {@code POST /refresh}, and {@code GET /env|/beans|/routes|
 * /loggers|/threaddump|/info}. The {@link KeyAuthFilter} treats every unlisted path
 * as exempt, so no master key closes that hole — only the endpoint config does.
 *
 * <p>Asserts the hardened surface directly (real routes, not route registration):
 * health + metrics + models return 200, and every management endpoint outside that
 * set is either 401 ({@code sensitive = true} → the framework's {@code EndpointsFilter}
 * rejects without a security module) or 404 (stop/refresh are disabled outright as
 * belt-and-suspenders). Modeled on {@code UnknownPath404Test} / {@code MetricsEndpointTest}.
 */
@MicronautTest
class ManagementEndpointExposureTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void unauthenticatedSurfaceIsExactlyHealthMetricsAndModels() {
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.GET("/health")));
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.GET("/health/readiness")));
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.GET("/health/liveness")));
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.GET("/metrics")));
        assertEquals(HttpStatus.OK, statusOf(HttpRequest.GET("/v1/models")));
    }

    @Test
    void everyOtherManagementEndpointIsLockedDown() {
        List<HttpRequest<?>> locked = List.of(
                HttpRequest.POST("/stop", ""),
                HttpRequest.POST("/refresh", ""),
                HttpRequest.GET("/env"),
                HttpRequest.GET("/beans"),
                HttpRequest.GET("/routes"),
                HttpRequest.GET("/loggers"),
                HttpRequest.GET("/threaddump"),
                HttpRequest.GET("/info"));
        for (HttpRequest<?> request : locked) {
            HttpStatus status = statusOf(request);
            assertTrue(
                    status == HttpStatus.UNAUTHORIZED || status == HttpStatus.NOT_FOUND,
                    request.getMethod() + " " + request.getUri() + " must not be reachable unauthenticated (got "
                            + status + ")");
        }
    }

    /** The status of a request, folding Micronaut's client exception into its response status. */
    private HttpStatus statusOf(HttpRequest<?> request) {
        try {
            HttpResponse<String> response = client.toBlocking().exchange(request, String.class);
            return response.getStatus();
        } catch (HttpClientResponseException e) {
            return e.getStatus();
        }
    }
}
