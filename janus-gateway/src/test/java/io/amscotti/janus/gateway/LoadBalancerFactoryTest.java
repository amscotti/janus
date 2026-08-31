package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.JanusConfig.RouterConfig;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.CircuitBreaker;
import io.amscotti.janus.router.CircuitBreakerConfig;
import io.amscotti.janus.router.CostBasedLoadBalancer;
import io.amscotti.janus.router.CostFunction;
import io.amscotti.janus.router.LatencyBasedLoadBalancer;
import io.amscotti.janus.router.LeastInflightLoadBalancer;
import io.amscotti.janus.router.LoadBalancer;
import io.amscotti.janus.router.PassiveUpstreamHealth;
import io.amscotti.janus.router.ResilienceConfig;
import io.amscotti.janus.router.RetryPolicy;
import io.amscotti.janus.router.RoundRobinLoadBalancer;
import io.amscotti.janus.router.SessionAffinityLoadBalancer;
import io.amscotti.janus.router.WeightedLoadBalancer;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * factory-wiring unit tests — pure JVM (no Micronaut, no network): the six-strategy
 * config switch (fail-fast on unknown, alpha/weights reaching the strategy constructors),
 * the defaults resolution, the {@code [router]} → resilience/breaker mappers (defaults +
 * the cooldown seconds→millis conversion pinned behaviorally via an injectable clock),
 * and the weighted-strategy weight warnings.
 */
class LoadBalancerFactoryTest {

    private static final ChatBackend DEEPSEEK = new FakeBackend("deepseek");
    private static final ChatBackend ANTHROPIC = new FakeBackend("anthropic");

    private static RouterConfig config(String strategy) {
        return new RouterConfig(strategy, 0.3, Map.of(), 2, 200L, 2000L, 0.2, 3, 10, 5, 60, 30);
    }

    // ------------------------------------------------------------ strategy switch

    @Test
    void selectsAllSixStrategiesByConfigName() {
        assertInstanceOf(
                RoundRobinLoadBalancer.class, LoadBalancerFactory.create(config("round-robin"), PriceTable.EMPTY));
        assertInstanceOf(
                LeastInflightLoadBalancer.class,
                LoadBalancerFactory.create(config("least-inflight"), PriceTable.EMPTY));
        assertInstanceOf(
                LatencyBasedLoadBalancer.class, LoadBalancerFactory.create(config("latency-based"), PriceTable.EMPTY));
        assertInstanceOf(
                CostBasedLoadBalancer.class, LoadBalancerFactory.create(config("cost-based"), PriceTable.EMPTY));
        assertInstanceOf(WeightedLoadBalancer.class, LoadBalancerFactory.create(config("weighted"), PriceTable.EMPTY));
        assertInstanceOf(
                SessionAffinityLoadBalancer.class,
                LoadBalancerFactory.create(config("session-affinity"), PriceTable.EMPTY));
    }

    @Test
    void sessionAffinityStrategyReportsConfigName() {
        LoadBalancer lb = LoadBalancerFactory.create(config("session-affinity"), PriceTable.EMPTY);
        assertEquals("session-affinity", lb.name());
    }

    @Test
    void costBasedPricingCostUsesAliasAndUsage() {
        PriceTable table = PriceTable.of(Map.of("m", new PricingRate(1.0, 2.0, 0.0, 0.0, 0)));
        CostFunction fn = LoadBalancerFactory.pricingCost(table);
        ChatResponse response = new ChatResponse(
                "id",
                "chat.completion",
                0L,
                "upstream-ignored",
                List.of(),
                new Usage(1000, 1000, 2000),
                "stop",
                Map.of(),
                Map.of());
        // 1000 * 1.0/1k + 1000 * 2.0/1k = 3 USD = 3_000_000 micro-USD
        assertEquals(3_000_000.0, fn.costOf("m", DEEPSEEK, response), 1e-6);
        assertEquals(0.0, fn.costOf("unknown-alias", DEEPSEEK, response), 1e-6, "missing row → zero");
    }

