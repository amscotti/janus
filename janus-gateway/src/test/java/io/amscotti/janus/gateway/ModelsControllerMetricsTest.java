package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.gateway.dto.ModelsResponse;
import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.Router;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@code /v1/models} failure leg, unit-level (direct construction, no
 * Micronaut context): a throwing {@code router.models/route} is caught by the
 * controller and recorded in the coarse 5xx bucket <em>before</em> rethrowing (the
 * catch-mirrors-the-handler pattern), so a failing models request is never silently
 * absent from the {@code openai} series. The success leg is pinned by
 * {@link ModelsControllerTest} (integration).
 */
class ModelsControllerMetricsTest {

    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);

    @BeforeEach
    void reset() {
        registry.clear();
    }

    @Test
    void throwingBackendNameIsMeteredInThe5xxBucketAndPropagates() {
        ChatBackend failing = new ChatBackend() {
            @Override
            public String name() {
                throw new IllegalStateException("broken backend");
            }

            @Override
            public String baseUrl() {
                return "http://fake/deepseek";
            }

            @Override
            public ChatResponse complete(ChatRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Stream<StreamChunk> stream(ChatRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        Router router = new Router(Map.of("deepseek-v4-flash", failing));
        ModelsController controller = new ModelsController(router, new MicrometerMetricsRecorder(registry));

        // The controller rethrows unchanged — the exception handler produces the true
        // envelope (recording never alters the request path).
        IllegalStateException e = assertThrows(IllegalStateException.class, controller::models);
        assertEquals("broken backend", e.getMessage());

        String scrape = registry.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"5xx\"} 1.0"),
                "a failing models request must land in the openai 5xx bucket:\n" + scrape);
        assertTrue(
                scrape.contains("janus_request_duration_seconds_count{face=\"openai\"} 1"),
                "the latency histogram must record the failure:\n" + scrape);
    }

    @Test
    void successIsRecordedExactlyOnceInThe2xxBucket() {
        Router router = new Router(Map.of("deepseek-v4-flash", new ChatBackend() {
            @Override
            public String name() {
                return "deepseek";
            }

            @Override
            public String baseUrl() {
                return "http://fake/deepseek";
            }

            @Override
            public ChatResponse complete(ChatRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Stream<StreamChunk> stream(ChatRequest request) {
                throw new UnsupportedOperationException();
            }
        }));
        ModelsController controller = new ModelsController(router, new MicrometerMetricsRecorder(registry));

        HttpResponse<ModelsResponse> response = controller.models();

        assertEquals(HttpStatus.OK, response.getStatus());
        String scrape = registry.scrape();
        assertTrue(
                scrape.contains("janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0"),
                "a successful models request must be metered exactly once:\n" + scrape);
    }
}
