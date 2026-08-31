package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** Proves the Micronaut context boots and that TOML config binding works. */
@MicronautTest(startApplication = false)
class JanusConfigTest {

    @Inject
    JanusConfig config;

    @Test
    void loadsTomlConfiguration() {
        assertEquals("janus", config.name());
        assertEquals("0.1.0", config.version());
    }

    /**
     * the main {@code application.toml} deliberately carries no {@code model_list}
     * (single source of truth is the operator's {@code config.toml}; TOML list merge
     * across files is the one ambiguity we avoid). Absent list → null, which the
     * {@code RouterFactory} treats as an empty router — a valid boot state.
     */
    @Test
    void toleratesAbsentModelList() {
        assertNull(config.modelList(), "no [[janus.model_list]] → modelList() is null");
    }

    /**
     * the main {@code application.toml} carries no {@code [janus.router]} section.
     * Micronaut 5.1 nested-record binding keeps the {@code router} bean alive with all
     * {@code @Nullable} components null (a plain nested record without
     * {@code @ConfigurationProperties} would bind null outright — either way the
     * contract is: absent section → no knobs → the factory applies
     * {@link JanusConfig.RouterConfig#DEFAULTS} — a valid boot state, pinned by the
     * {@code LoadBalancerFactory} resolve tests; default wiring).
     */
    @Test
    void toleratesAbsentRouterSection() {
        JanusConfig.RouterConfig router = config.router();
        assertNull(router.strategy(), "no [janus.router] → all components null → factory fills defaults");
        assertNull(router.latencyAlpha());
        assertTrue(router.weights().isEmpty(), "absent weights → empty map (never null)");
        assertNull(router.maxRetries());
        assertNull(router.cooldownTime());
        assertNull(router.breakerFailureThreshold());
    }

    /**
     * the main {@code application.toml} carries no {@code [janus.keys]} section.
     * Micronaut 5.1 nested-record binding keeps the {@code keys} bean alive with all
     * {@code @Nullable} components null (same behavior as {@code [janus.router]})
     * — either way the contract is: absent section → {@code effectiveMasterKeyEnv}
     * falls back to {@code JANUS_MASTER_KEY} — and with the default
     * {@code auth = "on"}, an unresolvable {@code JANUS_MASTER_KEY} <b>fails the
     * boot</b> ({@code MasterKeyProvider.resolveMasterKey}, the hardened posture
     * documented in {@code docs/ops.md}): a forgotten env var must never silently run
     * an unauthenticated admin API. Auth-off is never implicit — only the explicit
     * {@code [janus.keys] auth = "off"} declaration (loudly logged) turns it off.
     */
    @Test
    void toleratesAbsentKeysSection() {
        JanusConfig.KeysConfig keys = config.keys();
        assertNull(keys.masterKeyEnv(), "no [janus.keys] → masterKeyEnv() is null");
        assertEquals(
                JanusConfig.KeysConfig.DEFAULT_MASTER_KEY_ENV,
                keys.effectiveMasterKeyEnv(),
                "absent section → the documented default env name applies");
    }

    /**
     * the main {@code application.toml} carries no {@code [janus.pricing]}
     * section. Micronaut 5.1 nested-record binding keeps the {@code pricing} bean
     * alive with all {@code @Nullable} components null (the {@code [janus.router]}
     * precedent) — the contract is: absent section ⇒ null {@code models} ⇒ the
     * {@code GovernanceFactory} builds an empty price table (every model meters at $0
     * until rows are added) — a valid boot state.
     */
    @Test
    void toleratesAbsentPricingSection() {
        JanusConfig.PricingConfig pricing = config.pricing();
        assertNull(pricing.models(), "no [janus.pricing] → models() is null");
    }

    /**
     * the main {@code application.toml} carries no {@code [janus.limits]}
     * section. The {@code limits} bean stays alive with all {@code @Nullable}
     * components null  — the contract is: absent section ⇒ null knobs ⇒
     * the {@code GovernanceFactory} applies its documented defaults (fixed-window
     * limiter, soft cap 0.8, logger-only notifier, 1000-entry retention) — and since
     * enforcement is key-scoped, keyless behavior is unchanged (noop-level
     * governance).
     */
    @Test
    void toleratesAbsentLimitsSection() {
        JanusConfig.LimitsConfig limits = config.limits();
        assertNull(limits.window());
        assertNull(limits.softCapFraction());
        assertNull(limits.notifierWebhookUrl());
        assertNull(limits.ledgerRetention());
    }