    @Test
    void costBasedPricingCostPrefersBackendKeyedRateOverAliasRow() {
        // Two providers serving one alias must compare at their own rates —
        // a row keyed by the backend (provider) name overrides the shared alias row.
        PriceTable table = PriceTable.of(Map.of(
                "m", new PricingRate(1.0, 2.0, 0.0, 0.0, 0), // alias row (expensive default)
                "cheap", new PricingRate(0.1, 0.2, 0.0, 0.0, 0))); // backend-keyed override
        CostFunction fn = LoadBalancerFactory.pricingCost(table);
        ChatResponse response = new ChatResponse(
                "id",
                "chat.completion",
                0L,
                "upstream-ignored",
                List.of(),
                new Usage(1000, 1000, 2000),
                "stop",
                Map.of(),
                Map.of());
        assertEquals(300_000.0, fn.costOf("m", new FakeBackend("cheap"), response), 1e-6, "backend row wins");
        assertEquals(
                3_000_000.0,
                fn.costOf("m", new FakeBackend("anthropic"), response),
                1e-6,
                "backend without a row falls back to the alias row");
    }

    @Test
    void costBasedBackendRatesSteerToTheCheaperBackend() {
        // Steering pin: with per-backend rates the strategy can finally express
        // "provider B is 40% cheaper than provider A for the same alias" and steer spend
        // toward it; the control (no override) keeps the alias-rate tie → config order.
        PriceTable priced = PriceTable.of(
                Map.of("m", new PricingRate(1.0, 2.0, 0.0, 0.0, 0), "cheap", new PricingRate(0.1, 0.2, 0.0, 0.0, 0)));
        CostBasedLoadBalancer lb = new CostBasedLoadBalancer(LoadBalancerFactory.pricingCost(priced));
        ChatBackend expensive = new FakeBackend("expensive");
        ChatBackend cheap = new FakeBackend("cheap");
        List<ChatBackend> candidates = List.of(expensive, cheap);
        ChatResponse usage = new ChatResponse(
                "id", "chat.completion", 0L, "m", List.of(), new Usage(1000, 1000, 2000), "stop", Map.of(), Map.of());
        lb.onRequestEnd("m", expensive, true, usage); // alias rate → 3_000_000
        lb.onRequestEnd("m", cheap, true, usage); // backend rate → 300_000
        assertEquals("cheap", lb.pick("m", candidates).name(), "per-backend rates steer to the cheaper backend");
        for (int i = 0; i < 10; i++) {
            lb.onRequestEnd("m", cheap, true, usage); // cheap now at 3_300_000 > expensive 3_000_000
        }
        assertEquals(
                "expensive", lb.pick("m", candidates).name(), "spend equalization pulls back once cheap overtakes");

        CostBasedLoadBalancer control = new CostBasedLoadBalancer(
                LoadBalancerFactory.pricingCost(PriceTable.of(Map.of("m", new PricingRate(1.0, 2.0, 0.0, 0.0, 0)))));
        control.onRequestEnd("m", expensive, true, usage);
        control.onRequestEnd("m", cheap, true, usage);
        assertEquals(
                "expensive",
                control.pick("m", candidates).name(),
                "control: both at the alias rate → 0-tie → config order (current behavior pinned)");
    }

    @Test
    void costBasedPricingCostPricesCacheTokens() {
        // Cache-read / cache-creation tokens flow into the LB cost at
        // their own rates, exactly like the governance ledger.
        PriceTable table = PriceTable.of(Map.of("m", new PricingRate(1.0, 2.0, 0.5, 0.25, 0)));
        CostFunction fn = LoadBalancerFactory.pricingCost(table);
        ChatResponse response = new ChatResponse(
                "id",
                "chat.completion",
                0L,
                "upstream-ignored",
                List.of(),
                new Usage(1000, 1000, 2000, 1000L, 500L),
                "stop",
                Map.of(),
                Map.of());
        // 1000*1.0/1k + 1000*2.0/1k + 1000*0.25/1k + 500*0.5/1k = 3.5 USD
        assertEquals(3_500_000.0, fn.costOf("m", DEEPSEEK, response), 1e-6);
    }

    @Test
    void pricingCostNullResponseOrUsageIsZero() {
        CostFunction fn = LoadBalancerFactory.pricingCost(PriceTable.EMPTY);
        assertEquals(0.0, fn.costOf("m", DEEPSEEK, null), 1e-9);
        ChatResponse noUsage =
                new ChatResponse("id", "chat.completion", 0L, "upstream", List.of(), null, "stop", Map.of(), Map.of());
        assertEquals(0.0, fn.costOf("m", DEEPSEEK, noUsage), 1e-9);
    }

