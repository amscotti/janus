package io.amscotti.janus.store;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The in-memory price table: {@link PricingRate} rows keyed by <b>model alias</b>
 * (see {@link PricingRate} for the alias-vs-resolved-model divergence note),
 * built from the operator's {@code [[janus.pricing.models]]} TOML by the gateway's
 * {@code GovernanceFactory} — this class stays Micronaut-free (AGENTS.md boundary:
 * store depends on core only) and takes a plain {@code Map<String, PricingRate>}.
 *
 * <p><b>Per-backend override.</b> {@link #rateFor(String, String)} —
 * the pricing surface the cost-based load balancer prices through — treats a row
 * keyed by a <b>backend (provider) name</b> as a per-backend override for that
 * backend, preferred over the alias row when both resolve. One alias served by two
 * providers at two prices (OpenRouter "claude-3-5-sonnet" vs direct Anthropic) is
 * exactly the multi-provider-per-alias case cost-based routing exists for: the
 * operator adds a row keyed by the cheaper provider's name and the LB can finally
 * compare per-backend spend. The alias-only {@link #rateFor(String)} (governance
 * metering, scope-by-alias pricing) is unchanged — a backend-keyed row does not
 * disturb ledger metering for aliases that share the name. Rows keyed by neither the
 * alias nor any backend name are simply unreachable (they price nothing).
 *
 * <p><b>Unknown model ⇒ zero rate, logged once.</b> {@link #rateFor} falls back to
 * {@link PricingRate#ZERO} for aliases with no row (Pricing.rates_for
 * zero-rate fallback) and logs a warning the first time each unknown alias is seen —
 * never per request — so metering never crashes on a new model and operators hear
 * about missing rows exactly once. The once-logged alias set is <b>bounded</b> and the
 * alias is <b>sanitized</b> before logging: the alias is client-controlled, so an
 * unbounded set is a memory-DoS and a raw echo is a log-forgery surface (embedded
 * newlines forge log records) — distinct unknown aliases beyond
 * {@value #MAX_UNKNOWN_LOGGED} stop being logged (the zero-rate fallback is unchanged),
 * and the stored/logged form strips control characters and truncates to
 * {@value #MAX_MODEL_LOG_LENGTH} chars. {@code rateFor} is thread-safe and cheap
 * (O(1) map read + a set-add on a miss).
 *
 * <p>Immutable: the constructor copies the input map into an unmodifiable map;
 * {@link #EMPTY} is the no-pricing valid boot state (every alias ⇒ zero rate ⇒
 * $0 metering until the operator adds rows).
 */
public final class PriceTable {

    private static final Logger LOG = System.getLogger("io.amscotti.janus.store.PriceTable");

    /** No rows configured: every alias prices at zero (a valid boot state). */
    public static final PriceTable EMPTY = new PriceTable(Map.of());

    /** The cap on distinct once-logged unknown aliases (the set never grows unbounded). */
    static final int MAX_UNKNOWN_LOGGED = 1024;

    /** The max length of an alias echoed into a log line (log-forgery hygiene). */
    static final int MAX_MODEL_LOG_LENGTH = 128;

    private final Map<String, PricingRate> rates;
    private final boolean requirePriced;
    private final Set<String> loggedUnknown = ConcurrentHashMap.newKeySet();

    private PriceTable(Map<String, PricingRate> rates) {
        this(rates, false);
    }

    private PriceTable(Map<String, PricingRate> rates, boolean requirePriced) {
        Map<String, PricingRate> copy = new LinkedHashMap<>();
        for (Map.Entry<String, PricingRate> entry : rates.entrySet()) {
            String alias = Objects.requireNonNull(entry.getKey(), "model alias");
            if (alias.isBlank()) {
                throw new IllegalArgumentException("model alias must not be blank");
            }
            copy.put(alias, Objects.requireNonNull(entry.getValue(), "rate for alias " + alias));
        }
        this.rates = Map.copyOf(copy);
        this.requirePriced = requirePriced;
    }

    /**
     * Build a table from a plain map (the gateway factory builds it from TOML; tests
     * build it directly). The map is defensively copied into an unmodifiable table.
     */
    public static PriceTable of(Map<String, PricingRate> rates) {
        return of(rates, false);
    }

    /**
     * @param requirePriced when true, {@link #rateFor(String)} throws
     *     {@link UnpricedModelException} instead of returning {@link PricingRate#ZERO}
     */
    public static PriceTable of(Map<String, PricingRate> rates, boolean requirePriced) {
        Objects.requireNonNull(rates, "rates");
        return new PriceTable(rates, requirePriced);
    }

    /** True when {@code model} has a configured row (not the zero-rate fallback). */
    public boolean contains(String model) {
        return model != null && rates.containsKey(model);
    }

    public boolean requirePriced() {
        return requirePriced;
    }

    /**
     * The row for {@code model} (the client alias), or {@link PricingRate#ZERO} when
     * unknown (logged once per alias, bounded + sanitized — see the class javadoc).
     * Never returns null. Throws {@link UnpricedModelException} when
     * {@link #requirePriced} is true and the alias has no row.
     */
    public PricingRate rateFor(String model) {
        if (model == null) {
            return PricingRate.ZERO;
        }
        PricingRate rate = rates.get(model);
        if (rate != null) {
            return rate;
        }
        if (requirePriced) {
            throw new UnpricedModelException(model);
        }
        String safe = sanitizeForLog(model);
        // Hardened: the earlier add-then-size-check guard relied on
        // {@code ConcurrentHashMap.size} being a moment-in-time snapshot — it is not
        // under concurrent updates (it sums striped counters), so two racers could both
        // read ≤ MAX and both keep, permanently overshooting the cap (flaky
        // loggedUnknownSetStaysWithinTheCapUnderAConcurrentDistinctAliasBurst). The
        // monitor makes check-and-insert atomic; the path runs only on a FIRST unknown
        // alias (the set is the once-logged guard), so contention is irrelevant.
        boolean logIt;
        synchronized (loggedUnknown) {
            if (loggedUnknown.contains(safe)) {
                logIt = false;
            } else if (loggedUnknown.size() < MAX_UNKNOWN_LOGGED) {
                loggedUnknown.add(safe);
                logIt = true;
            } else {
                logIt = false;
            }
        }
        if (logIt) {
            LOG.log(
                    Level.WARNING,
                    "no price configured for model \"{0}\" — metering at $0 until a [[janus.pricing.models]] "
                            + "row is added (logged once)",
                    safe);
        }
        return PricingRate.ZERO;
    }

    /**
     * The effective rate for a response served by {@code backendName} for client alias
     * {@code model}: a row keyed by the <b>backend (provider) name</b> wins as a
     * per-backend override, else the alias row (zero-rate fallback, logged once per
     * unknown alias — the cost-based LB's pricing surface). A null/unknown
     * {@code backendName} never suppresses the alias fallback.
     */
    public PricingRate rateFor(String model, String backendName) {
        if (backendName != null) {
            PricingRate backendRate = rates.get(backendName);
            if (backendRate != null) {
                return backendRate;
            }
        }
        return rateFor(model);
    }

    /** True when the table carries no pricing rows at all (a zero-rate boot state). */
    public boolean isEmpty() {
        return rates.isEmpty();
    }

    /** Package-private observability seam (the growth-bound test). */
    int loggedUnknownSize() {
        return loggedUnknown.size();
    }

    /**
     * The alias form that may reach a log line: control characters become spaces
     * (an embedded newline must not forge a second log record) and the value is
     * truncated to {@value #MAX_MODEL_LOG_LENGTH} chars. Also the stored dedup key, so
     * the once-logged set itself is bounded in memory.
     */
    static String sanitizeForLog(String model) {
        StringBuilder sb = new StringBuilder(model.length());
        for (int i = 0; i < model.length(); i++) {
            char c = model.charAt(i);
            sb.append(c < 0x20 || c == 0x7f ? ' ' : c);
        }
        if (sb.length() <= MAX_MODEL_LOG_LENGTH) {
            return sb.toString();
        }
        return sb.substring(0, MAX_MODEL_LOG_LENGTH) + "...";
    }
}
