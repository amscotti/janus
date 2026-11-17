package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Provider-module drift guard pinning {@link AbstractDecisionAdapter} default deadlines
 * (docs/architecture.md timeouts table) and {@link HttpSupport#MAX_RESPONSE_BODY_BYTES}
 * (64 MiB success-body OOM defense).
 */
class ProviderTimeoutContractTest {

    @Test
    void decisionAdapterDeadlinesAndResponseBodyCapMatchContract() {
        assertEquals(Duration.ofSeconds(10), AbstractDecisionAdapter.CONNECT_TIMEOUT);
        assertEquals(Duration.ofSeconds(60), AbstractDecisionAdapter.HEADER_ARRIVAL_TIMEOUT);
        assertEquals(Duration.ofSeconds(300), AbstractDecisionAdapter.DEFAULT_BODY_READ_TIMEOUT);
        assertEquals(64 * 1024 * 1024, HttpSupport.MAX_RESPONSE_BODY_BYTES);
    }
}
