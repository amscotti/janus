package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.provider.AnthropicAdapter;
import io.amscotti.janus.provider.OpenAiCompatibleAdapter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Drift guard for the timeouts table in {@code docs/architecture.md} ("Timeouts
 * (operator view)"): connect 10 s, header-arrival 60 s, non-stream body-read 300 s,
 * stream-idle 60 s. All four deadlines are compile-time constants in the adapters and
 * SSE publishers; if any constant changes, this test fails and the table must be
 * updated in the same change — the same role {@code ManagementEndpointExposureTest}
 * plays for the endpoint surface.
 *
 * <p>Since the {@code [janus.timeouts]} section made the deadlines
 * operator-tunable, the guard also pins that {@link JanusConfig.TimeoutsConfig#DEFAULTS}
 * reproduces the constants <b>exactly</b> — the absent-config boot keeps the
 * documented deadlines byte-identically, and the config override path is the only
 * way the effective deadlines deviate from the pinned constants (all nine are
 * re-pinned here, including {@code ResponsesSsePublisher}, which the pre-guard
 * missed).
 */
class TimeoutContractTest {

    @Test
    void adapterDeadlinesMatchTheDocumentedTimeoutsTable() {
        assertEquals(Duration.ofSeconds(10), OpenAiCompatibleAdapter.CONNECT_TIMEOUT);
        assertEquals(Duration.ofSeconds(60), OpenAiCompatibleAdapter.HEADER_ARRIVAL_TIMEOUT);
        assertEquals(Duration.ofSeconds(300), OpenAiCompatibleAdapter.DEFAULT_BODY_READ_TIMEOUT);
        assertEquals(Duration.ofSeconds(10), AnthropicAdapter.CONNECT_TIMEOUT);
        assertEquals(Duration.ofSeconds(60), AnthropicAdapter.HEADER_ARRIVAL_TIMEOUT);
        assertEquals(Duration.ofSeconds(300), AnthropicAdapter.DEFAULT_BODY_READ_TIMEOUT);
    }

    @Test
    void streamIdleDeadlineMatchesTheDocumentedTimeoutsTable() {
        assertEquals(60, SseChunkPublisher.DEFAULT_IDLE_TIMEOUT_SECONDS);
        assertEquals(60, AnthropicSsePublisher.DEFAULT_IDLE_TIMEOUT_SECONDS);
        assertEquals(60, ResponsesSsePublisher.DEFAULT_IDLE_TIMEOUT_SECONDS);
    }

    /**
     * The config defaults ARE the pinned constants: {@code [janus.timeouts]}
     * {@code DEFAULTS} equals (10, 60, 300, 60) — the adapter constants above and
     * the three publishers' idle constants. An absent section or key resolves to
     * exactly these values, so an operator who never configures timeouts runs the
     * documented code constants, and any deviation from them requires the explicit
     * config override path ({@code RouterFactory.resolve}).
     */
    @Test
    void timeoutsDefaultsReproduceThePinnedConstantsExactly() {
        assertEquals(
                OpenAiCompatibleAdapter.CONNECT_TIMEOUT,
                Duration.ofSeconds(JanusConfig.TimeoutsConfig.DEFAULTS.connectTimeoutSeconds()));
        assertEquals(
                OpenAiCompatibleAdapter.HEADER_ARRIVAL_TIMEOUT,
                Duration.ofSeconds(JanusConfig.TimeoutsConfig.DEFAULTS.headerTimeoutSeconds()));
        assertEquals(
                OpenAiCompatibleAdapter.DEFAULT_BODY_READ_TIMEOUT,
                Duration.ofSeconds(JanusConfig.TimeoutsConfig.DEFAULTS.bodyReadTimeoutSeconds()));
        assertEquals(
                AnthropicAdapter.CONNECT_TIMEOUT,
                Duration.ofSeconds(JanusConfig.TimeoutsConfig.DEFAULTS.connectTimeoutSeconds()));
        assertEquals(
                AnthropicAdapter.HEADER_ARRIVAL_TIMEOUT,
                Duration.ofSeconds(JanusConfig.TimeoutsConfig.DEFAULTS.headerTimeoutSeconds()));
        assertEquals(
                AnthropicAdapter.DEFAULT_BODY_READ_TIMEOUT,
                Duration.ofSeconds(JanusConfig.TimeoutsConfig.DEFAULTS.bodyReadTimeoutSeconds()));
        assertEquals(
                SseChunkPublisher.DEFAULT_IDLE_TIMEOUT_SECONDS,
                JanusConfig.TimeoutsConfig.DEFAULTS.streamIdleTimeoutSeconds().longValue());
        assertEquals(
                AnthropicSsePublisher.DEFAULT_IDLE_TIMEOUT_SECONDS,
                JanusConfig.TimeoutsConfig.DEFAULTS.streamIdleTimeoutSeconds().longValue());
        assertEquals(
                ResponsesSsePublisher.DEFAULT_IDLE_TIMEOUT_SECONDS,
                JanusConfig.TimeoutsConfig.DEFAULTS.streamIdleTimeoutSeconds().longValue());
    }
}
