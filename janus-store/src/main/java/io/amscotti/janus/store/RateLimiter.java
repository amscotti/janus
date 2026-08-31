package io.amscotti.janus.store;

/**
 * Per-key, per-dimension rate limiting (the design names "rate-limit
 * counters" as {@code CallStore} operations, so this interface is the seam
 * extracts — {@code KeyStore} precedent). Three operations, mirroring the reference
 * {@code Core.RateLimiter} pipeline steps 5/9:
 *
 * <ul>
 * <li>{@link #tryAcquire} — <b>consume-on-allow</b> (the RPM hard gate): the counter
 * is incremented inside the same atomic step that checks it, so the cap is exact
 * under concurrency; a denied request is <b>not</b> counted. (Deliberate
 * divergence from the reference implementation semantics, which increments the request counter
 * <em>before</em> the cap check — denied requests count toward the same window
 * there, per {@code rate_limiter.ex}: "the request counter was still
 * incremented, so retries count toward the same window". Outcomes are
 * identical at the gate — the counter saturates at the cap either way and
 * {@code Retry-After} is time-based — so janus keeps the non-consuming denial
 * and documents the divergence.)
 * <li>{@link #wouldExceed} — <b>non-consuming pre-check</b> (the TPM gate): asks
 * "would {@code existing + estimate} cross the cap?" without touching the
 * counter, so a conservative estimate (request {@code max_tokens} /
 * {@code default_max_tokens}) can 429 a request before it would cross — the
 * documented the reference trade-off: the token cap "trips on the request after the one
 * that crossed" for real tokens.
 * <li>{@link #accumulate} — <b>consume at finalize</b> (TPM settle): real token
 * counts are accumulated once the upstream usage is known, so the counter
 * reflects actual spend. Accumulation may push the counter past the cap (the
 * cap gates the <em>next</em> request, not the one already in flight).
 * </ul>
 *
 * <p><b>Null caps mean unenforced (caller skips).</b> A {@code null} {@code rpm}/
 * {@code tpm} on the {@link KeyRecord} means "no cap", not "zero" — the gateway calls
 * these methods only when a cap is present, so this interface never sees a "disabled"
 * dimension. {@code keyId} is the non-secret {@link KeyRecord#id}.
 *
 * <p>Both shipped variants ({@link FixedWindowRateLimiter}, {@link TokenBucketRateLimiter})
 * are in-memory {@code ConcurrentHashMap}s keyed by key id + dimension with injectable
 * {@link java.time.Clock} ( discipline — deterministic tests, no real time on the
 * request path); cross-node correctness is the store layer's job.
 *
 * <p><b>Limits are per-key only.</b> Global/team-level limits, limit tiers by plan and
 * per-provider budgets are explicitly out of scope (
 * asks for per-key only; the reference implementation semantics has the rest).
 */
public interface RateLimiter {

    /**
     * The outcome of a gate operation: {@link Allowed} carries the resulting counter
     * value (requests in the window / tokens remaining), {@link Denied} carries the
     * {@code Retry-After} in whole seconds until the gate reopens.
     */
    sealed interface RateLimitResult permits RateLimitResult.Allowed, RateLimitResult.Denied {

        /** The operation succeeded and consumed/recorded its cost. */
        record Allowed(long count) implements RateLimitResult {}

        /**
         * The operation was refused; {@code retryAfterSeconds} is the whole seconds
         * until the gate reopens — ≥ 1 for both shipped variants (a request landing on
         * the exact rollover second recomputes the next window instead of being denied;
         * the ≥ 0 floor is defensive, matching the max(..., 1)).
         */
        record Denied(long retryAfterSeconds) implements RateLimitResult {}
    }

    /**
     * Check + consume the request-count dimension (RPM hard gate, the reference step 5):
     * {@code Allowed} iff {@code existing + cost ≤ limit} in the current window/bucket,
     * with the counter incremented atomically with the check. {@code Denied} returns
     * the seconds until the gate reopens — the caller must 429 with {@code Retry-After}
     * and must <b>not</b> dispatch upstream.
     *
     * @param keyId the {@link KeyRecord#id} owning the counter
     * @param limit the requests-per-minute cap (must be &gt; 0)
     * @param cost the cost of this request (1 for RPM; must be ≥ 0)
     */
    RateLimitResult tryAcquire(String keyId, int limit, long cost);

    /**
     * Non-consuming TPM pre-check : {@code true} iff
     * {@code existing + estimate > limit} — the request would cross the cap if
     * dispatched. The counter is untouched; real tokens land via {@link #accumulate}
     * at finalize.
     *
     * @param keyId the {@link KeyRecord#id} owning the counter
     * @param limit the tokens-per-minute cap (must be &gt; 0)
     * @param estimate conservative token estimate ({@code max_tokens} /
     * {@code default_max_tokens}); must be ≥ 0
     */
    boolean wouldExceed(String keyId, int limit, long estimate);

    /**
     * Consume {@code actual} tokens at finalize (TPM settle, the reference step 9). The count
     * may exceed {@code limit} — the cap gates the next request, not the one already
     * completed (the documented semantics).
     *
     * <p><b>Return value — pinned by </b> (this javadoc's deferral, completed):
     * the post-accumulation TPM counter value, <em>the window total</em> (cumulative
     * tokens consumed in the current window), matching {@link FixedWindowRateLimiter}.
     * The parity harness pins the concrete value through the composite
     * ({@code AbstractCallStoreContractTest.accumulateReturnValueIsPinnedThroughTheSeam}),
     * so {@code PostgresCallStore} inherits a concrete target and must return the
     * same quantity. The shipped sliding variant ({@link TokenBucketRateLimiter})
     * returns the same quantity for the same sequence (its window total is the net
     * tokens consumed, floored to whole tokens) — <b>one meaning across all variants
     *</b>, so a future consumer of the return (e.g. an {@code X-RateLimit-*}
     * header) gets identical values regardless of {@code [janus.limits] window}.
     *
     * @param keyId the {@link KeyRecord#id} owning the counter
     * @param limit the tokens-per-minute cap (must be &gt; 0)
     * @param actual real tokens from the upstream usage; must be ≥ 0
     */
    long accumulate(String keyId, int limit, long actual);
}
