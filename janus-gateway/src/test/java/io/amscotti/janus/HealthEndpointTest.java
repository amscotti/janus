package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** Management health endpoint is up when the gateway boots. */
@MicronautTest
class HealthEndpointTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void healthReturnsUp() {
        HttpResponse<String> response = client.toBlocking().exchange(HttpRequest.GET("/health"), String.class);
        assertEquals(HttpStatus.OK, response.getStatus());
        String body = response.body();
        // Micronaut 5 health status is "healthy"; older versions used "UP".
        assertTrue(
                body != null && (body.contains("healthy") || body.contains("UP") || body.contains("GREEN")),
                "expected health body to indicate healthy/UP, got: " + body);
    }
}
