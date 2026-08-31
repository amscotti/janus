package io.amscotti.janus.gateway;

import io.amscotti.janus.JanusConfig;
import io.amscotti.janus.store.CallStore;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import io.amscotti.janus.store.RateLimiter;
import io.amscotti.janus.store.SpendLedger;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Composition root for the governance beans (the {@code KeyStoreFactory}
 * pattern): the {@link PriceTable} built from {@code [[janus.pricing.models]]}, the
 * {@link Notifier} (logger default, webhook when a URL is configured) and the
 * {@link Governance} collaborator both controllers inject — all sharing the /
 * {@link Clock} bean (produced by {@link CallStoreFactory}, no-real-time
 * discipline). Tests replace this factory with the fixed-clock governance
 * ({@code TestGovernanceFactory}).
 *
 * <p><b>Constructor slimming.</b> The {@link RateLimiter}/{@link SpendLedger} beans moved to
 * {@link CallStoreFactory} (derived views of the one {@link CallStore} bean — the
 * "one object, three bean types" handoff), and the {@code [janus.limits] window}
 * selection moved into the {@code CallStoreFactory}'s memory branch (token-bucket
 * stays memory-only in documented in {@code PgRateLimiter}). This factory keeps
 * the {@code PriceTable}/{@code Notifier}/{@code Governance} wiring; {@code
 * Governance}'s constructor takes the three seams, which DI resolves to the derived
 * beans (the same {@code CallStore} instance) plus the call-ledger view.
 *
 * <p><b>Defaults live here, not in the binding records ( {@code [janus.router]}
 * precedent).</b> Absent sections / null components resolve to: empty price table
 * (zero-rate metering), 0.8 soft-cap fraction, logger-only notifier. Enforcement is
 * <b>key-scoped</b> — a key's null {@code rpm}/{@code tpm}/{@code budgetUsd} means
 * "no cap", and no key attached means nothing happens — so these defaults reproduce
 * keyless behavior byte-identically (the noop-level guarantee;
 * {@code Governance.noop} is the explicit limits-off instance tests use).
 */
@Factory
class GovernanceFactory {

    private static final Logger LOG = LoggerFactory.getLogger(GovernanceFactory.class);

    @Singleton
    PriceTable priceTable(JanusConfig config) {
        JanusConfig.PricingConfig pricing = config.pricing();
        boolean requirePriced = pricing != null && Boolean.TRUE.equals(pricing.requirePriced());
        if (pricing == null || pricing.models() == null || pricing.models().isEmpty()) {
            if (requirePriced
                    && config.modelList() != null
                    && !config.modelList().isEmpty()) {
                throw new IllegalArgumentException(
                        "[janus.pricing] require-priced = true but no [[janus.pricing.models]] rows are configured");
            }
            return PriceTable.EMPTY; // no rows ⇒ zero-rate metering (valid boot unless require-priced)
        }
        Map<String, PricingRate> rates = new LinkedHashMap<>();
        for (JanusConfig.PricingConfig.PricingModel model : pricing.models()) {
            // Fail-fast on a duplicate row, like every other pricing drift check
            // (require-priced, the unknown-window value): the table is keyed by name,
            // so two rows sharing one would otherwise silently last-win — metering
            // bills at whichever row the map happened to keep, an operator editing the
            // table has no way to see which rates survived. The name space is shared
            // by alias rows and backend-override rows, so a collision between those is
            // equally ambiguous and equally rejected.
            if (rates.containsKey(model.name())) {
                throw new IllegalArgumentException("[janus.pricing] duplicate [[janus.pricing.models]] row for \""
                        + model.name() + "\" — a name may carry exactly one row (the later row would silently "
                        + "replace the earlier one's rates)");
            }
            rates.put(
                    model.name(),
                    new PricingRate(
                            model.inputPer1k() == null ? 0.0 : model.inputPer1k(),
                            model.outputPer1k() == null ? 0.0 : model.outputPer1k(),
                            model.cacheReadPer1k() == null ? 0.0 : model.cacheReadPer1k(),
                            model.cacheCreationPer1k() == null ? 0.0 : model.cacheCreationPer1k(),
                            model.defaultMaxTokens() == null ? 0 : model.defaultMaxTokens(),
                            model.webSearchPer1k() == null ? 0.0 : model.webSearchPer1k(),
                            model.longContextThreshold() == null ? 0 : model.longContextThreshold(),
                            model.longInputPer1k() == null ? 0.0 : model.longInputPer1k(),
                            model.longOutputPer1k() == null ? 0.0 : model.longOutputPer1k(),
                            model.longCacheReadPer1k() == null ? 0.0 : model.longCacheReadPer1k(),
                            model.longCacheCreationPer1k() == null ? 0.0 : model.longCacheCreationPer1k()));
        }
        PriceTable table = PriceTable.of(rates, requirePriced);
        if (requirePriced && config.modelList() != null) {
            for (JanusConfig.ModelListEntry entry : config.modelList()) {
                if (!table.contains(entry.name())) {
                    throw new IllegalArgumentException("[janus.pricing] require-priced = true but alias \""
                            + entry.name()
                            + "\" has no [[janus.pricing.models]] row");
                }
            }
        }
        return table;
    }

