package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.provider.ProviderException;
import io.micronaut.http.HttpStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * real upstream error bodies → OpenAI envelopes: for each committed error fixture
 * (verbatim copies of the janus-core captures; the gateway test classpath cannot see
 * core *test* resources) assert the envelope vocabulary exists ({@code error.message}/
 * {@code error.type}/status), build the {@link ProviderException} of the README-declared
 * class (401 → {@code TYPE_AUTH}, 400 → {@code TYPE_UPSTREAM_4XX} with statusCode, 429 →
 * {@code TYPE_RATE_LIMITED}), and assert {@link ErrorMapper} yields the OpenAI
 * envelope (401 {@code authentication_error}, 429 {@code rate_limit_error},
 * upstream-status {@code api_error}) with the upstream {@code error.message} text
 * carried through the mapper's {@code redactSecrets} choke point. The adapter's
 * classification logic itself stays pinned by the provider loopback tests
 * (janus-provider, untouched); this test consumes the declared class.
 *
 * <p><b>Premise note.</b> This test pins the <em>mapper</em> contract — an
 * exception message built from real upstream text is redacted and forwarded verbatim.
 * The shipped adapters (<code>OpenAiCompatibleAdapter</code>/<code>AnthropicAdapter</code>)
 * deliberately construct generic messages and never place upstream body text in the
 * exception (pinned by their loopback tests, e.g. {@code completeErrorBodyNeverLeaksSecretIntoException}),
 * so the fixture text here is defense-in-depth for the redaction choke point, not the
 * adapter's actual message. The fixture messages carry no {@code sk-} shape, so the
 * redaction is a pass-through and the verbatim assertion is exact.
 */
class ErrorFixtureTest {

    /** README-declared classification per fixture (core fixture README table). */
    private record Fixture(
            String file, String providerType, Integer statusCode, HttpStatus expectedStatus, String expectedType) {}

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture(
                    "deepseek.401.json",
                    ProviderException.TYPE_AUTH,
                    401,
                    HttpStatus.UNAUTHORIZED,
                    ErrorMapper.TYPE_AUTHENTICATION_ERROR),
            new Fixture(
                    "deepseek.400.json",
                    ProviderException.TYPE_UPSTREAM_4XX,
                    400,
                    HttpStatus.BAD_REQUEST,
                    ErrorMapper.TYPE_API_ERROR),
            new Fixture(
                    "deepseek.429.json",
                    ProviderException.TYPE_RATE_LIMITED,
                    429,
                    HttpStatus.TOO_MANY_REQUESTS,
                    ErrorMapper.TYPE_RATE_LIMIT_ERROR),
            // The Anthropic 400 (invalid_request_error, unknown model) — the same
            // upstream-4xx row as the OpenAI 400, now pinned against an Anthropic-shaped
            // body (copied verbatim from the janus-core anthropic corpus).
            new Fixture(
                    "anthropic.400.json",
                    ProviderException.TYPE_UPSTREAM_4XX,
                    400,
                    HttpStatus.BAD_REQUEST,
                    ErrorMapper.TYPE_API_ERROR));

    private final ErrorMapper mapper = new ErrorMapper();

    @Test
    void realErrorBodiesMapToTheDeclaredOpenAiEnvelopes() throws Exception {
        for (Fixture fixture : FIXTURES) {
            JsonNode error = errorObject(fixture.file());
            assertNotNull(error.get("message"), fixture.file());
            assertNotNull(error.get("type"), fixture.file());
            assertTrue(error.get("message").asString().length() > 0, fixture.file() + " message must be non-blank");
            assertTrue(error.get("type").asString().length() > 0, fixture.file() + " type must be non-blank");

            ProviderException provider = new ProviderException(
                    fixture.providerType(), error.get("message").asString(), fixture.statusCode(), null);
            ErrorMapper.ErrorMapping mapping = mapper.map(provider);

            assertEquals(fixture.expectedStatus(), mapping.status(), fixture.file());
            assertEquals(fixture.expectedType(), mapping.envelope().type(), fixture.file());
            // The mapper carries the upstream error text through its redaction choke
            // point (redactSecrets is a pass-through for these fixture messages, which
            // carry no sk- shape — so the envelope text is the fixture text verbatim).
            assertEquals(
                    ErrorMapper.redactSecrets(error.get("message").asString()),
                    mapping.envelope().message(),
                    fixture.file());
        }
    }

    /** {@code {"error": {...}}} envelope vocabulary per fixture. */
    private static JsonNode errorObject(String file) throws IOException {
        try (InputStream in = ErrorFixtureTest.class.getResourceAsStream("/fixtures/errors/" + file)) {
            assertNotNull(in, "fixture /fixtures/errors/" + file + " missing");
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = GatewayJson.mapper().readTree(body);
            return root.get("error");
        }
    }
}
