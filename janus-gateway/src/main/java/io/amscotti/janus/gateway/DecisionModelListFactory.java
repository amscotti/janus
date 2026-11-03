package io.amscotti.janus.gateway;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.JanusConfig.DecisionModelListEntry;
import io.amscotti.janus.JanusConfig.DecisionProviderEntry;
import io.amscotti.janus.provider.DecisionAdapter;
import io.amscotti.janus.router.DecisionBackend;
import io.micronaut.core.annotation.Nullable;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns {@code [[janus.decision-model-list]]} entries into the decision
 * router's alias → ordered candidate-list map — the decision analogue of
 * {@link ModelListFactory} (docs/system-one.md). Same rules: duplicate
 * aliases group into one pool, resolved backend names must be unique per
 * alias, the {@code [janus.decision-providers.<name>]} block supplies
 * {@code base-url}/{@code api-key-env}/timeout defaults under entries that
 * omit them, credentials resolve from env-var <em>names</em> only, and an
 * unknown provider without a wire-format block fails fast at boot listing
 * the known adapters.
 *
 * <p>Backend naming follows the chat rule: the generic {@code systemone}
 * family keeps the entry's provider name; the fixed-name {@code
 * openai-decisions} family dispatches under the adapter's own name.
 */
final class DecisionModelListFactory {

    private static final Logger LOG = System.getLogger("io.amscotti.janus.gateway.DecisionModelListFactory");

    /** {@code api-key-env} is a NAME, never a secret value. */
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private DecisionModelListFactory() {}

    /** Production: {@link DecisionRouterFactory}'s explicit constructor switch. Test: a fake. */
    @FunctionalInterface
    interface DecisionProviderConstructor {
        DecisionAdapter create(
                String providerName,
                String wireFormatKey,
                String baseUrl,
                String apiKey,
                JanusConfig.TimeoutsConfig timeouts);
    }

    static Map<String, List<DecisionBackend>> buildDecisionRoutes(
            @Nullable List<DecisionModelListEntry> entries,
            Map<String, DecisionProviderEntry> providers,
            Function<String, String> env,
            JanusConfig.TimeoutsConfig timeouts,
            DecisionProviderConstructor constructor) {
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(constructor, "constructor");
        providers.forEach(DecisionModelListFactory::validateBlock);
        if (entries == null || entries.isEmpty()) {
            warnDeadBlocks(providers, Set.of());
            return Collections.emptyMap();
        }
        Set<String> knownProviders = knownProviders();
        Map<String, List<DecisionBackend>> grouped = new LinkedHashMap<>();
        Map<String, Map<String, String>> claimed = new LinkedHashMap<>();
        Set<String> referencedProviders = new HashSet<>();
        for (DecisionModelListEntry entry : entries) {
            Objects.requireNonNull(entry, "decision-model-list entry");
            validateEntry(entry);
            String alias = entry.name();
            referencedProviders.add(entry.provider());
            DecisionBackend backend = backendFor(entry, providers, knownProviders, env, timeouts, constructor);
            String firstProvider = claimed.computeIfAbsent(alias, a -> new LinkedHashMap<>())
                    .putIfAbsent(backend.name(), entry.provider());
            if (firstProvider != null) {
                throw new IllegalStateException(
                        "duplicate backend name \"" + backend.name() + "\" for decision alias \""
                                + alias + "\": providers \"" + firstProvider + "\" and \"" + entry.provider()
                                + "\" resolve to the same backend"
                                + ("openai-decisions".equals(backend.name())
                                        ? " (the openai-decisions family uses one fixed backend name, so two"
                                                + " OpenAI Decisions entries cannot pool under one alias)"
                                        : " (two entries for one alias must resolve to different backend names)"));
            }
            grouped.computeIfAbsent(alias, a -> new ArrayList<>()).add(backend);
        }
        warnDeadBlocks(providers, referencedProviders);
        Map<String, List<DecisionBackend>> routes = new LinkedHashMap<>();
        grouped.forEach((alias, backends) -> routes.put(alias, Collections.unmodifiableList(backends)));
        return Collections.unmodifiableMap(routes);
    }

