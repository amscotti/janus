package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.annotation.Property;
import io.micronaut.core.order.Ordered;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Contract & drift-guard tests across {@link Face}, {@link KeyAuthFilter},
 * {@link DecisionEncodingFilter}, {@link GatewayExceptionHandler}, and all face
 * controllers.
 */
@MicronautTest
@Property(name = "janus.test.governance", value = "true")
@Property(name = "janus.test.metrics", value = "true")
class RouteAndFilterContractTest {

    @Inject
    KeyAuthFilter keyAuthFilter;

    @Inject
    DecisionEncodingFilter decisionEncodingFilter;

    @Inject
    GatewayExceptionHandler exceptionHandler;

    @Inject
    @Client("/")
    HttpClient client;

    @BeforeEach
    void clearMetrics() {
        TestMetricsFactory.REGISTRY.clear();
    }

    @Test
    void filterOrderingPinsKeyAuthSecurityBeforeDecisionEncoding() {
        assertTrue(keyAuthFilter instanceof Ordered, "KeyAuthFilter must implement Ordered");
        assertTrue(decisionEncodingFilter instanceof Ordered, "DecisionEncodingFilter must implement Ordered");
        assertEquals(ServerFilterPhase.SECURITY.order(), ((Ordered) keyAuthFilter).getOrder());
        assertEquals(ServerFilterPhase.SECURITY.after(), ((Ordered) decisionEncodingFilter).getOrder());
        assertTrue(
                ((Ordered) keyAuthFilter).getOrder() < ((Ordered) decisionEncodingFilter).getOrder(),
                "KeyAuthFilter must precede DecisionEncodingFilter");
    }

    @Test
    void trailingSlashAndMediaTypeParameterDelimiterEnforcedWithOpenAiEnvelopeAndMetrics() throws Exception {
        for (String path : List.of("/v1/systemone", "/v1/systemone/")) {
            TestMetricsFactory.REGISTRY.clear();
            HttpResponse<String> badType =
                    exchangeError(HttpRequest.POST(path, "{}").contentType("application/json-patch+json"));
            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, badType.getStatus(), path + ": " + badType.body());
            JsonNode envelope = GatewayJson.mapper().readTree(badType.body()).path("error");
            assertEquals(ErrorMapper.TYPE_INVALID_REQUEST, envelope.path("type").asString());
            assertTrue(envelope.path("message").asString().contains("Content-Type"), badType.body());
            assertEquals(1.0, systemOne4xxCount(), "415 must record janus_requests_total{face=systemone,status=4xx}");

            TestMetricsFactory.REGISTRY.clear();
            HttpResponse<String> badEncoding = exchangeError(HttpRequest.POST(path, "{}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Content-Encoding", "br"));
            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, badEncoding.getStatus(), path + ": " + badEncoding.body());
            assertEquals(1.0, systemOne4xxCount(), "415 bad encoding must record metrics on " + path);
        }

        // Non-POST requests must NOT be rejected with 415 by DecisionEncodingFilter.
        HttpResponse<String> getSystemOne =
                exchangeError(HttpRequest.GET("/v1/systemone").contentType(MediaType.TEXT_PLAIN_TYPE));
        assertNotEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, getSystemOne.getStatus());

        // Multiple or stacked Content-Encoding headers must be rejected cleanly by header parser.
        io.micronaut.http.simple.SimpleHttpHeaders stacked = new io.micronaut.http.simple.SimpleHttpHeaders(
                java.util.Map.of(), io.micronaut.core.convert.ConversionService.SHARED);
        stacked.add("Content-Encoding", "gzip");
        stacked.add("Content-Encoding", "zstd");
        assertTrue(DecisionEncodingFilter.parseSupportedEncoding(stacked).isEmpty());
    }

    @Test
    void faceRoutesAlignWithControllersExceptionHandlerAndFilters() {
        // 1. Collect all concrete HTTP paths declared by gateway @Controller classes.
        Set<String> modelPaths = new HashSet<>();
        for (Class<?> controllerClass : List.of(
                ChatCompletionsController.class,
                MessagesController.class,
                ResponsesController.class,
                SystemOneController.class)) {
            Controller ctrl = controllerClass.getAnnotation(Controller.class);
            String base = ctrl == null ? "" : ctrl.value();
            for (Method m : controllerClass.getDeclaredMethods()) {
                for (String sub : routeValues(m)) {
                    String normalized = KeyAuthFilter.normalizePath(base + sub);
                    // Exclude parametric /responses/{id} stub check from literal match and verify via Face.of
                    String probe = normalized.replace("{id}", "resp_123");
                    assertTrue(Face.of(probe).isPresent(), "Face enum must own controller route " + probe);
                    modelPaths.add(probe);
                }
            }
        }
        assertTrue(modelPaths.containsAll(List.of(
                "/v1/chat/completions",
                "/v1/messages",
                "/v1/messages/count_tokens",
                "/v1/responses",
                "/v1/systemone")));

        // 2. Verify GatewayExceptionHandler envelope parity for every Face.
        for (String path : modelPaths) {
            String rendered = exceptionHandler
                    .handle(HttpRequest.POST(path, ""), new io.amscotti.janus.store.UnpricedModelException("unknown"))
                    .body();
            if (Face.of(path).orElseThrow() == Face.ANTHROPIC) {
                assertTrue(
                        rendered.contains("\"type\":\"error\""),
                        "Anthropic face must render Anthropic error envelope on " + path + ": " + rendered);
            } else {
                assertTrue(
                        rendered.startsWith("{\"error\":{"),
                        "Non-Anthropic face must render OpenAI error envelope on " + path + ": " + rendered);
            }
        }

        // 3. Verify @Filter annotations cover normalized variants (e.g. "/**") rather than literal "/v1/systemone".
        for (Class<?> filterClass : List.of(KeyAuthFilter.class, DecisionEncodingFilter.class)) {
            Filter filter = filterClass.getAnnotation(Filter.class);
            List<String> patterns = Arrays.asList(filter.value());
            assertTrue(
                    patterns.contains("/**"),
                    filterClass.getSimpleName()
                            + " should match '/**' with normalizePath so trailing/double slashes cannot bypass it: "
                            + patterns);
        }
    }

    private static List<String> routeValues(Method m) {
        if (m.isAnnotationPresent(Post.class)) {
            return List.of(m.getAnnotation(Post.class).value());
        }
        if (m.isAnnotationPresent(Get.class)) {
            return List.of(m.getAnnotation(Get.class).value());
        }
        if (m.isAnnotationPresent(Delete.class)) {
            return List.of(m.getAnnotation(Delete.class).value());
        }
        if (m.isAnnotationPresent(Put.class)) {
            return List.of(m.getAnnotation(Put.class).value());
        }
        return List.of();
    }

    private double systemOne4xxCount() {
        var counter = TestMetricsFactory.REGISTRY
                .find(MicrometerMetricsRecorder.SERIES_REQUESTS)
                .tag("face", "systemone")
                .tag("status", "4xx")
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private HttpResponse<String> exchangeError(HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, String.class);
        } catch (HttpClientResponseException e) {
            @SuppressWarnings("unchecked")
            HttpResponse<String> response = (HttpResponse<String>) e.getResponse();
            return response;
        }
    }
}
