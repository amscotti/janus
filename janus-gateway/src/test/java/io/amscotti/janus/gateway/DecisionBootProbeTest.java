package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.janus.router.DecisionRouter;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import io.amscotti.janus.store.UnpricedModelException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The startup probe is the eager half of the decision config contract. */
class DecisionBootProbeTest {

    @Test
    void unpricedDecisionAliasRefusesBoot() {
        DecisionRouter router =
                new DecisionRouter(Map.of("jev-latest", List.of(new FakeDecisionBackend("fake-decision"))));
        DecisionBootProbe probe = new DecisionBootProbe(router, PriceTable.of(Map.of(), true));

        assertThrows(UnpricedModelException.class, () -> probe.onApplicationEvent(null));
    }

    @Test
    void pricedDecisionAliasPasses() {
        DecisionRouter router =
                new DecisionRouter(Map.of("jev-latest", List.of(new FakeDecisionBackend("fake-decision"))));
        DecisionBootProbe probe = new DecisionBootProbe(
                router, PriceTable.of(Map.of("jev-latest", new PricingRate(0.14, 0.0, 4096)), true));

        assertDoesNotThrow(() -> probe.onApplicationEvent(null));
    }
}