    /** Decision adapter names from the ServiceLoader — the fail-fast known-provider set. */
    static Set<String> knownProviders() {
        Set<String> names = new TreeSet<>();
        for (DecisionAdapter adapter : ServiceLoader.load(DecisionAdapter.class)) {
            names.add(adapter.name());
        }
        return names;
    }

    private static DecisionBackend backendFor(
            DecisionModelListEntry entry,
            Map<String, DecisionProviderEntry> providers,
            Set<String> knownProviders,
            Function<String, String> env,
            JanusConfig.TimeoutsConfig timeouts,
            DecisionProviderConstructor constructor) {
        String provider = entry.provider();
        DecisionProviderEntry block = providers.get(provider);
        String wireFormatKey;
        if (knownProviders.contains(provider)) {
            wireFormatKey = provider;
            if (block != null
                    && block.wireFormat() != null
                    && !block.wireFormat().isBlank()
                    && !block.wireFormat().equals(provider)) {
                LOG.log(
                        Level.WARNING,
                        "decision-providers block \"{0}\" sets wire-format \"{1}\", ignored because the provider"
                                + " name is a registered adapter (the registered name is authoritative)",
                        provider,
                        block.wireFormat());
            }
        } else if (block != null
                && block.wireFormat() != null
                && !block.wireFormat().isBlank()) {
            wireFormatKey = block.wireFormat();
        } else {
            throw new IllegalStateException(
                    "decision-model-list entry \"" + entry.name() + "\" references unknown decision provider \""
                            + provider + "\" (known decision providers: " + knownProviders
                            + "; or declare [janus.decision-providers." + provider + "] with a wire-format)");
        }
        String baseUrl = entry.baseUrl() != null ? entry.baseUrl() : (block == null ? null : block.baseUrl());
        String apiKeyEnv = entry.apiKeyEnv() != null ? entry.apiKeyEnv() : (block == null ? null : block.apiKeyEnv());
        JanusConfig.TimeoutsConfig resolved = resolveTimeouts(timeouts, block, entry);
        DecisionAdapter adapter =
                constructor.create(provider, wireFormatKey, baseUrl, resolveApiKey(apiKeyEnv, entry, env), resolved);
        // The adapter's own name is the backend identity (the systemone family was
        // constructed with the operator's provider name; openai-decisions is fixed).
        return new DecisionAdapterBackend(
                adapter, adapter.name(), entry.resolvedUpstreamModel(), entry.visionEnabled());
    }

    private static void validateEntry(DecisionModelListEntry entry) {
        if (entry.name() == null || entry.name().isBlank()) {
            throw new IllegalStateException("decision-model-list entry name must be non-blank");
        }
        if (entry.provider() == null || entry.provider().isBlank()) {
            throw new IllegalStateException(
                    "decision-model-list entry \"" + entry.name() + "\" provider must be non-blank");
        }
        if (entry.upstreamModel() != null && entry.upstreamModel().isBlank()) {
            throw new IllegalStateException(
                    "decision-model-list entry \"" + entry.name() + "\" upstream-model must be non-blank when present");
        }
        requirePositive(
                entry.connectTimeoutSeconds(),
                "decision-model-list entry \"" + entry.name() + "\" connect-timeout-seconds");
        requirePositive(
                entry.headerTimeoutSeconds(),
                "decision-model-list entry \"" + entry.name() + "\" header-timeout-seconds");
        requirePositive(
                entry.bodyReadTimeoutSeconds(),
                "decision-model-list entry \"" + entry.name() + "\" body-read-timeout-seconds");
    }