    @Singleton
    Notifier notifier(JanusConfig config, Clock clock) {
        JanusConfig.LimitsConfig limits = config.limits();
        String url = limits == null ? null : limits.notifierWebhookUrl();
        Notifier sink = url != null && !url.isBlank() ? webhookOrLogger(url) : new LoggingNotifier();
        // Once-per-key-per-window dedup for the
        // :budget_exceeded event — a key parked over the soft line warns once per
        // 60s window, not on every request (see DedupNotifier).
        return new DedupNotifier(sink, clock);
    }

    /**
     * A syntactically-invalid {@code notifier-webhook-url} must NOT abort
     * the gateway boot ({@code URI.create} throws {@code IllegalArgumentException}
     * inside the bean factory otherwise). The URL is validated here; on a bad value the
     * gateway boots with the logger-only notifier and the misconfiguration is logged —
     * the webhook silently not notifies (the {@link WebhookNotifier} is additionally
     * lazy-parse-tolerant, so a bad URL can never raise into {@code notify}).
     *
     * <p>A <em>syntactically-valid</em> URL with a non-{@code http(s)} scheme
     * ({@code ftp://}, {@code file://}, {@code mailto:}, …) is not a webhook — it would
     * build a {@link WebhookNotifier} whose every {@code notify} fails at
     * {@code HttpRequest.newBuilder} (caught, logged, dropped) instead of the documented
     * "falls back to {@link LoggingNotifier}" path. The scheme is therefore required to
     * be {@code http}/{@code https} here; the log names only the (safe) scheme, never
     * the URL (a webhook URL may carry a credential in a query parameter).
     */
    private static Notifier webhookOrLogger(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || !(SCHEME_HTTP.equals(scheme) || SCHEME_HTTPS.equals(scheme))) {
                LOG.warn(
                        "configured notifier-webhook-url must use http or https (got {}) — falling back to the"
                                + " logger-only notifier (webhooks disabled)",
                        scheme == null ? "no scheme" : "\"" + scheme + "\"");
                return new LoggingNotifier();
            }
            // A connect timeout bounds the connection phase — a webhook host
            // that never accepts must not hold the send forever (the request builder's
            // own 10s timeout in WebhookNotifier bounds the whole send).
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            return new WebhookNotifier(client, url);
        } catch (IllegalArgumentException e) {
            // Never echo the URL or its exception message — a webhook URL may carry a
            // credential in a query parameter (secret hygiene; discipline).
            LOG.warn(
                    "configured notifier-webhook-url is not a valid URL — falling back to the logger-only"
                            + " notifier (webhooks disabled): {}",
                    e.getClass().getSimpleName());
            return new LoggingNotifier();
        }
    }

    private static final String SCHEME_HTTP = "http";
    private static final String SCHEME_HTTPS = "https";

    @Singleton
    Governance governance(
            JanusConfig config,
            RateLimiter rateLimiter,
            PriceTable priceTable,
            SpendLedger spendLedger,
            Notifier notifier,
            Clock clock,
            MetricsRecorder metricsRecorder,
            CallStore callStore) {
        JanusConfig.LimitsConfig limits = config.limits();
        double softCapFraction = JanusConfig.LimitsConfig.DEFAULTS.softCapFraction();
        if (limits != null && limits.softCapFraction() != null) {
            softCapFraction = limits.softCapFraction();
        }
        // No provider resolver here by design: a model-alias resolver can only answer
        // Router.route's config-first candidate, which is NOT the backend that served
        // the request under balancing/retry failover. The call ledger's provider is
        // threaded per request from the dispatch seam instead — the controllers capture
        // the router's dispatch-observer delivery (ModelFaceControllerSupport) and pass
        // it into finalize/wrapStream/recordFailure.
        return new Governance(
                rateLimiter, priceTable, spendLedger, notifier, softCapFraction, clock, metricsRecorder, callStore);
    }
}