    @Test
    void costBasedStrategyReportsConfigName() {
        LoadBalancer lb = LoadBalancerFactory.create(config("cost-based"), PriceTable.EMPTY);
        assertEquals("cost-based", lb.name());
    }

    @Test
    void unknownStrategyFailsFastListingTheSix() {
        IllegalStateException ex = assertThrows(
                IllegalStateException.class, () -> LoadBalancerFactory.create(config("bogus"), PriceTable.EMPTY));
        assertTrue(ex.getMessage().contains("bogus"), "error must name the unknown strategy: " + ex);
        assertTrue(ex.getMessage().contains("round-robin"), "error must list the six strategies: " + ex);
        assertTrue(ex.getMessage().contains("least-inflight"), ex.getMessage());
        assertTrue(ex.getMessage().contains("latency-based"), ex.getMessage());
        assertTrue(ex.getMessage().contains("cost-based"), ex.getMessage());
        assertTrue(ex.getMessage().contains("weighted"), ex.getMessage());
        assertTrue(ex.getMessage().contains("session-affinity"), ex.getMessage());
    }

    @Test
    void latencyStrategyAppliesLatencyAlphaAndRejectsOutOfRange() {
        // The alpha reaches the strategy constructor; validation lives there and
        // surfaces through the factory (no silent acceptance of a bad value).
        RouterConfig badAlpha = new RouterConfig("latency-based", 0.0, Map.of(), 2, 200L, 2000L, 0.2, 3, 10, 5, 60, 30);
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> LoadBalancerFactory.create(badAlpha, PriceTable.EMPTY));
        assertTrue(ex.getMessage().contains("alpha"), "error must mention alpha: " + ex);

