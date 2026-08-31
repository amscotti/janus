package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.InMemorySpendLedger;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * step 7 (-slimmed) — {@link GovernanceFactory}, the governance composition
 * root: {@code [[janus.pricing.models]]} becomes the {@link PriceTable} (absent ⇒
 * empty), the notifier is the logger unless a webhook URL is configured, and the
 * soft-cap fraction defaults to 0.8 — defaults live in the factory, not the binding
 * records.
 *
 * <p><b>Constructor slimming.</b> The {@code RateLimiter}/{@code SpendLedger} beans moved to
 * {@link CallStoreFactory} (derived views of the one {@link CallStore} bean — the
 * "one object, three bean types" handoff), and the {@code [janus.limits] window}
 * selection moved into the {@code CallStoreFactory}'s memory branch. Those selections
 * are covered by {@code CallStoreFactoryBootTest} (the Docker-free leg) and the
 * Testcontainers-backed {@code CallStoreFactoryTest}; this suite keeps the
 * {@code PriceTable}/{@code Notifier}/{@code Governance} wiring.
 */
class GovernanceFactoryTest {

    private static final Clock CLOCK = TestKeyAuthFactory.CLOCK;

    private final GovernanceFactory factory = new GovernanceFactory();

    @Test
    void priceTableBuiltFromPricingModels() {
        JanusConfig.PricingConfig pricing = new JanusConfig.PricingConfig(
                List.of(new JanusConfig.PricingConfig.PricingModel("deepseek-v4-flash", 0.14, 0.28, null, null, 4096)));
        PriceTable table = factory.priceTable(config(pricing, null));
        PricingRate rate = table.rateFor("deepseek-v4-flash");
        assertEquals(0.14, rate.inputPer1K());
        assertEquals(0.28, rate.outputPer1K());
        assertEquals(0.0, rate.cacheReadPer1K(), "absent cache rate → 0");
        assertEquals(4096, rate.defaultMaxTokens());
        assertEquals(PricingRate.ZERO, table.rateFor("unknown-model"), "rows outside the table stay zero-rate");
    }

