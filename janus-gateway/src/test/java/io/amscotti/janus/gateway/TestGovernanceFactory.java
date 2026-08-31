package io.amscotti.janus.gateway;

import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.InMemoryCallStore;
import io.amscotti.janus.store.InMemorySpendLedger;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import io.amscotti.janus.store.RateLimiter;
import io.amscotti.janus.store.TokenBucketRateLimiter;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Map;

/**
 * shared gateway test composition root (the {@link TestKeyAuthFactory} pattern):
 * replaces the production {@link GovernanceFactory} with one whose {@link Governance}
 * is either {@link Governance#noop} (the default — every existing suite that does
 * not opt in gets the limits-off instance the plan promises, so keyless
 * behavior is byte-identical) or a real Governance built from <b>fixed-clock</b>
 * components when a class opts in with {@value #ENABLED_PROPERTY}:
 *
 * <ul>
 * <li>a {@link FixedWindowRateLimiter} on the shared {@link TestKeyAuthFactory}
 * fixed clock (or a {@link TokenBucketRateLimiter} via {@value #WINDOW_PROPERTY});
 * <li>a {@link PriceTable} with DeepSeek rates (0.14/0.28 — the gate config's
 * published rates, so the exact micro-USD assertions are the design's);
 * <li>the shared {@link #LEDGER} (tests assert {@code spendByKey}/{@code recent}
 * directly) and {@link #NOTIFIER} (tests assert the recorded events) — both
 * key-scoped, so cross-test accumulation is invisible to per-key assertions.
 * </ul>
 */
@Factory
@Requires(property = "janus.test.production-factories", notEquals = "true")
final class TestGovernanceFactory {

    /** Opt-in property: absent/false ⇒ {@code Governance.noop()}; "true" ⇒ real governance. */
    static final String ENABLED_PROPERTY = "janus.test.governance";

    /** Optional limiter variant: "fixed" (default) | "sliding". */
    static final String WINDOW_PROPERTY = "janus.test.governance.window";

    static final Clock CLOCK = TestKeyAuthFactory.CLOCK;

    /** DeepSeek published rates (input 0.14 / output 0.28 per 1K) — the design's exact-cost table. */
    static final PriceTable PRICES = PriceTable.of(Map.of(
            "deepseek-v4-flash", new PricingRate(0.14, 0.28, 0.0, 0.0, 4096),
            "deepseek-v4-pro", new PricingRate(0.14, 0.28, 0.0, 0.0, 4096)));

    /** Shared ledger; tests assert on freshly-created key ids (per-key isolation). */
    static final InMemorySpendLedger LEDGER = new InMemorySpendLedger(CLOCK, 1000);

    /**
     * Shared call store the writer records into (the real-governance
     * instances are the 9-arg form, so the writer is exercised end-to-end in every
     * governance suite). Tests assert on freshly-created key ids (per-key isolation).
     */
    static final InMemoryCallStore CALLS = new InMemoryCallStore(CLOCK, 1000);

    /** Shared notifier; the factory clears it when a real-governance context boots. */
    static final RecordingNotifier NOTIFIER = new RecordingNotifier();

    @Singleton
    @Replaces(factory = GovernanceFactory.class)
    Governance governance(Environment environment, MetricsRecorder metricsRecorder) {
        boolean enabled = environment.getProperty(ENABLED_PROPERTY, Boolean.class, false);
        if (!enabled) {
            return Governance.noop(); // every non-opting suite gets the limits-off instance
        }
        NOTIFIER.clear();
        String window = environment.getProperty(WINDOW_PROPERTY, String.class).orElse("fixed");
        RateLimiter limiter =
                "sliding".equals(window) ? new TokenBucketRateLimiter(CLOCK) : new FixedWindowRateLimiter(CLOCK);
        // the real Governance records usage through the same recorder the
        // controllers use (the TestMetricsFactory one — noop unless the class opts
        // into janus.test.metrics), so token/cost series appear only in the
        // suites and the suites stay byte-identical.
        // The full form wires the call-ledger writer over the shared
        // in-memory store — the recordCall seam is live in every real-governance
        // suite. The record's provider is threaded per request from the dispatch
        // seam (the controllers' router dispatch observer), not resolved here.
        return new Governance(limiter, PRICES, LEDGER, NOTIFIER, 0.8, CLOCK, metricsRecorder, CALLS);
    }
}