        RouterConfig validAlpha =
                new RouterConfig("latency-based", 1.0, Map.of(), 2, 200L, 2000L, 0.2, 3, 10, 5, 60, 30);
        assertInstanceOf(LatencyBasedLoadBalancer.class, LoadBalancerFactory.create(validAlpha, PriceTable.EMPTY));
    }

    @Test
    void weightedStrategyPassesWeightsThrough() {
        // weights { deepseek: 0, anthropic: 1 } → pool is [anthropic] only → always picks
        // anthropic (empty weights would fall back to the first candidate, deepseek).
        RouterConfig weighted = new RouterConfig(
                "weighted", 0.3, Map.of("deepseek", 0, "anthropic", 1), 2, 200L, 2000L, 0.2, 3, 10, 5, 60, 30);
        LoadBalancer lb = LoadBalancerFactory.create(weighted, PriceTable.EMPTY);
        for (int i = 0; i < 50; i++) {
            assertSame(ANTHROPIC, lb.pick("m", List.of(DEEPSEEK, ANTHROPIC)), "positive weight wins");
        }
    }

    // ---------------------------------------------------------------- defaults

    @Test
    void resolveNullsAndPartialsToDefaults() {
        assertSame(RouterConfig.DEFAULTS, LoadBalancerFactory.resolve(null), "absent section → DEFAULTS");
        RouterConfig partial =
                new RouterConfig("weighted", null, null, 7, null, null, null, null, null, null, null, null);
        RouterConfig resolved = LoadBalancerFactory.resolve(partial);
        assertEquals("weighted", resolved.strategy(), "bound value kept");
        assertEquals(0.3, resolved.latencyAlpha(), "null component filled from DEFAULTS");
        assertEquals(Map.of(), resolved.weights(), "null weights filled from DEFAULTS (empty map)");
        assertEquals(7, resolved.maxRetries(), "bound value kept");
        assertEquals(200L, resolved.backoffBaseMs(), "null component filled from DEFAULTS");
        assertEquals(2000L, resolved.backoffMaxMs());
        assertEquals(0.2, resolved.jitter());
        assertEquals(3, resolved.allowedFails());
        assertEquals(10, resolved.cooldownTime());
        assertEquals(5, resolved.breakerFailureThreshold());
        assertEquals(60, resolved.breakerWindowSeconds());
        assertEquals(30, resolved.breakerCooldownSeconds());
    }

    @Test
    void defaultsProduceRetryPolicyTwoTwoHundredTwoThousandPointTwo() {
        ResilienceConfig resilience = RouterFactory.toResilience(RouterConfig.DEFAULTS);
        RetryPolicy policy = resilience.retryPolicy();
        assertEquals(2, policy.maxRetries());
        assertEquals(200, policy.baseDelayMillis());
        assertEquals(2000, policy.maxDelayMillis());
        assertEquals(0.2, policy.jitter());
        assertSame(ProviderRetryClassifier.INSTANCE, resilience.classifier(), "classifier bridge wired");
    }

    @Test
    void defaultsProducePassiveUpstreamHealthThreeAndTenSecondCooldown() {
        // Behavioral pin with an injectable clock: allowedFails=3 and the cooldown-time
        // seconds→millis conversion (10s → 10_000ms) are both observable through
        // healthy — at exactly T+10_000 the probation trial is admitted.
        MutableClock clock = new MutableClock(1_000_000L);
        ResilienceConfig resilience = RouterFactory.toResilience(RouterConfig.DEFAULTS, clock);
        assertInstanceOf(PassiveUpstreamHealth.class, resilience.health());

        List<ChatBackend> candidates = List.of(DEEPSEEK, ANTHROPIC);
        assertEquals(candidates, resilience.health().healthy(candidates), "no failures → all healthy");
        resilience.health().recordFailure(ANTHROPIC);
        resilience.health().recordFailure(ANTHROPIC);
        assertEquals(
                candidates,
                resilience.health().healthy(candidates),
                "2 consecutive failures < allowedFails(3) → still healthy");
        resilience.health().recordFailure(ANTHROPIC);
        assertEquals(
                List.of(DEEPSEEK),
                resilience.health().healthy(candidates),
                "3 consecutive failures → unhealthy (cooldown pending)");

        clock.advance(9_999);
        assertEquals(
                List.of(DEEPSEEK),
                resilience.health().healthy(candidates),
                "cooldown 10s not elapsed at 9.999s after the flip");
        clock.advance(1);
        assertEquals(
                candidates,
                resilience.health().healthy(candidates),
                "cooldown 10s elapsed exactly → one trial attempt admitted (seconds→millis pinned)");
    }

    @Test
    void defaultsProduceBreakerConfigFiveSixtyThirty() {
        assertEquals(
                new CircuitBreakerConfig(5, Duration.ofSeconds(60), Duration.ofSeconds(30)),
                RouterFactory.breakerConfig(RouterConfig.DEFAULTS));
    }

    @Test
    void thresholdZeroBreakerKeyDisablesTheBreaker() {
        // Operator disable: breaker-failure-threshold = 0 (with the window/
        // cooldown still at their defaults) must bind to a graceful disable — the natural
        // "turn the breaker off, keep health on" action — never a boot crash.
        RouterConfig disabled = new RouterConfig(
                "round-robin", 0.3, Map.of(), 2, 200L, 2000L, 0.2, 3, 10, /* breaker-failure-threshold */ 0, 60, 30);
        CircuitBreakerConfig config = RouterFactory.breakerConfig(disabled);
        assertEquals(0, config.failureThreshold());
        assertEquals(Duration.ofSeconds(60), config.window()); // accepted, ignored
        assertEquals(Duration.ofSeconds(30), config.cooldown()); // accepted, ignored
        assertSame(CircuitBreaker.disabled(), RouterFactory.toBreaker(disabled));
    }

    @Test
    void breakerMapperUsesThresholdWindowAndCooldownSeconds() {
        // Behavioral pin: 5 failures within the window trip OPEN; the 30s cooldown gates
        // canTry (all three breaker keys are SECONDS — Duration.ofSeconds in the mapper).
        MutableClock clock = new MutableClock(1_000_000L);
        CircuitBreaker breaker = RouterFactory.toBreaker(RouterConfig.DEFAULTS, clock);
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(ANTHROPIC));
        for (int i = 0; i < 4; i++) {
            breaker.recordConnectFailure(ANTHROPIC);
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(ANTHROPIC), "4 failures < threshold(5) → still CLOSED");
        breaker.recordConnectFailure(ANTHROPIC);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(ANTHROPIC), "5th failure trips OPEN");
        assertTrue(!breaker.canTry(ANTHROPIC), "OPEN denies dispatch during the cooldown");

        clock.advance(29_999);
        assertTrue(!breaker.canTry(ANTHROPIC), "cooldown 30s not elapsed at 29.999s");
        clock.advance(1);
        assertTrue(breaker.canTry(ANTHROPIC), "cooldown 30s elapsed exactly → half-open probe admitted");
    }

    // ------------------------------------------------------- weight warnings

    @Test
    void weightWarningsNameBackendsWithoutPositiveWeight() {
        Map<String, List<ChatBackend>> routes = Map.of("m", List.of(DEEPSEEK, ANTHROPIC));
        List<String> warnings = LoadBalancerFactory.warnAboutWeights(routes, Map.of("deepseek", 3));
        assertEquals(1, warnings.size(), "anthropic lacks a weight; deepseek has one");
        assertTrue(warnings.getFirst().contains("anthropic"), warnings.toString());
        assertTrue(warnings.getFirst().contains("excluded"), "warning must explain the exclusion: " + warnings);
    }

    @Test
    void weightWarningsCoverZeroAndMissingWeightsAndUnknownKeys() {
        Map<String, List<ChatBackend>> routes = Map.of("m", List.of(DEEPSEEK, ANTHROPIC));
        List<String> warnings = LoadBalancerFactory.warnAboutWeights(routes, Map.of("deepseek", 0, "bogus", 5));
        assertEquals(3, warnings.size(), "zero weight + missing weight + unknown key → three warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("deepseek") && w.contains("no positive weight")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("anthropic") && w.contains("no positive weight")));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("bogus") && w.contains("no listed backend")));
    }

    @Test
    void weightWarningsQuietWhenEveryBackendHasAPositiveWeight() {
        Map<String, List<ChatBackend>> routes = Map.of("m", List.of(DEEPSEEK, ANTHROPIC));
        assertTrue(LoadBalancerFactory.warnAboutWeights(routes, Map.of("deepseek", 3, "anthropic", 1))
                .isEmpty());
    }

    // ------------------------------------------------- session-affinity warnings

    @Test
    void affinityWarnsWhenWeightsAreConfiguredAndIgnoresThem() {
        // weights are a weighted-strategy knob; under session-affinity they are
        // silently ignored — the boot warning is the anti-footgun (the
        // warnAboutWeights/warnAboutCosts style).
        List<String> warnings = LoadBalancerFactory.warnAboutIgnoredWeights(Map.of("deepseek", 3));
        assertEquals(1, warnings.size(), "configured weights + session-affinity → one warning");
        assertTrue(warnings.getFirst().contains("session-affinity"), warnings.toString());
        assertTrue(warnings.getFirst().contains("ignored"), "the warning says the weights are ignored: " + warnings);
    }

    @Test
    void affinityWeightsWarningQuietWhenNoWeightsConfigured() {
        assertTrue(LoadBalancerFactory.warnAboutIgnoredWeights(Map.of()).isEmpty(), "no weights → quiet");
        assertTrue(LoadBalancerFactory.warnAboutIgnoredWeights(null).isEmpty(), "absent weights → quiet");
    }

    // --------------------------------------------------------- cost warnings

    @Test
    void warnAboutCostsEmitsForEmptyTable() {
        List<String> warnings = LoadBalancerFactory.warnAboutCosts(PriceTable.EMPTY);
        assertEquals(1, warnings.size(), "an empty table must not silently pin cost-based to config order");
        assertTrue(warnings.getFirst().contains("cost-based"), warnings.toString());
        assertTrue(warnings.getFirst().contains("$0"), "the warning explains the zero-tie cause: " + warnings);
        assertTrue(warnings.getFirst().contains("first candidate"), "the warning names the pinning: " + warnings);
    }

    @Test
    void warnAboutCostsQuietWhenTableHasRows() {
        PriceTable withAliasRow = PriceTable.of(Map.of("deepseek-v4-flash", new PricingRate(0.14, 0.28, 0.0, 0.0, 0)));
        assertTrue(LoadBalancerFactory.warnAboutCosts(withAliasRow).isEmpty(), "a pricing row → quiet");
        PriceTable withBackendOverride = PriceTable.of(Map.of("deepseek", new PricingRate(0.14, 0.28, 0.0, 0.0, 0)));
        assertTrue(
                LoadBalancerFactory.warnAboutCosts(withBackendOverride).isEmpty(),
                "a backend-keyed override row also counts as pricing configured");
    }

    // ------------------------------------------------------------------ doubles

    /** Test clock with a settable millis; the health/breaker cooldowns read it lazily. */
    private static final class MutableClock extends Clock {

        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long deltaMillis) {
            millis += deltaMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("MutableClock is fixed-zone");
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
