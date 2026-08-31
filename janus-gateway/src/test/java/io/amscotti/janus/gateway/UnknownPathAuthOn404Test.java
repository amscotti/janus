package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The auth-on leg of the unknown-path pin: even with a master key set, an
 * unmatched path passes the {@link KeyAuthFilter} (its protected set is exact-match on
 * the model/admin routes only) and 404s with Micronaut's default body, never an
 * envelope. The auth-off leg is {@link UnknownPath404Test}.
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
class UnknownPathAuthOn404Test {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void unknownPathWithMasterKeyStillGetsDefault404NotAnEnvelope() {
        HttpClientResponseException e = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(
                                HttpRequest.GET("/v1/does-not-exist")
                                        .header("Authorization", "Bearer test-master-key-000"),
                                String.class));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());

        String body = e.getResponse().getBody(String.class).orElse("");
        assertFalse(body.contains("\"error\":{"), "no OpenAI envelope wrapper on an unknown path: " + body);
        assertFalse(body.contains("\"type\":\"error\""), "no Anthropic error payload on an unknown path: " + body);
    }
}
