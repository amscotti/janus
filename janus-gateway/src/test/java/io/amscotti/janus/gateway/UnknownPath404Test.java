package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The "unknown paths get Micronaut's default 404 (not an envelope)" claim,
 * pinned through the live server with <b>auth off</b> (the default): a genuinely
 * unmatched path 404s with the framework default body — never the OpenAI
 * {@code {"error": …}} wrapper, never the Anthropic {@code {"type":"error", …}}
 * payload that {@link GatewayExceptionHandler} would render for a routed exception.
 * The {@code ExceptionHandler<Throwable, …>} bean is the widest net in the app, so the
 * no-envelope guarantee is a deliberate pin. The auth-on leg is
 * {@link UnknownPathAuthOn404Test}.
 */
@MicronautTest
class UnknownPath404Test {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void unknownPathGetsMicronautDefault404NotAnEnvelope() {
        HttpClientResponseException e = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/v1/does-not-exist"), String.class));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());

        String body = e.getResponse().getBody(String.class).orElse("");
        assertFalse(body.contains("\"error\":{"), "no OpenAI envelope wrapper on an unknown path: " + body);
        assertFalse(body.contains("\"type\":\"error\""), "no Anthropic error payload on an unknown path: " + body);
        assertTrue(
                body.isBlank() || !body.contains("\"error\""), "no error key of any shape on an unknown path: " + body);
    }
}
