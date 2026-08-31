package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.store.PricingRate;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import io.micronaut.context.exceptions.BeanCreationException;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import io.micronaut.toml.env.TomlPropertySourceLoader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Binding test: the TOML array-of-tables {@code
 * [[janus.model_list]]} binds to {@link JanusConfig#modelList} — {@code name},
 * {@code provider}, {@code api_key_env} → {@code apiKeyEnv}, {@code base_url} →
 * {@code baseUrl} — in TOML order, with an absent {@code base_url} tolerated (null).
 *
 * <p><b>Loading mechanism.</b> Micronaut 5.1.0's {@code @PropertySource} annotation
 * takes inline {@code @Property} values only (no file locations), so the test builds a
 * plain {@link ApplicationContext} with the {@code model-list-test.toml} resource loaded
 * through {@link TomlPropertySourceLoader} — the same loader the production {@code
 * micronaut.config.files} path resolves via its ServiceLoader registration (this is the
 * plan's documented fallback). A per-class {@code micronaut.config.files} system
 * property was rejected: Gradle shares one test JVM across classes, so the property
 * would leak into {@link JanusConfigTest} (whose absent-{@code model_list} assertion
 * needs the clean main {@code application.toml}).
 *
 * <p>The absent-list side is {@link JanusConfigTest#toleratesAbsentModelList} (the
 * main {@code application.toml} carries no {@code model_list}) — null {@code modelList}
 * is a valid boot state the {@code RouterFactory} treats as an empty router.
 *
 * <p><b> ([router] TOML).</b> {@code router-test.toml} pins the {@code [janus.router]}
 * block binding: kebab-case keys bind every {@link JanusConfig.RouterConfig} component
 * (strategy, weights inline table, latency-alpha, max-retries, backoff-*-ms, jitter,
 * allowed-fails, cooldown-time, breaker-*).: unlike the array-of-tables
 * element keys (negative-pinned here), plain-section scalar keys normalize
 * {@code _} and {@code -} — {@code underscoreSpellingsBindForSectionKeys} pins that
 * actual behavior. The absent-section side is
 * {@link JanusConfigTest#toleratesAbsentRouterSection}.
 */
class ModelListBindingTest {

    @Test
    void bindsModelListEntriesInTomlOrder() {
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(loadTestToml("model-list-test.toml"))
                .start()) {
            JanusConfig config = context.getBean(JanusConfig.class);
            List<JanusConfig.ModelListEntry> modelList = config.modelList();
            assertEquals(2, modelList.size(), "both [[janus.model_list]] entries must bind");

            JanusConfig.ModelListEntry first = modelList.get(0);
            assertEquals("deepseek-v4-flash", first.name());
            assertEquals("deepseek", first.provider());
            assertEquals("DEEPSEEK_API_KEY", first.apiKeyEnv());
            assertEquals("https://api.deepseek.com", first.baseUrl());

            JanusConfig.ModelListEntry second = modelList.get(1);
            assertEquals("deepseek-v4-pro", second.name());
            assertEquals("deepseek", second.provider());
            assertEquals("DEEPSEEK_API_KEY", second.apiKeyEnv());
            assertNull(second.baseUrl(), "omitted base-url binds to null (adapter default applies)");
        }
    }

    @Test
    void bindsProviderBlocksToProviderEntryMap() {
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(loadTestToml("model-list-test.toml"))
                .start()) {
            JanusConfig config = context.getBean(JanusConfig.class);
            Map<String, JanusConfig.ProviderEntry> providers = config.providers();
            assertEquals(1, providers.size(), "the [janus.providers.deepseek] block must bind");
            JanusConfig.ProviderEntry deepseek = providers.get("deepseek");
            assertEquals("openai-compatible", deepseek.wireFormat());
            assertEquals("https://api.deepseek.com", deepseek.baseUrl());
            assertEquals("DEEPSEEK_API_KEY", deepseek.apiKeyEnv());
        }
    }

    @Test
    void underscoreKeysSilentlyNullCredentialFields() throws IOException {
        // Negative pin: Micronaut 5.1 does NOT treat _ and - as equivalent for TOML
        // binding — api_key_env/base_url bind to null (the operator 401s silently unless
        // warned). This test documents the empirically-verified behavior so the kebab-case
        // spelling (api-key-env/base-url) stays the documented one.
        String underscoreToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [[janus.model_list]]
                name = "deepseek-v4-flash"
                provider = "deepseek"
                api_key_env = "DEEPSEEK_API_KEY"
                base_url = "https://api.deepseek.com"
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "underscore",
                        new TomlPropertySourceLoader()
                                .read(
                                        "underscore",
                                        new java.io.ByteArrayInputStream(
                                                underscoreToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig.ModelListEntry entry =
                    context.getBean(JanusConfig.class).modelList().get(0);
            assertEquals("deepseek-v4-flash", entry.name());
            assertEquals("deepseek", entry.provider());
            assertNull(entry.apiKeyEnv(), "api_key_env (underscore) must NOT bind to apiKeyEnv");
            assertNull(entry.baseUrl(), "base_url (underscore) must NOT bind to baseUrl");
        }
    }

    private static PropertySource loadTestToml(String resource) {
        ClassPathResourceLoader loader =
                ClassPathResourceLoader.defaultLoader(ModelListBindingTest.class.getClassLoader());
        try (InputStream in = loader.getResourceAsStream("classpath:" + resource)
                .orElseThrow(() -> new IllegalStateException("missing test TOML: classpath:" + resource))) {
            return PropertySource.of(resource, new TomlPropertySourceLoader().read(resource, in));
        } catch (IOException e) {
            throw new IllegalStateException("failed to read classpath:" + resource, e);
        }
    }

    // ------------------------------------------------------ [janus.router] block

    @Test
    void bindsRouterSectionToRouterConfig() {
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(loadTestToml("router-test.toml"))
                .start()) {
            JanusConfig.RouterConfig router = context.getBean(JanusConfig.class).router();
            assertEquals("weighted", router.strategy());
            assertEquals(0.7, router.latencyAlpha().doubleValue());
            assertEquals(Map.of("deepseek", 3, "anthropic", 1), router.weights());
            assertEquals(
                    List.of("deepseek", "anthropic"),
                    new ArrayList<>(router.weights().keySet()),
                    "inline-table weights must bind preserving TOML order");
            assertEquals(5, router.maxRetries().intValue());
            assertEquals(100L, router.backoffBaseMs().longValue());
            assertEquals(4000L, router.backoffMaxMs().longValue());
            assertEquals(0.4, router.jitter().doubleValue());
            assertEquals(7, router.allowedFails().intValue());
            assertEquals(42, router.cooldownTime().intValue(), "cooldown-time (seconds) binds cooldownTime");
            assertEquals(9, router.breakerFailureThreshold().intValue());
            assertEquals(90, router.breakerWindowSeconds().intValue());
            assertEquals(45, router.breakerCooldownSeconds().intValue());
        }
    }

    @Test
    void underscoreSpellingsBindForSectionKeys() throws IOException {
        // (verified empirically against Micronaut 5.1): the rule
        // ("underscores silently null") applies to ARRAY-OF-TABLES element keys only
        // ([[janus.model-list]] — element maps keep their raw keys). Under a plain
        // section ([janus.router]) the property resolver normalizes _ and - in scalar
        // paths, so latency_alpha and latency-alpha bind identically. This test pins the
        // actual behavior so operators know the kebab spelling is the documented
        // convention (consistency with the model-list rule) without being a silent-null
        // trap; the real negative pin stays with the model-list test.
        String underscoreToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.router]
                strategy = "weighted"
                latency_alpha = 0.7
                weights = { deepseek = 3 }
                max_retries = 5
                backoff_base_ms = 100
                backoff_max_ms = 4000
                jitter = 0.4
                allowed_fails = 7
                cooldown_time = 42
                breaker_failure_threshold = 9
                breaker_window_seconds = 90
                breaker_cooldown_seconds = 45
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "underscore-router",
                        new TomlPropertySourceLoader()
                                .read(
                                        "underscore-router",
                                        new java.io.ByteArrayInputStream(
                                                underscoreToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig.RouterConfig router = context.getBean(JanusConfig.class).router();
            assertEquals("weighted", router.strategy());
            assertEquals(0.7, router.latencyAlpha().doubleValue(), "latency_alpha normalizes to latency-alpha");
            assertEquals(Map.of("deepseek", 3), router.weights());
            assertEquals(5, router.maxRetries().intValue(), "max_retries normalizes to max-retries");
            assertEquals(100L, router.backoffBaseMs().longValue(), "backoff_base_ms normalizes to backoff-base-ms");
            assertEquals(4000L, router.backoffMaxMs().longValue(), "backoff_max_ms normalizes to backoff-max-ms");
            assertEquals(0.4, router.jitter().doubleValue());
            assertEquals(7, router.allowedFails().intValue(), "allowed_fails normalizes to allowed-fails");
            assertEquals(42, router.cooldownTime().intValue(), "cooldown_time normalizes to cooldown-time");
            assertEquals(
                    9,
                    router.breakerFailureThreshold().intValue(),
                    "breaker_failure_threshold normalizes to breaker-failure-threshold");
            assertEquals(
                    90,
                    router.breakerWindowSeconds().intValue(),
                    "breaker_window_seconds normalizes to breaker-window-seconds");
            assertEquals(
                    45,
                    router.breakerCooldownSeconds().intValue(),
                    "breaker_cooldown_seconds normalizes to breaker-cooldown-seconds");
        }
    }

    // ------------------------------------------------------ [janus.keys] block

    /**
     * the {@code [janus.keys]} section binds the nested {@code KeysConfig} — the
     * admin master key is an <b>env-reference</b> ({@code master-key-env} names the
     * environment variable; the value itself never appears in TOML, pattern).
     * {@code keys-test.toml} carries {@code master-key-env = "MY_MASTER_KEY_ENV"}.
     */
    @Test
    void bindsKeysSectionToKeysConfig() {
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(loadTestToml("keys-test.toml"))
                .start()) {
            JanusConfig.KeysConfig keys = context.getBean(JanusConfig.class).keys();
            assertEquals("MY_MASTER_KEY_ENV", keys.masterKeyEnv());
            assertEquals("MY_MASTER_KEY_ENV", keys.effectiveMasterKeyEnv());
        }
    }

    @Test
    void underscoreSpellingsBindForKeysSection() throws IOException {
        // Same plain-section normalization as [janus.router] : master_key_env
        // and master-key-env bind identically — kebab stays the documented spelling.
        String underscoreToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.keys]
                master_key_env = "UNDERSCORE_MASTER_ENV"
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "underscore-keys",
                        new TomlPropertySourceLoader()
                                .read(
                                        "underscore-keys",
                                        new java.io.ByteArrayInputStream(
                                                underscoreToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig.KeysConfig keys = context.getBean(JanusConfig.class).keys();
            assertEquals("UNDERSCORE_MASTER_ENV", keys.masterKeyEnv(), "master_key_env normalizes to master-key-env");
        }
    }

    // ------------------------------------------------------ [janus.pricing] block

    @Test
    void bindsPricingSectionToPricingConfig() {
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(loadTestToml("pricing-test.toml"))
                .start()) {
            JanusConfig.PricingConfig pricing =
                    context.getBean(JanusConfig.class).pricing();
            List<JanusConfig.PricingConfig.PricingModel> models = pricing.models();
            assertEquals(2, models.size(), "both [[janus.pricing.models]] rows must bind");

            JanusConfig.PricingConfig.PricingModel first = models.get(0);
            assertEquals("deepseek-v4-flash", first.name());
            assertEquals(0.00014, first.inputPer1k().doubleValue(), "input-per-1k binds inputPer1k");
            assertEquals(0.00028, first.outputPer1k().doubleValue(), "output-per-1k binds outputPer1k");
            assertNull(first.cacheReadPer1k(), "omitted cache-read-per-1k binds null (factory substitutes 0)");
            assertNull(first.cacheCreationPer1k());
            assertEquals(4096, first.defaultMaxTokens().intValue(), "default-max-tokens binds defaultMaxTokens");
            assertNull(first.longContextThreshold());

            JanusConfig.PricingConfig.PricingModel second = models.get(1);
            assertEquals("deepseek-v4-pro", second.name());
            assertEquals(0.07, second.cacheReadPer1k().doubleValue());
            assertEquals(0.09, second.cacheCreationPer1k().doubleValue());
            assertEquals(8192, second.defaultMaxTokens().intValue());
            assertEquals(200000, second.longContextThreshold().intValue());
            assertEquals(0.28, second.longInputPer1k().doubleValue());
            assertEquals(0.56, second.longOutputPer1k().doubleValue());

            PricingRate tiered = new PricingRate(
                    second.inputPer1k(),
                    second.outputPer1k(),
                    second.cacheReadPer1k(),
                    second.cacheCreationPer1k(),
                    second.defaultMaxTokens(),
                    0.0,
                    second.longContextThreshold(),
                    second.longInputPer1k(),
                    second.longOutputPer1k(),
                    0.0,
                    0.0);
            assertEquals(second.inputPer1k(), tiered.forPromptTokens(199_999).inputPer1K());
            assertEquals(0.28, tiered.forPromptTokens(200_000).inputPer1K());
            assertEquals(0.56, tiered.forPromptTokens(200_000).outputPer1K());
        }
    }

    @Test
    void underscorePricingKeysSilentlyNull() throws IOException {
        // Negative pin: array-of-tables element keys keep their RAW spellings —
        // input_per_1k does NOT bind to inputPer1K (silently null ⇒ a zero rate the
        // operator may not notice). The kebab spelling is the documented convention,
        // written up in config.toml exactly like the model-list note.
        String underscoreToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.pricing]
                [[janus.pricing.models]]
                name = "deepseek-v4-flash"
                input_per_1k = 0.14
                output_per_1k = 0.28
                default_max_tokens = 4096
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "underscore-pricing",
                        new TomlPropertySourceLoader()
                                .read(
                                        "underscore-pricing",
                                        new java.io.ByteArrayInputStream(
                                                underscoreToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig.PricingConfig.PricingModel model =
                    context.getBean(JanusConfig.class).pricing().models().get(0);
            assertEquals("deepseek-v4-flash", model.name());
            assertNull(model.inputPer1k(), "input_per_1k must NOT bind to inputPer1k");
            assertNull(model.outputPer1k(), "output_per_1k must NOT bind to outputPer1k");
            assertNull(model.defaultMaxTokens(), "default_max_tokens must NOT bind to defaultMaxTokens");
        }
    }

    // ------------------------------------------------------ [janus.limits] block

    @Test
    void bindsLimitsSectionToLimitsConfig() {
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(loadTestToml("limits-test.toml"))
                .start()) {
            JanusConfig.LimitsConfig limits = context.getBean(JanusConfig.class).limits();
            assertEquals("sliding", limits.window());
            assertEquals(0.6, limits.softCapFraction().doubleValue(), "soft-cap-fraction binds softCapFraction");
            assertEquals("http://localhost:9999/hook", limits.notifierWebhookUrl());
            assertEquals(50, limits.ledgerRetention().intValue(), "ledger-retention binds ledgerRetention");
        }
    }

    @Test
    void underscoreSpellingsBindForLimitsSection() throws IOException {
        // Same plain-section normalization as [janus.router]/[janus.keys] :
        // soft_cap_fraction and soft-cap-fraction bind identically — kebab stays the
        // documented spelling.
        String underscoreToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.limits]
                window = "sliding"
                soft_cap_fraction = 0.6
                notifier_webhook_url = "http://localhost:9999/hook"
                ledger_retention = 50
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "underscore-limits",
                        new TomlPropertySourceLoader()
                                .read(
                                        "underscore-limits",
                                        new java.io.ByteArrayInputStream(
                                                underscoreToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig.LimitsConfig limits = context.getBean(JanusConfig.class).limits();
            assertEquals("sliding", limits.window());
            assertEquals(0.6, limits.softCapFraction().doubleValue(), "soft_cap_fraction normalizes");
            assertEquals("http://localhost:9999/hook", limits.notifierWebhookUrl(), "notifier_webhook_url normalizes");
            assertEquals(50, limits.ledgerRetention().intValue(), "ledger_retention normalizes");
        }
    }

    // ---------------------------------------------------- [janus.timeouts] block

    /**
     * the {@code [janus.timeouts]} section binds the nested {@code TimeoutsConfig}
     * — the four upstream-deadline keys (connect / header / body-read /
     * stream-idle, all {@code -seconds}). {@code timeouts-test.toml} carries values
     * deliberately different from {@code DEFAULTS} so the test distinguishes bound
     * values from the factory defaults.
     */
    @Test
    void bindsTimeoutsSectionToTimeoutsConfig() {
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(loadTestToml("timeouts-test.toml"))
                .start()) {
            JanusConfig.TimeoutsConfig timeouts =
                    context.getBean(JanusConfig.class).timeouts();
            assertEquals(
                    3,
                    timeouts.connectTimeoutSeconds().intValue(),
                    "connect-timeout-seconds binds connectTimeoutSeconds");
            assertEquals(
                    7, timeouts.headerTimeoutSeconds().intValue(), "header-timeout-seconds binds headerTimeoutSeconds");
            assertEquals(
                    120,
                    timeouts.bodyReadTimeoutSeconds().intValue(),
                    "body-read-timeout-seconds binds bodyReadTimeoutSeconds");
            assertEquals(
                    9,
                    timeouts.streamIdleTimeoutSeconds().intValue(),
                    "stream-idle-timeout-seconds binds streamIdleTimeoutSeconds");
        }
    }

    @Test
    void underscoreSpellingsBindForTimeoutsSection() throws IOException {
        // Same plain-section normalization as [janus.router]/[janus.keys] :
        // connect_timeout_seconds and connect-timeout-seconds bind identically —
        // kebab stays the documented spelling.
        String underscoreToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.timeouts]
                connect_timeout_seconds = 3
                header_timeout_seconds = 7
                body_read_timeout_seconds = 120
                stream_idle_timeout_seconds = 9
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "underscore-timeouts",
                        new TomlPropertySourceLoader()
                                .read(
                                        "underscore-timeouts",
                                        new java.io.ByteArrayInputStream(
                                                underscoreToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig.TimeoutsConfig timeouts =
                    context.getBean(JanusConfig.class).timeouts();
            assertEquals(3, timeouts.connectTimeoutSeconds().intValue(), "connect_timeout_seconds normalizes");
            assertEquals(7, timeouts.headerTimeoutSeconds().intValue(), "header_timeout_seconds normalizes");
            assertEquals(120, timeouts.bodyReadTimeoutSeconds().intValue(), "body_read_timeout_seconds normalizes");
            assertEquals(9, timeouts.streamIdleTimeoutSeconds().intValue(), "stream_idle_timeout_seconds normalizes");
        }
    }

    @Test
    void nonPositiveTimeoutsValueFailsFastAtBinding() throws IOException {
        // fail-fast : a typo'd 0 deadline refuses the node to start —
        // the TimeoutsConfig compact constructor rejects it at binding time, naming
        // the key (the [janus.store] retention / [janus.limits] ledger-retention
        // precedent).
        String badToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.timeouts]
                header-timeout-seconds = 0
                """;
        BeanCreationException e = assertThrows(BeanCreationException.class, () -> {
            try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                    .propertySources(PropertySource.of(
                            "bad-timeouts",
                            new TomlPropertySourceLoader()
                                    .read(
                                            "bad-timeouts",
                                            new java.io.ByteArrayInputStream(
                                                    badToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                    .start()) {
                // Force the config binding — Micronaut config records bind lazily.
                context.getBean(JanusConfig.class);
            }
        });
        assertTrue(
                e.getMessage().contains("header-timeout-seconds"),
                "the fail-fast error names the offending key: " + e.getMessage());
    }

    // ---------------------------------------- timeout overrides (entry/block)

    /**
     * : the four {@code [janus.timeouts]} keys, same kebab {@code -seconds}
     * spelling, bind as per-provider overrides on the {@code
     * [janus.providers.<name>]} blocks. Values deliberately differ from {@code
     * DEFAULTS} so the test distinguishes bound values from factory defaults.
     */
    @Test
    void bindsProviderBlockTimeoutComponents() throws IOException {
        String toml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.providers.ollama]
                wire-format = "openai-compatible"
                connect-timeout-seconds = 5
                header-timeout-seconds = 120
                body-read-timeout-seconds = 900
                stream-idle-timeout-seconds = 45
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "provider-timeouts",
                        new TomlPropertySourceLoader()
                                .read(
                                        "provider-timeouts",
                                        new java.io.ByteArrayInputStream(
                                                toml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig.ProviderEntry block =
                    context.getBean(JanusConfig.class).providers().get("ollama");
            assertEquals(
                    5, block.connectTimeoutSeconds().intValue(), "connect-timeout-seconds binds connectTimeoutSeconds");
            assertEquals(
                    120, block.headerTimeoutSeconds().intValue(), "header-timeout-seconds binds headerTimeoutSeconds");
            assertEquals(
                    900,
                    block.bodyReadTimeoutSeconds().intValue(),
                    "body-read-timeout-seconds binds bodyReadTimeoutSeconds");
            assertEquals(
                    45,
                    block.streamIdleTimeoutSeconds().intValue(),
                    "stream-idle-timeout-seconds binds streamIdleTimeoutSeconds");
        }
    }

    /** The entry-level (highest-precedence) overrides bind on {@code [[janus.model-list]]}. */
    @Test
    void bindsModelListEntryTimeoutComponents() throws IOException {
        String toml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [[janus.model-list]]
                name = "llama3"
                provider = "ollama"
                connect-timeout-seconds = 4
                header-timeout-seconds = 180
                body-read-timeout-seconds = 600
                stream-idle-timeout-seconds = 30
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "entry-timeouts",
                        new TomlPropertySourceLoader()
                                .read(
                                        "entry-timeouts",
                                        new java.io.ByteArrayInputStream(
                                                toml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig.ModelListEntry entry =
                    context.getBean(JanusConfig.class).modelList().get(0);
            assertEquals(4, entry.connectTimeoutSeconds().intValue());
            assertEquals(180, entry.headerTimeoutSeconds().intValue());
            assertEquals(600, entry.bodyReadTimeoutSeconds().intValue());
            assertEquals(30, entry.streamIdleTimeoutSeconds().intValue());
        }
    }

    @Test
    void underscoreEntryTimeoutKeysSilentlyNull() throws IOException {
        // Negative pin (the api_key_env/base_url precedent): array-of-tables
        // element keys keep their RAW spellings — header_timeout_seconds does NOT
        // bind to headerTimeoutSeconds (silently null ⇒ the override is silently
        // absent). The kebab spelling stays the documented one.
        String toml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [[janus.model-list]]
                name = "llama3"
                provider = "ollama"
                header_timeout_seconds = 180
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "underscore-entry-timeouts",
                        new TomlPropertySourceLoader()
                                .read(
                                        "underscore-entry-timeouts",
                                        new java.io.ByteArrayInputStream(
                                                toml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig.ModelListEntry entry =
                    context.getBean(JanusConfig.class).modelList().get(0);
            assertEquals("llama3", entry.name());
            assertNull(entry.headerTimeoutSeconds(), "header_timeout_seconds must NOT bind to headerTimeoutSeconds");
        }
    }

    /**
     * fail-fast empirical pin: a non-positive override on a model-list entry
     * or provider block is rejected by the record's compact constructor (naming
     * the key — pinned record-level in {@code ModelListFactoryTest}), but
     * Micronaut's <b>collection-element</b> binding swallows the construction
     * failure and drops the offending element instead of failing the boot — the
     * SAME pre-existing behavior a blank {@code name}/{@code provider} entry hits
     * (the route vanishes; the alias 404s {@code model_not_found} at request
     * time). Pinned here so a framework upgrade that turns this into a boot
     * failure updates the pin consciously. (The {@code [janus.timeouts]} SECTION
     * does fail the boot — {@code nonPositiveTimeoutsValueFailsFastAtBinding}
     * above — a nested configuration-properties bean is not a collection
     * element.)
     */
    @Test
    void nonPositiveOverrideDropsTheElementAtBinding() throws IOException {
        String badToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [[janus.model-list]]
                name = "llama3"
                provider = "ollama"
                header-timeout-seconds = 0

                [janus.providers.ollama]
                wire-format = "openai-compatible"
                connect-timeout-seconds = 0
                """;
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(PropertySource.of(
                        "bad-override-timeouts",
                        new TomlPropertySourceLoader()
                                .read(
                                        "bad-override-timeouts",
                                        new java.io.ByteArrayInputStream(
                                                badToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                .start()) {
            JanusConfig config = context.getBean(JanusConfig.class);
            assertTrue(
                    config.modelList() == null || config.modelList().isEmpty(),
                    "the entry with the non-positive override is dropped (not bound)");
            assertTrue(
                    config.providers() == null || config.providers().isEmpty(),
                    "the provider block with the non-positive override is dropped (not bound)");
        }
    }

    // ------------------------------------------------------ [janus.store] block

    @Test
    void bindsStoreSectionToStoreConfig() {
        try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                .propertySources(loadTestToml("store-test.toml"))
                .start()) {
            JanusConfig.StoreConfig store = context.getBean(JanusConfig.class).store();
            assertEquals("postgres", store.type());
            assertEquals("JANUS_DB_URL", store.jdbcUrlEnv());
            assertEquals("JANUS_DB_USER", store.userEnv());
            assertEquals("JANUS_DB_PASS", store.passwordEnv());
            assertEquals(25, store.maxPoolSize().intValue(), "max-pool-size binds maxPoolSize");
            assertEquals(500, store.retention().intValue(), "retention binds retention");
        }
    }

    @Test
    void unknownStoreTypeFailsFastAtBinding() throws IOException {
        // fail-fast : an unknown [janus.store] type refuses the
        // node to start — the record's compact constructor rejects it at binding time.
        String badToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.store]
                type = "redis"
                """;
        BeanCreationException e = assertThrows(BeanCreationException.class, () -> {
            try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                    .propertySources(PropertySource.of(
                            "bad-store",
                            new TomlPropertySourceLoader()
                                    .read(
                                            "bad-store",
                                            new java.io.ByteArrayInputStream(
                                                    badToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                    .start()) {
                // Force the config binding — Micronaut config records bind lazily.
                context.getBean(JanusConfig.class);
            }
        });
        assertTrue(
                e.getMessage().contains("memory") && e.getMessage().contains("postgres"),
                "the fail-fast error lists the two valid variants: " + e.getMessage());
    }

    @Test
    void postgresWithoutJdbcUrlEnvFailsFastAtBinding() throws IOException {
        // fail-fast: type = "postgres" without jdbc-url-env is a misconfiguration
        // at binding time — the node must never silently fall back to memory.
        String badToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.store]
                type = "postgres"
                """;
        BeanCreationException e = assertThrows(BeanCreationException.class, () -> {
            try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                    .propertySources(PropertySource.of(
                            "bad-store",
                            new TomlPropertySourceLoader()
                                    .read(
                                            "bad-store",
                                            new java.io.ByteArrayInputStream(
                                                    badToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                    .start()) {
                // Force the config binding — Micronaut config records bind lazily.
                context.getBean(JanusConfig.class);
            }
        });
        assertTrue(
                e.getMessage().contains("jdbc-url-env"),
                "the fail-fast error names the missing knob: " + e.getMessage());
    }

    @Test
    void slidingWindowWithPostgresStoreFailsFastAtBinding() throws IOException {
        // Out-of-scope divergence: a sliding-window limiter
        // with a postgres store would be silently ignored (PgRateLimiter is
        // fixed-window only), so the combination is rejected at binding time — the
        // node refuses to start rather than enforce different semantics than the
        // operator asked for ( fail-fast philosophy, recorded in docs/clustering.md).
        String badToml = """
                [janus]
                name = "janus"
                version = "0.1.0-SNAPSHOT"

                [janus.limits]
                window = "sliding"

                [janus.store]
                type = "postgres"
                jdbc-url-env = "JANUS_DB_URL"
                """;
        BeanCreationException e = assertThrows(BeanCreationException.class, () -> {
            try (ApplicationContext context = ApplicationContext.builder(Environment.TEST)
                    .propertySources(PropertySource.of(
                            "sliding-postgres",
                            new TomlPropertySourceLoader()
                                    .read(
                                            "sliding-postgres",
                                            new java.io.ByteArrayInputStream(
                                                    badToml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))))
                    .start()) {
                // Force the config binding — Micronaut config records bind lazily.
                context.getBean(JanusConfig.class);
            }
        });
        assertTrue(
                e.getMessage().contains("sliding") && e.getMessage().contains("postgres"),
                "the fail-fast error names the conflict: " + e.getMessage());
    }
}