    @Test
    void requirePricedFailsBootWhenAModelListAliasHasNoRow() {
        JanusConfig.PricingConfig pricing = new JanusConfig.PricingConfig(
                List.of(new JanusConfig.PricingConfig.PricingModel("priced", 0.14, 0.28, null, null, 4096)), true);
        JanusConfig config = new JanusConfig(
                "janus",
                "0.1.0-SNAPSHOT",
                List.of(
                        new JanusConfig.ModelListEntry("priced", "deepseek", "DEEPSEEK_API_KEY", null),
                        new JanusConfig.ModelListEntry("missing", "deepseek", "DEEPSEEK_API_KEY", null)),
                null,
                null,
                null,
                pricing,
                null,
                null);
        IllegalArgumentException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> factory.priceTable(config));
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("missing"), thrown.getMessage());
    }

    @Test
    void duplicatePricingRowFailsBootInsteadOfSilentlyLastWinning() {
        // The table is keyed by name: two rows sharing one would silently last-win —
        // metering billed at whichever row the map happened to keep, and the operator
        // editing the table has no way to see which rates survived. Fail-fast, the
        // same posture as require-priced and the [janus.limits] window check.
        JanusConfig.PricingConfig pricing = new JanusConfig.PricingConfig(List.of(
                new JanusConfig.PricingConfig.PricingModel("deepseek-v4-flash", 0.14, 0.28, null, null, 4096),
                new JanusConfig.PricingConfig.PricingModel("deepseek-v4-flash", 0.9, 1.8, null, null, 8192)));
        IllegalArgumentException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> factory.priceTable(config(pricing, null)));
        org.junit.jupiter.api.Assertions.assertTrue(thrown.getMessage().contains("duplicate"), thrown.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(
                thrown.getMessage().contains("deepseek-v4-flash"), thrown.getMessage());
    }

    @Test
    void absentPricingYieldsEmptyTable() {
        assertEquals(PriceTable.EMPTY, factory.priceTable(config(null, null)));
        assertEquals(
                PriceTable.EMPTY,
                factory.priceTable(config(new JanusConfig.PricingConfig(null), null)),
                "a section with no models is also empty");
    }

    @Test
    void notifierDefaultsToLoggingAndWebhookWhenUrlConfigured() {
        // The production notifier is the once-per-key-per-window
        // dedup wrapper around the configured sink (logger default / webhook when a URL
        // is configured) — the DedupNotifier unit suite pins the dedup semantics.
        Notifier defaulted = factory.notifier(config(null, null), CLOCK);
        assertInstanceOf(DedupNotifier.class, defaulted);
        assertInstanceOf(LoggingNotifier.class, ((DedupNotifier) defaulted).delegate());
        Notifier defaultedLimits =
                factory.notifier(config(null, new JanusConfig.LimitsConfig(null, null, null, null)), CLOCK);
        assertInstanceOf(DedupNotifier.class, defaultedLimits);
        assertInstanceOf(LoggingNotifier.class, ((DedupNotifier) defaultedLimits).delegate());
        Notifier webhook = factory.notifier(
                config(null, new JanusConfig.LimitsConfig("fixed", null, "http://hooks/janus", 1000)), CLOCK);
        assertInstanceOf(DedupNotifier.class, webhook);
        assertInstanceOf(WebhookNotifier.class, ((DedupNotifier) webhook).delegate());
    }

    @Test
    void malformedWebhookUrlFallsBackToLoggerNotifier() {
        // A syntactically-invalid notifier-webhook-url must NOT abort the
        // gateway boot — the factory validates the URL and falls back to the
        // logger-only sink (the misconfiguration is logged, webhooks disabled).
        for (String badUrl : List.of("http://host with space/hook", "not a url", "http://")) {
            Notifier notifier =
                    factory.notifier(config(null, new JanusConfig.LimitsConfig("fixed", null, badUrl, 1000)), CLOCK);
            assertInstanceOf(DedupNotifier.class, notifier, badUrl);
            assertInstanceOf(
                    LoggingNotifier.class,
                    ((DedupNotifier) notifier).delegate(),
                    "a malformed webhook URL must not produce a WebhookNotifier: " + badUrl);
        }
    }

    @Test
    void blankWebhookUrlIsLoggerNotifier() {
        // A blank (not just null) URL is treated as unconfigured — the logger sink.
        Notifier notifier =
                factory.notifier(config(null, new JanusConfig.LimitsConfig("fixed", null, "   ", 1000)), CLOCK);
        assertInstanceOf(DedupNotifier.class, notifier);
        assertInstanceOf(LoggingNotifier.class, ((DedupNotifier) notifier).delegate());
    }

    @Test
    void nonHttpSchemeWebhookUrlFallsBackToLoggerNotifier() {
        // A syntactically-valid URL with a non-http(s) scheme (ftp://, file://,
        // mailto:) is NOT a webhook — it would build a WebhookNotifier whose every notify
        // fails at HttpRequest.newBuilder (caught, logged, dropped) instead of the
        // documented logger fallback. The factory requires http/https and falls back.
        for (String badUrl : List.of("ftp://hooks.example.com/janus", "file:///tmp/hook", "mailto:ops@example.com")) {
            Notifier notifier =
                    factory.notifier(config(null, new JanusConfig.LimitsConfig("fixed", null, badUrl, 1000)), CLOCK);
            assertInstanceOf(DedupNotifier.class, notifier, badUrl);
            assertInstanceOf(
                    LoggingNotifier.class,
                    ((DedupNotifier) notifier).delegate(),
                    "a non-http(s) webhook URL must not produce a WebhookNotifier: " + badUrl);
        }
    }

    @Test
    void governanceAppliesSoftCapFractionFromLimitsWithDefault() {
        // the factory wires the Tier-1 recorder; the unit test passes the no-op
        // (the pre- form) so the soft-cap resolution assertions stay focused. No
        // Router is wired — the call ledger's provider is threaded per request from
        // the dispatch seam (the controllers' observer), not resolved at construction.
        Governance defaulted = factory.governance(
                config(null, null),
                new FixedWindowRateLimiter(CLOCK),
                PriceTable.EMPTY,
                new InMemorySpendLedger(CLOCK, 1000),
                new LoggingNotifier(),
                CLOCK,
                MetricsRecorder.noop(),
                null);
        assertEquals(0.8, defaulted.softCapFraction(), "absent soft-cap-fraction → the documented default");

        Governance configured = factory.governance(
                config(null, new JanusConfig.LimitsConfig("fixed", 0.6, null, 1000)),
                new FixedWindowRateLimiter(CLOCK),
                PriceTable.EMPTY,
                new InMemorySpendLedger(CLOCK, 1000),
                new LoggingNotifier(),
                CLOCK,
                MetricsRecorder.noop(),
                null);
        assertEquals(0.6, configured.softCapFraction(), "soft-cap-fraction binds into the Governance");
    }

    private static JanusConfig config(JanusConfig.PricingConfig pricing, JanusConfig.LimitsConfig limits) {
        return new JanusConfig("janus", "0.1.0-SNAPSHOT", null, null, null, null, pricing, limits, null);
    }
}
