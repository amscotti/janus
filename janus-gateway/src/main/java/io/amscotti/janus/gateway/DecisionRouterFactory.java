package io.amscotti.janus.gateway;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.provider.DecisionAdapter;
import io.amscotti.janus.provider.OpenAiDecisionsAdapter;
import io.amscotti.janus.provider.SystemOneAdapter;
import io.amscotti.janus.router.DecisionBackend;
import io.amscotti.janus.router.DecisionRouter;
import io.amscotti.janus.router.RetryPolicy;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Composition root producing the {@link DecisionRouter} singleton from
 * {@code [[janus.decision-model-list]]} + {@code
 * [janus.decision-providers.*]} — the decision analogue of {@link
 * RouterFactory} (docs/system-one.md). Retry knobs reuse the shared {@code
 * [janus.router]} section ({@code max-retries}/{@code backoff-*}/{@code
 * jitter}); the load-balancer strategy does not apply (the decision router
 * round-robins). An absent decision-model list is a valid boot state: an
 * empty decision router 404s every decision alias {@code model_not_found}.
 *
 * <p>The direct {@link SystemOneAdapter}/{@link OpenAiDecisionsAdapter}
 * references keep both ServiceLoader registrations reachable in native-image.
 */
@Factory
class DecisionRouterFactory {

    private static final Logger LOG = System.getLogger("io.amscotti.janus.gateway.DecisionRouterFactory");

    private final JanusConfig config;

    DecisionRouterFactory(JanusConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Singleton
    DecisionRouter decisionRouter() {
        JanusConfig.TimeoutsConfig timeouts = RouterFactory.resolve(config.timeouts());
        Map<String, List<DecisionBackend>> routes = DecisionModelListFactory.buildDecisionRoutes(
                config.decisionModelList(),
                config.decisionProviders() == null ? Map.of() : config.decisionProviders(),
                System::getenv,
                timeouts,
                DecisionRouterFactory::createDecisionAdapter);
        warnAboutIgnoredStrategy(config.router());
        JanusConfig.RouterConfig r = LoadBalancerFactory.resolve(config.router());
        RetryPolicy retry = new RetryPolicy(r.maxRetries(), r.backoffBaseMs(), r.backoffMaxMs(), r.jitter());
        return new DecisionRouter(routes, retry, ProviderRetryClassifier.INSTANCE);
    }

    /**
     * The decision plane round-robins: the chat router's {@code strategy} (and its
     * weights/alpha) does not apply, and an operator who sets one must not silently
     * assume decisions follow it — warn at boot (the ignored-knobs precedent of
     * {@code LoadBalancerFactory.warnAboutWeights}). Package-private so a test pins
     * the text.
     */
    static String warnAboutIgnoredStrategy(JanusConfig.RouterConfig router) {
        if (router == null
                || router.strategy() == null
                || router.strategy().isBlank()
                || "round-robin".equalsIgnoreCase(router.strategy())) {
            return null;
        }
        String warning = "[janus.router] strategy \"" + router.strategy()
                + "\" does not apply to decision routing — the decision plane round-robins;"
                + " only max-retries/backoff-*/jitter are shared with it";
        LOG.log(Level.WARNING, warning);
        return warning;
    }

    /**
     * Explicit decision-adapter constructor switch (no reflection) keyed by
     * the resolved wire-format family. The systemone family is named by the
     * operator's provider name and has no default base URL (a blank base
     * fails fast); openai-decisions defaults to OpenAI's base.
     */
    static DecisionAdapter createDecisionAdapter(
            String providerName,
            String wireFormatKey,
            String baseUrl,
            String apiKey,
            JanusConfig.TimeoutsConfig timeouts) {
        JanusConfig.TimeoutsConfig t = RouterFactory.resolve(timeouts);
        Duration connect = Duration.ofSeconds(t.connectTimeoutSeconds());
        Duration header = Duration.ofSeconds(t.headerTimeoutSeconds());
        Duration bodyRead = Duration.ofSeconds(t.bodyReadTimeoutSeconds());
        // The wire-format values deliberately equal the adapter NAMEs, so the
        // registered-name key and the wire-format fallback key are one case each.
        return switch (wireFormatKey) {
            case SystemOneAdapter.NAME ->
                new SystemOneAdapter(providerName, baseUrl, apiKey, connect, header, bodyRead);
            case OpenAiDecisionsAdapter.NAME -> {
                String resolved =
                        baseUrl == null || baseUrl.isBlank() ? OpenAiDecisionsAdapter.DEFAULT_BASE_URL : baseUrl;
                yield new OpenAiDecisionsAdapter(resolved, apiKey, connect, header, bodyRead);
            }
            default ->
                throw new IllegalStateException(
                        "no decision adapter constructor registered for wire format \"" + wireFormatKey + "\"");
        };
    }
}
