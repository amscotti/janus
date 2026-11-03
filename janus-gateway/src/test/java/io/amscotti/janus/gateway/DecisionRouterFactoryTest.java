package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.provider.DecisionAdapter;
import io.amscotti.janus.provider.OpenAiDecisionsAdapter;
import io.amscotti.janus.provider.SystemOneAdapter;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The decision composition root's two explicit-registration halves, pinned:
 * {@code createDecisionAdapter}'s constructor switch (name/base/secret/timeout
 * threading, the openai-decisions default base, blank-base fail-fast, unknown
 * wire format), the {@code META-INF/services} ↔ constructor-switch coupling
 * (every ServiceLoader-known provider must be constructible — the chat twin is
 * {@code ModelListFactoryTest.resolvedBackendNameMatchesTheRealConstructorNaming}),
 * the wire-format constant ↔ adapter NAME literals, and the ignored-strategy
 * boot warning.
 */
class DecisionRouterFactoryTest {

    private static final JanusConfig.TimeoutsConfig TIMEOUTS = new JanusConfig.TimeoutsConfig(3, 7, 9, 11);

    @Test
    void constructorSwitchThreadsNameBaseSecretAndTimeouts() {
        DecisionAdapter adapter =
                DecisionRouterFactory.createDecisionAdapter("ollama", "systemone", "http://h", "k", TIMEOUTS);
        assertEquals("ollama", adapter.name());
        assertEquals("http://h", adapter.baseUrl());
        assertEquals("k", adapter.auth().secret());
        // The timeout threading (3/7/9 → the adapter's deadline constructors) is
        // asserted provider-side in DecisionAdaptersLoopbackTest —
        // AbstractDecisionAdapter's accessors are package-private by module design.
    }

    @Test
    void openAiDecisionsFamilyCarriesTheFixedNameAndDefaultBase() {
        DecisionAdapter adapter =
                DecisionRouterFactory.createDecisionAdapter("whatever", "openai-decisions", "  ", "k", TIMEOUTS);
        assertEquals(OpenAiDecisionsAdapter.NAME, adapter.name(), "the family name is fixed, not the entry's");
        assertEquals(OpenAiDecisionsAdapter.DEFAULT_BASE_URL, adapter.baseUrl(), "blank base ⇒ OpenAI's base");
    }

    @Test
    void systemoneBlankBaseFailsFastAtConstruction() {
        RuntimeException e = assertThrows(
                RuntimeException.class,
                () -> DecisionRouterFactory.createDecisionAdapter("x", "systemone", " ", "k", TIMEOUTS));
        assertTrue(e.getMessage().toLowerCase().contains("base"), e.getMessage());
    }

    @Test
    void unknownWireFormatFailsWithTheRegisteredKey() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class,
                () -> DecisionRouterFactory.createDecisionAdapter("x", "anthropic", "http://h", "k", TIMEOUTS));
        assertTrue(e.getMessage().contains("anthropic"), e.getMessage());
    }

    @Test
    void wireFormatConstantsEqualTheAdapterNames() {
        // The switch in createDecisionAdapter keys off the adapter NAMEs while the
        // TOML binding validates against the JanusConfig constants — the two literal
        // families must stay equal or a valid config boots into "no constructor
        // registered" at the first decision alias.
        assertEquals(JanusConfig.WIRE_FORMAT_SYSTEMONE, SystemOneAdapter.NAME);
        assertEquals(JanusConfig.WIRE_FORMAT_OPENAI_DECISIONS, OpenAiDecisionsAdapter.NAME);
    }

    @Test
    void everyServiceLoaderKnownProviderIsConstructible() {
        // The services file lists the registered adapters (DecisionAdapterSpiTest
        // pins that direction); this pins the other half — every registered name
        // must have a constructor arm, or adding the third adapter to
        // META-INF/services would pass the SPI test and fail at boot.
        Set<String> known = DecisionModelListFactory.knownProviders();
        assertEquals(Set.of("systemone", "openai-decisions"), known);
        for (String provider : known) {
            DecisionAdapter adapter =
                    DecisionRouterFactory.createDecisionAdapter(provider, provider, "http://h", "k", TIMEOUTS);
            assertNotNull(adapter);
            assertEquals(
                    OpenAiDecisionsAdapter.NAME.equals(provider) ? OpenAiDecisionsAdapter.NAME : provider,
                    adapter.name());
        }
    }

    @Test
    void warnAboutIgnoredStrategyOnlyFiresForAConfiguredStrategy() {
        assertNull(DecisionRouterFactory.warnAboutIgnoredStrategy(null));
        assertNull(DecisionRouterFactory.warnAboutIgnoredStrategy(
                new JanusConfig.RouterConfig(null, null, null, null, null, null, null, null, null, null, null, null)));
        assertNull(DecisionRouterFactory.warnAboutIgnoredStrategy(
                new JanusConfig.RouterConfig("  ", null, null, null, null, null, null, null, null, null, null, null)));
        assertNull(
                DecisionRouterFactory.warnAboutIgnoredStrategy(new JanusConfig.RouterConfig(
                        "round-robin", null, null, null, null, null, null, null, null, null, null, null)),
                "the decision plane's own strategy must not produce a false ignored-knob warning");
        String warning = DecisionRouterFactory.warnAboutIgnoredStrategy(new JanusConfig.RouterConfig(
                "least-inflight", null, null, null, null, null, null, null, null, null, null, null));
        assertNotNull(warning);
        assertTrue(warning.contains("least-inflight"), warning);
        assertTrue(warning.contains("does not apply to decision routing"), warning);
    }
}
