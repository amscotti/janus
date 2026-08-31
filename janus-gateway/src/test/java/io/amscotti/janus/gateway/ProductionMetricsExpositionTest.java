package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The <b>production</b>-DI metrics exposition test:
 * the one {@code @MicronautTest} that does <b>not</b> replace {@link MetricsFactory} or
 * {@link RouterFactory} ({@code janus.test.production-factories=true} opts the test
 * factories out), so the real composition runs: the production {@code MetricsFactory}
 * puts the recorder on the auto-configured {@code MeterRegistry} (the same one
 * {@code GET /metrics} scrapes) and registers the health/breaker gauges from the
 * {@link RouterResilience} bean {@code router} populated. A real request through a
 * real adapter against a local HTTP fake (the committed golden body with the pinned
 * 14/12 usage, served over a real socket) must then surface <b>every</b> Tier-1 series
 * in the HTTP exposition — the "recorded meter → scraped text" property
 * as manual-native-smoke-only.
 *
 * <p>Also the "latency histogram" wording decision: the
 * {@code janus_request_duration_seconds} Timer publishes percentile-histogram
 * {@code _bucket} lines (not just the count/sum/max summary), so the design's "latency
 * histogram" wording is literally satisfied — pinned here on the live exposition.
 *
 * <p>The router config (model list + base URL) is supplied via {@code @Property}
 * annotations; the fake upstream binds the fixed {@link #FAKE_PORT} (compile-time
 * constant — see the field's javadoc for why).
 */
@MicronautTest
@Property(name = "janus.test.production-factories", value = "true")
@Property(name = "janus.test.master-key", value = "test-master-key-000")
@Property(name = "janus.test.governance", value = "true")
@Property(name = "janus.model-list[0].name", value = "deepseek-v4-flash")
@Property(name = "janus.model-list[0].provider", value = "openai-compatible")
@Property(name = "janus.model-list[0].base-url", value = "http://127.0.0.1:18543")
// with the test governance factory opted out (production-factories=true), the
// PRODUCTION GovernanceFactory reads the pricing table from config — set the rows
// so the exact-cost series land (5320 micro = 14×0.14 + 12×0.28 per 1K).
@Property(name = "janus.pricing.models[0].name", value = "deepseek-v4-flash")
@Property(name = "janus.pricing.models[0].input-per-1k", value = "0.14")
@Property(name = "janus.pricing.models[0].output-per-1k", value = "0.28")
@Property(name = "janus.pricing.models[0].default-max-tokens", value = "4096")
@Property(name = "janus.limits.window", value = "fixed")
class ProductionMetricsExpositionTest {

    /** The committed golden usage ( chat.response.json): 14 prompt / 12 completion. */
    private static final String GOLDEN_BODY = """
            {
              "id": "chatcmpl-9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a",
              "object": "chat.completion",
              "created": 1785715200,
              "model": "deepseek-v4-flash",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "The weather in Paris is 18 degrees with light rain."
                  },
                  "logprobs": null,
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 14,
                "completion_tokens": 12,
                "total_tokens": 26
              },
              "system_fingerprint": "fp_3a5770e1b4"
            }
            """;

    private static HttpServer fake;
    private static final AtomicInteger FAKE_HITS = new AtomicInteger();

    /**
     * Fixed port for the in-suite fake upstream. {@code @Property} values are
     * compile-time constants, and system-property / placeholder indirection does not
     * reach the test context's config binding, so the fake binds this exact port (a
     * distinctive high port; the fail-loudly retry below keeps a stray collision
     * visible instead of flaky). The gate's own drills use free ports.
     */
    private static final int FAKE_PORT = 18543;

    static {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                fake = HttpServer.create(new InetSocketAddress("127.0.0.1", FAKE_PORT), 0);
                // Adapter posts {base}/v1/chat/completions (OpenAI-compatible path).
                fake.createContext("/v1/chat/completions", exchange -> {
                    FAKE_HITS.incrementAndGet();
                    exchange.getRequestBody().readAllBytes(); // drain (auth header checked by the gateway, not us)
                    byte[] body = GOLDEN_BODY.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
                fake.start();
                break;
            } catch (IOException e) {
                if (attempt == 4) {
                    throw new ExceptionInInitializerError(
                            "fake upstream port " + FAKE_PORT + " is busy after 5 attempts — "
                                    + "another process is squatting the test port (see "
                                    + "ProductionMetricsExpositionTest.FAKE_PORT)");
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ExceptionInInitializerError(ie);
                }
            }
        }
    }

    @Inject
    @Client("/")
    HttpClient client;

    @BeforeEach
    void reset() {
        FAKE_HITS.set(0);
    }

    @AfterAll
    static void tearDown() {
        fake.stop(0);
    }

    @Test
    void productionWiringServesEveryTier1SeriesOverHttp() {
        // Master key → virtual key (auth on; with the test factories opted out, the
        // PRODUCTION MasterKeyProvider reads the JANUS_MASTER_KEY env — the
        // two-node wiring — falling back to the property value for local runs).
        String masterKey = System.getenv().getOrDefault("JANUS_MASTER_KEY", "test-master-key-000");
        String generate = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[\"deepseek-v4-flash\"]}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", masterKey),
                        String.class)
                .body();
        // sk-janus-<prefix>-<secret>; key_id is the 6th comma-delimited field of the
        // generated JSON — parse minimally (no DTO binding in this test).
        String fullKey = extract(generate, "\"key\"");
        String keyId = extract(generate, "\"key_id\"");

        HttpResponse<String> chat = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/v1/chat/completions",
                                        "{\"model\":\"deepseek-v4-flash\",\"messages\":[{\"role\":\"user\","
                                                + "\"content\":\"w33 production-di exposition\"}]}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", fullKey),
                        String.class);
        assertEquals(HttpStatus.OK, chat.getStatus());
        assertEquals(1, FAKE_HITS.get(), "the request must have been dispatched to the fake upstream");

        HttpResponse<String> metrics = client.toBlocking().exchange(HttpRequest.GET("/metrics"), String.class);
        assertEquals(HttpStatus.OK, metrics.getStatus());
        String scrape = metrics.body();

        // Requests + latency histogram: count/sum/max AND _bucket lines.
        assertLine(scrape, "janus_requests_total{face=\"openai\",status=\"2xx\"} 1.0");
        assertLine(scrape, "janus_request_duration_seconds_count{face=\"openai\"} 1");
        assertTrue(
                scrape.contains("janus_request_duration_seconds_bucket{face=\"openai\""),
                "the latency histogram must publish _bucket lines:\n" + scrape);
        assertTrue(
                scrape.contains("janus_request_duration_seconds_max{face=\"openai\"}"),
                "the latency histogram must publish _max:\n" + scrape);

        // Tokens + exact cost (14×0.14 + 12×0.28 = 5 320 micro-USD — the design 2 math).
        assertLine(scrape, "janus_tokens_in_total 14.0");
        assertLine(scrape, "janus_tokens_out_total 12.0");
        assertLine(scrape, "janus_cost_micro_usd_total 5320.0");

        // Per-key series (the keyed request labels them with the opaque key id).
        assertLine(scrape, "janus_key_requests_total{key_id=\"" + keyId + "\"} 1.0");
        assertLine(scrape, "janus_key_tokens_in_total{key_id=\"" + keyId + "\"} 14.0");
        assertLine(scrape, "janus_key_tokens_out_total{key_id=\"" + keyId + "\"} 12.0");
        assertLine(scrape, "janus_key_cost_micro_usd_total{key_id=\"" + keyId + "\"} 5320.0");

        // Health/breaker gauges from the RouterResilience bean:
        // the production RouterFactory ran, so the backend label is the adapter's name
        // plus its base URL (the per-instance identity — labels sorted A-Z).
        assertLine(
                scrape,
                "janus_upstream_healthy{base_url=\"http://127.0.0.1:18543\",provider=\"openai-compatible\"} 1.0");
        assertLine(
                scrape,
                "janus_upstream_breaker_state{base_url=\"http://127.0.0.1:18543\",provider=\"openai-compatible\"} 0.0");
    }

    /** Pull the value of a JSON string field (no DTO binding in this test). */
    private static String extract(String json, String field) {
        // field is already quoted (e.g. "\"key\""); append the colon + opening quote
        // of the value: "key":"<value>...
        String marker = field + ":\"";
        int start = json.indexOf(marker);
        assertTrue(start >= 0, "missing " + field + " in " + json);
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static void assertLine(String scrape, String expected) {
        assertTrue(
                scrape.lines().anyMatch(line -> line.equals(expected)),
                "expected line: " + expected + "\nscrape:\n" + scrape);
    }
}