    private static void validateBlock(String name, DecisionProviderEntry block) {
        if (block == null) {
            return;
        }
        if (block.wireFormat() != null
                && !block.wireFormat().isBlank()
                && !JanusConfig.WIRE_FORMAT_SYSTEMONE.equals(block.wireFormat())
                && !JanusConfig.WIRE_FORMAT_OPENAI_DECISIONS.equals(block.wireFormat())) {
            throw new IllegalStateException("[janus.decision-providers." + name
                    + "] wire-format must be \"systemone\" or \"openai-decisions\" (got \"" + block.wireFormat()
                    + "\")");
        }
        requirePositive(
                block.connectTimeoutSeconds(), "[janus.decision-providers." + name + "] connect-timeout-seconds");
        requirePositive(block.headerTimeoutSeconds(), "[janus.decision-providers." + name + "] header-timeout-seconds");
        requirePositive(
                block.bodyReadTimeoutSeconds(), "[janus.decision-providers." + name + "] body-read-timeout-seconds");
    }

    private static void requirePositive(Integer value, String what) {
        if (value != null && value <= 0) {
            throw new IllegalStateException(what + " must be > 0 (got " + value + ")");
        }
    }

    private static void warnDeadBlocks(Map<String, DecisionProviderEntry> providers, Set<String> referencedProviders) {
        for (String provider : providers.keySet()) {
            if (!referencedProviders.contains(provider)) {
                LOG.log(
                        Level.WARNING,
                        "decision-providers block \"{0}\" is not referenced by any [[janus.decision-model-list]]"
                                + " entry (dead config)",
                        provider);
            }
        }
    }

    private static JanusConfig.TimeoutsConfig resolveTimeouts(
            JanusConfig.TimeoutsConfig global, @Nullable DecisionProviderEntry block, DecisionModelListEntry entry) {
        return new JanusConfig.TimeoutsConfig(
                firstNonNull(
                        entry.connectTimeoutSeconds(),
                        block == null ? null : block.connectTimeoutSeconds(),
                        global.connectTimeoutSeconds()),
                firstNonNull(
                        entry.headerTimeoutSeconds(),
                        block == null ? null : block.headerTimeoutSeconds(),
                        global.headerTimeoutSeconds()),
                firstNonNull(
                        entry.bodyReadTimeoutSeconds(),
                        block == null ? null : block.bodyReadTimeoutSeconds(),
                        global.bodyReadTimeoutSeconds()),
                global.streamIdleTimeoutSeconds());
    }

    private static Integer firstNonNull(Integer entryValue, Integer blockValue, Integer globalValue) {
        return entryValue != null ? entryValue : (blockValue != null ? blockValue : globalValue);
    }

    private static String resolveApiKey(String apiKeyEnv, DecisionModelListEntry entry, Function<String, String> env) {
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
            LOG.log(
                    Level.WARNING,
                    "decision-model-list entry \"{0}\": no api-key-env configured (entry or "
                            + "[janus.decision-providers.{1}] block) — the adapter will send no Authorization "
                            + "header (correct for local upstreams; a hosted upstream returns 401)",
                    entry.name(),
                    entry.provider());
            return "";
        }
        if (!ENV_NAME.matcher(apiKeyEnv).matches()) {
            // Never echo the configured value: a copy-pasted secret would land in
            // the log exactly when the operator is trying to diagnose it.
            LOG.log(
                    Level.WARNING,
                    "decision-model-list entry \"{0}\": api-key-env must be an environment-variable NAME"
                            + " ([A-Za-z_][A-Za-z0-9_]*), not a secret value — the adapter will send no"
                            + " Authorization header",
                    entry.name());
            return "";
        }
        String secret = env.apply(apiKeyEnv);
        if (secret == null || secret.isBlank()) {
            LOG.log(
                    Level.WARNING,
                    "decision-model-list entry \"{0}\": env var {1} is unset/blank — the adapter will send no "
                            + "Authorization header (a hosted upstream returns 401)",
                    entry.name(),
                    apiKeyEnv);
            return "";
        }
        return secret;
    }
}
