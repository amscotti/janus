package io.amscotti.janus.gateway;

import io.amscotti.janus.provider.ProviderException;
import io.amscotti.janus.router.DefaultRetryClassifier;
import io.amscotti.janus.router.RetryClassifier;

/**
 * The provider-aware {@link RetryClassifier} the
 * composition root wires into the router — the documented hand-off bridge. Real
 * adapter failures surface as {@code ProviderException} (janus-provider), which the
 * router must never import (AGENTS.md: provider and router depend on core only), so
 * this gateway-side classifier maps {@code ProviderException.retryable} — 429 /
 * upstream 5xx / network / timeout — to the router's retry decision; everything else
 * falls through to {@link DefaultRetryClassifier.INSTANCE} (router {@link
 * io.amscotti.janus.router.BackendException} retryable flag, and {@code false} for any
 * unknown throwable, {@link Error} or null — the default semantics, so the gateway's
 * existing single-attempt behavior for non-retryable failures is preserved exactly).
 *
 * <p><b>Why a classifier and not a backend translation.</b> The gateway's /
 * error-envelope mappers already map {@code ProviderException} to HTTP errors;
 * translating at {@code ProviderAdapterChatBackend} would orphan that path. The router
 * records health/breaker failures only for <b>retryable (transport-class)</b> errors —
 * non-retryable client errors (4xx/auth/bad-payload) never count against an upstream
 * — and the misbehaving-classifier guard already protects against a
 * throwing classifier.
 *
 * <p>Stateless singleton; native-image safe (direct type reference, no reflection).
 */
final class ProviderRetryClassifier implements RetryClassifier {

    /** Shared instance — stateless by construction. */
    static final RetryClassifier INSTANCE = new ProviderRetryClassifier();

    private ProviderRetryClassifier() {}

    @Override
    public boolean isRetryable(Throwable error) {
        if (error instanceof ProviderException providerException) {
            return providerException.retryable();
        }
        return DefaultRetryClassifier.INSTANCE.isRetryable(error);
    }
}