    /**
     * The {@code [janus.limits] window} value is validated at binding time
     * (the {@code JanusConfig} compact constructor, next to the sliding+postgres
     * check) — a typo like {@code "slidng"} must fail fast at boot, never silently
     * degrade to the fixed-window limiter. The error names the bad value.
     */
    @Test
    void invalidLimitsWindowRejectedAtBinding() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> new JanusConfig(
                        "janus",
                        "0.1.0-SNAPSHOT",
                        null,
                        null,
                        null,
                        null,
                        null,
                        new JanusConfig.LimitsConfig("bucket", 0.8, null, 1000),
                        null));
        assertTrue(ex.getMessage().contains("bucket"), "the error must name the bad value: " + ex.getMessage());
    }

    /** The two documented window values (and a blank one = absent) all bind fine. */
    @Test
    void knownLimitsWindowValuesAcceptedAtBinding() {
        new JanusConfig(
                "janus",
                "0.1.0-SNAPSHOT",
                null,
                null,
                null,
                null,
                null,
                new JanusConfig.LimitsConfig("fixed", null, null, null),
                null);
        new JanusConfig(
                "janus",
                "0.1.0-SNAPSHOT",
                null,
                null,
                null,
                null,
                null,
                new JanusConfig.LimitsConfig("sliding", null, null, null),
                null);
        new JanusConfig(
                "janus",
                "0.1.0-SNAPSHOT",
                null,
                null,
                null,
                null,
                null,
                new JanusConfig.LimitsConfig("  ", null, null, null),
                null);
    }

    /**
     * A {@code soft-cap-fraction} outside {@code [0, 1]} (e.g. {@code 1.5}) is
     * rejected at binding time, in the {@code LimitsConfig} compact constructor, so the
     * boot error names the {@code [janus.limits] soft-cap-fraction} key — previously it
     * surfaced one bean-hop later from the {@code Governance} constructor with no key
     * name. The range endpoints are valid (null = the factory default).
     */
    @Test
    void softCapFractionOutOfRangeRejectedAtBinding() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> new JanusConfig.LimitsConfig("fixed", 1.5, null, 1000));
        assertTrue(
                ex.getMessage().contains("soft-cap-fraction"),
                "the error must name the config key: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("1.5"), "the error must name the bad value: " + ex.getMessage());

        new JanusConfig.LimitsConfig("fixed", 0.0, null, 1000);
        new JanusConfig.LimitsConfig("fixed", 1.0, null, 1000);
        new JanusConfig.LimitsConfig("fixed", null, null, 1000);
    }

    /**
     * A non-positive {@code ledger-retention} (a typo'd {@code 0} or a negative
     * value) is rejected at binding time, in the {@code LimitsConfig} compact
     * constructor, so the boot error names {@code [janus.limits]
     * ledger-retention} — before this guard the {@code CallStoreFactory} silently
     * substituted the 1000 default (the sibling {@code [janus.store]}
     * {@code retention}/{@code max-pool-size} precedent). Null (absent key) is the
     * factory-default case; positive values bind.
     */
    @Test
    void ledgerRetentionOutOfRangeRejectedAtBinding() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> new JanusConfig.LimitsConfig("fixed", null, null, 0));
        assertTrue(
                ex.getMessage().contains("ledger-retention"), "the error must name the config key: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("0"), "the error must name the bad value: " + ex.getMessage());

        assertThrows(IllegalArgumentException.class, () -> new JanusConfig.LimitsConfig("fixed", null, null, -3));
        new JanusConfig.LimitsConfig("fixed", null, null, 1);
        new JanusConfig.LimitsConfig("fixed", null, null, null);
    }

    /**
     * the main {@code application.toml} carries no {@code [janus.timeouts]}
     * section. The {@code timeouts} bean stays alive with all {@code @Nullable}
     * components null (the {@code [janus.store]} precedent) — the contract is:
     * absent section ⇒ null components ⇒ the {@code RouterFactory} resolves
     * {@link JanusConfig.TimeoutsConfig#DEFAULTS} (10/60/300/60, the code constants
     * the adapters and SSE publishers pin) — default behavior byte-identical,
     * zero extra config.
     */
    @Test
    void toleratesAbsentTimeoutsSection() {
        JanusConfig.TimeoutsConfig timeouts = config.timeouts();
        assertNull(timeouts.connectTimeoutSeconds(), "no [janus.timeouts] → connectTimeoutSeconds() is null");
        assertNull(timeouts.headerTimeoutSeconds());
        assertNull(timeouts.bodyReadTimeoutSeconds());
        assertNull(timeouts.streamIdleTimeoutSeconds());
    }

    /**
     * A non-positive {@code [janus.timeouts]} value (a typo'd {@code 0} or a
     * negative number) is rejected at binding time, in the {@code TimeoutsConfig}
     * compact constructor, so the boot error names the offending key — the
     * {@code [janus.limits] ledger-retention}/{@code [janus.store] retention}
     * precedent: a typo'd deadline is never silently swapped for the default.
     * Null (absent key) is the factory-default case; positive values bind.
     */
    @Test
    void nonPositiveTimeoutSecondsRejectedAtBindingNamingTheKey() {
        IllegalArgumentException connect =
                assertThrows(IllegalArgumentException.class, () -> new JanusConfig.TimeoutsConfig(0, null, null, null));
        assertTrue(
                connect.getMessage().contains("connect-timeout-seconds"),
                "the error must name the config key: " + connect.getMessage());
        assertTrue(connect.getMessage().contains("0"), "the error must name the bad value: " + connect.getMessage());

        assertThrows(IllegalArgumentException.class, () -> new JanusConfig.TimeoutsConfig(null, -5, null, null));
        assertThrows(IllegalArgumentException.class, () -> new JanusConfig.TimeoutsConfig(null, null, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new JanusConfig.TimeoutsConfig(null, null, null, 0));

        new JanusConfig.TimeoutsConfig(null, null, null, null);
        new JanusConfig.TimeoutsConfig(1, 1, 1, 1);
    }

    /**
     * the main {@code application.toml} carries no {@code [janus.store]} section.
     * The {@code store} bean stays alive with all {@code @Nullable} components null
     *  — the contract is: absent section ⇒ null components ⇒ the
     * {@code CallStoreFactory} applies its documented defaults (memory backend,
     * retention 1000, pool 10) — default behavior byte-identical, zero extra config.
     */
    @Test
    void toleratesAbsentStoreSection() {
        JanusConfig.StoreConfig store = config.store();
        assertNull(store.type(), "no [janus.store] → type() is null (factory default: memory)");
        assertNull(store.jdbcUrlEnv());
        assertNull(store.userEnv());
        assertNull(store.passwordEnv());
        assertNull(store.maxPoolSize());
        assertNull(store.retention());
    }
}
