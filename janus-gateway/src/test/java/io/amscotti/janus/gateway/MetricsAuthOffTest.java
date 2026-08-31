package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * step 4 (auth-off leg) — an unauthenticated request (no master key ⇒ the filter
 * is a passthrough, no {@code KeyRecord} attached) records the <b>unlabeled</b>
 * Tier-1 totals only: requests/status bucket + histogram + tokens in/out + cost, and
 * <b>never</b> a {@code janus_key_*} series (the per-key label requires a governing
 * key; the auth-off request has none). Governance is real here so the unlabeled
 * token/cost series are recorded at finalize (Tier-1 is always on, the reference
 * {@code PRIVACY.md}).
 */
@MicronautTest
@Property(name = "janus.test.governance", value = "true")
@Property(name = "janus.test.metrics", value = "true")
class MetricsAuthOffTest {

    @Inject
    @Client("/")
    HttpClient client;

    @BeforeEach
    void reset() {
        TestMetricsFactory.REGISTRY.clear();
    }

    @Test
    void authOffRequestRecordsUnlabeledSeriesOnly() {
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/chat/completions", openAiBody()).contentType(MediaType.APPLICATION_JSON),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());

        String scrape = TestMetricsFactory.REGISTRY.scrape();
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0");
        assertLine(scrape, "janus_request_duration_seconds_count{face=\"openai\"} 1");
        assertLine(scrape, "janus_tokens_in_total 10.0");
        assertLine(scrape, "janus_tokens_out_total 5.0");
        assertLine(scrape, "janus_cost_micro_usd_total 2800.0");
        assertFalse(scrape.contains("janus_key_"), "auth-off must never create per-key series:\n" + scrape);
    }

    private static String openAiBody() {
        return "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false,"
                + "\"max_tokens\":16}";
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static void assertLine(String scrape, String expected) {
        assertTrue(
                scrape.lines().anyMatch(line -> line.equals(expected)),
                "expected line: " + expected + "\nscrape:\n" + scrape);
    }
}
