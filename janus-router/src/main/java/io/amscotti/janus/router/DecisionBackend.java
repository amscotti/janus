package io.amscotti.janus.router;

import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;

/**
 * Provider-agnostic seam the {@link DecisionRouter} routes to — the decision
 * analogue of {@link ChatBackend}. Deliberately not the janus-provider {@code
 * DecisionAdapter} (no {@code auth}) so the router stays within the
 * "router depends on core only" boundary; the gateway adapts a {@code
 * DecisionAdapter} to this interface.
 *
 * <p>Backends are stateless and thread-safe; {@code meta} passes through
 * untouched (gateway-internal, never serialized); failures surface as the
 * adapted provider's unchecked typed exceptions, propagated as-is.
 */
public interface DecisionBackend {

    /** Provider name — display/router key, stable, unique, lower-case. */
    String name();

    /** Normalized upstream base URL (identity dimension for observability). */
    String baseUrl();

    /** One decision evaluation. */
    DecisionResponse decide(DecisionRequest request);

    /**
     * Whether this backend accepts image evidence ({@code images[]} on the
     * request) — the decision-model-list {@code vision} flag. The router
     * routes image-bearing requests to capable candidates only, so a mixed
     * pool degrades deterministically instead of failing on round-robin
     * position; a pool with no capable candidate is a typed client error
     * before dispatch. Default {@code false}: an adapter must opt in.
     */
    default boolean supportsImages() {
        return false;
    }
}
