package io.amscotti.janus.gateway;

import io.amscotti.janus.router.DecisionRouter;
import io.amscotti.janus.store.PriceTable;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import java.util.Objects;

/**
 * Decision-plane boot probe: the decision router and the shared price table are
 * lazy Micronaut singletons, so without this listener a broken decision config
 * (unknown provider, blank base URL, invalid `wire-format`, missing
 * {@code require-priced} row) would pass {@code /health} and only fail on the
 * first {@code POST /v1/systemone} — often after a probe-gated rollout has
 * replaced healthy pods. Injecting both beans here forces their construction
 * during startup, and the per-alias price read forces {@code require-priced}
 * validation for decision aliases.
 *
 * <p>Same shape as {@link StoreBootProbe}: an {@link ApplicationEventListener}
 * on {@link ServerStartupEvent}, so a raw test context without an embedded
 * server does not fire it.
 */
@Singleton
final class DecisionBootProbe implements ApplicationEventListener<ServerStartupEvent> {

    private final DecisionRouter decisionRouter;
    private final PriceTable priceTable;

    DecisionBootProbe(DecisionRouter decisionRouter, PriceTable priceTable) {
        this.decisionRouter = Objects.requireNonNull(decisionRouter, "decisionRouter");
        this.priceTable = Objects.requireNonNull(priceTable, "priceTable");
    }

    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        // Touching the router forces routes/adapters/credentials to resolve; the
        // factory's validation throws with the offending entry/block.
        decisionRouter.models();
        for (String alias : decisionRouter.models()) {
            // UnpricedModelException (store) fails the node when require-priced is
            // on; without the flag this is a cheap map read (and logs an unpriced
            // alias once, which is useful inventory).
            priceTable.rateFor(alias);
        }
    }
}
