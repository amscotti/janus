package io.amscotti.janus.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Key CRUD + auth-lookup seam. The interface exists now — not speculation —
 * because "key CRUD" belongs on the {@code CallStore}
 * operation with a Postgres implementation planned : {@code InMemoryKeyStore}
 * is the concrete default extracts, and the change is additive (semantics
 * documented here), not a rewrite.
 *
 * <p>Contract notes:
 *
 * <ul>
 * <li>{@link #create} returns the full key string <b>exactly once</b>; the stored
 * {@link KeyRecord} holds only {@code salt} + salted SHA-256 {@code secretHash}.
 * <li>{@link #list} returns redacted {@link KeyRecordView}s with <b>no</b> secret
 * material (the view type has no hash/salt fields — structural, not a filter).
 * <li>{@link #revoke} is idempotent: {@code true} iff a key with that id exists
 * (already-revoked counts as found); {@code false} for an unknown id. The
 * ACTIVE→REVOKED transition is atomic (a racing auth cannot pass a revoked key).
 * <li>{@link #authenticate} runs the whole verify → status → expiry →
 * {@code lastUsedAt} sequence as <b>one atomic transition</b> on the prefix
 * index, so a concurrent {@link #revoke} either lands before it (⇒
 * {@code REVOKED}) or after it (⇒ {@code OK} — the in-flight request that
 * already passed the check is the last one through; there is no torn state).
 * <li>{@link #touch} is best-effort CAS on {@code lastUsedAt} (never regresses the
 * clock); it is a no-op for unknown prefixes and non-{@code ACTIVE} records.
 * The auth path maintains {@code lastUsedAt} via {@link #authenticate}'s atomic
 * bump; {@code touch} is the explicit non-auth bump seam
 * {@code CallStore} union ( surface, parity-tested) — it has no production
 * caller today and exists so a future per-request non-auth touch or the
 * per-key usage series has the named seam rather than a silent
 * authenticate-only path.
 * </ul>
 */
public interface KeyStore {

    /** The outcome of {@link #create}: the stored record + the full key, shown once. */
    record CreatedKey(KeyRecord record, String fullKey) {}

    /**
     * Creation parameters. {@code models} is the per-key model scope (null/empty =
     * allow all); {@code expiresAt}/{@code budgetUsd}/{@code budgetDuration}/
     * {@code rpm}/{@code tpm} are nullable — the admin API exposes {@code models}/
     * {@code name}/{@code budget_usd}/{@code budget_duration}/{@code rpm}/
     * {@code tpm}/{@code duration} (the caps are enforced by {@code
     * Governance}).
     *
     * @param owner human-readable owner/label ("name" in the admin API); nullable
     * @param models per-key model scope; null/empty = allow all
     * @param expiresAt epoch millis after which the key no longer authenticates; null =
     * never expires
     * @param budgetUsd per-key budget cap in USD — data only, enforced by
     * @param budgetDuration per-key budget reset window in seconds; null = the budget
     * is lifetime (all-time), positive = the cap refills each aligned window — data
     * only, enforced by
     * @param rpm per-key requests-per-minute cap — data only, enforced by
     * @param tpm per-key tokens-per-minute cap — data only, enforced by
     */
    record KeyCreateRequest(
            String owner,
            List<String> models,
            Long expiresAt,
            Double budgetUsd,
            Long budgetDuration,
            Integer rpm,
            Integer tpm) {

        public KeyCreateRequest {
            models = models == null ? List.of() : List.copyOf(models);
        }
    }

    /**
     * The largest accepted {@code budgetDuration} (seconds): 10 years
     * (31,536,000 × 10 = 315,360,000). Above it the derived window epoch
     * {@code floorDiv(nowSec, dur) * dur} could be 0 — the lifetime row — so the
     * bound makes that alias unreachable.
     */
    long MAX_BUDGET_DURATION_SECONDS = 315_360_000L;

    /**
     * Rejects a {@link KeyCreateRequest} whose caps violate the <b>null-means-no-cap</b>
     * invariant that {@code Governance} (enforcement) depends on — both stores call this
     * at the top of {@link #create}. The admin API's checks are a courtesy, not the only
     * defense: {@code KeyStore} is a public seam for the future CLI, and Pg rows can be
     * seeded via SQL.
     *
     * <p>Why each rejection exists:
     *
     * <ul>
     * <li>A stored {@code rpm}/{@code tpm} of {@code 0} (or negative) would make the
     * rate limiter's {@code requirePositive} throw on <b>every</b> request for that key —
     * a 500 on the hot path, never a 429. "Deny all" is not expressible via 0 (null is
     * the only "no cap" spelling).
     * <li>A non-finite / non-positive {@code budgetUsd} would silently unbound the cap at
     * enforcement ({@code Governance} clamps to "no cap") and echo non-JSON literals in
     * {@code GET /key/list}.
     * <li>A budget whose micro-USD conversion saturates ({@code usd * 1e6 + 0.5 >=
     * Long.MAX_VALUE}) or rounds to zero (fewer than 1 micro-USD) collides with the
     * ledger's no-cap sentinel — the operator's cap would be silently unbound.
     * <li>A non-positive {@code budgetDuration} would make the ledger's window
     * arithmetic meaningless, and a duration above
     * {@value #MAX_BUDGET_DURATION_SECONDS} seconds (10 years) would silently alias
     * the lifetime window: {@code floorDiv(nowSec, dur) * dur == 0} whenever
     * {@code dur > nowSec} (~1.77e9 s ≈ 56 y), so an absurd duration maps the key's
     * windowed rows onto the window-0 lifetime row. The 10-year bound makes the
     * collision unreachable.
     * </ul>
     *
     * @throws IllegalArgumentException when a cap violates the invariant
     */
    static void validateCaps(KeyCreateRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.rpm() != null && request.rpm() <= 0) {
            throw new IllegalArgumentException("rpm must be positive (null = no cap), got " + request.rpm());
        }
        if (request.tpm() != null && request.tpm() <= 0) {
            throw new IllegalArgumentException("tpm must be positive (null = no cap), got " + request.tpm());
        }
        Double budgetUsd = request.budgetUsd();
        if (budgetUsd != null) {
            if (!Double.isFinite(budgetUsd) || budgetUsd <= 0) {
                throw new IllegalArgumentException(
                        "budgetUsd must be a positive finite number (null = no cap), got " + budgetUsd);
            }
            if (budgetUsd * 1_000_000.0 + 0.5 >= Long.MAX_VALUE) {
                throw new IllegalArgumentException("budgetUsd is too large to represent in micro-USD");
            }
            if (budgetUsd * 1_000_000.0 + 0.5 < 1.0) {
                throw new IllegalArgumentException("budgetUsd is too small — it rounds to 0 micro-USD");
            }
        }
        Long budgetDuration = request.budgetDuration();
        if (budgetDuration != null && (budgetDuration <= 0 || budgetDuration > MAX_BUDGET_DURATION_SECONDS)) {
            throw new IllegalArgumentException("budgetDuration must be a positive number of seconds up to "
                    + MAX_BUDGET_DURATION_SECONDS + " (10 years; null = lifetime budget), got " + budgetDuration);
        }
    }

    /**
     * Create a key: fresh random material, the record persisted prefix-indexed, the
     * full {@code sk-janus-<prefix>-<secret>} string returned exactly once.
     */
    CreatedKey create(KeyCreateRequest request);

    /** Look up the auth candidate by its non-secret prefix (O(1) index). */
    Optional<KeyRecord> findByPrefix(String prefix);

    /**
     * Revoke a key permanently (atomic, idempotent). {@code true} iff a key with the
     * id exists; the ACTIVE→REVOKED transition wins over a racing auth.
     */
    boolean revoke(String id);

    /**
     * Atomically authenticate a presented secret against the key stored under
     * {@code prefix} (the auth path). The whole verify → status → expiry →
     * {@code lastUsedAt} sequence executes as a single atomic transition on the
     * prefix index, so a concurrent {@link #revoke} cannot interleave: either the
     * revoke lands first (⇒ {@link AuthOutcome#REVOKED}) or the auth completes first
     * (⇒ {@link AuthOutcome#OK} — the request that already passed the check is the
     * last one through). {@code lastUsedAt} is bumped only for an {@code OK} outcome,
     * inside the same transition.
     *
     * <p><b>The store clock governs expiry.</b> The {@code expiresAt}
     * check reads the store's injected {@link java.time.Clock} — never a caller-supplied
     * timestamp — so a mis-wired caller cannot pass a stale "now" and silently disable
     * expiry (the gateway filter and the store share the same {@code Clock} bean).
     *
     * <p><b>Prefix-existence timing stance (documented, re-verifies).</b> An
     * unknown prefix returns {@link AuthOutcome#INVALID} with no SHA-256 work, while a
     * known prefix with a wrong secret runs a full hash — a measurable timing
     * differential that reveals only <em>which prefixes exist</em>. The prefix is the
     * public O(1) index by design (exposed in {@code GET /key/list}) and ~47.6 bits
     * (infeasible to enumerate), so nothing secret leaks; see {@link KeyHash}'s
     * length-leak note for the related hash-timing stance.
     *
     * @param prefix the non-secret prefix of the presented key
     * @param secret the presented secret (never persisted)
     * @return the outcome + the record it decided on; {@code record} is null for
     * {@link AuthOutcome#INVALID} (unknown prefix <em>or</em> wrong secret) — a failed
     * auth never returns the credential-bearing {@link KeyRecord} (salt/hash)
     */
    AuthResult authenticate(String prefix, String secret);

    /**
     * The outcome of {@link #authenticate}.
     *
     * <ul>
     * <li>{@link #OK} — secret verified, {@code ACTIVE}, not past {@code expiresAt};
     * the request may proceed with the returned record.
     * <li>{@link #INVALID} — unknown prefix or the secret does not verify (401
     * {@code authentication}).
     * <li>{@link #REVOKED} — the key was revoked (403 {@code authorization}).
     * <li>{@link #EXPIRED} — {@code ACTIVE} but past {@code expiresAt} (401
     * {@code authentication}).
     * </ul>
     */
    enum AuthOutcome {
        OK,
        INVALID,
        REVOKED,
        EXPIRED
    }

    /** The result of {@link #authenticate}: an outcome plus the record it decided on. */
    record AuthResult(AuthOutcome outcome, KeyRecord record) {}

    /** Redacted views of all keys (never any secret material), deterministic order. */
    List<KeyRecordView> list();

    /**
     * Best-effort CAS update of {@code lastUsedAt} to the store clock; no-op for
     * unknown prefixes and non-{@code ACTIVE} records (a revoked record's timestamp
     * must never be bumped).
     */
    void touch(String prefix);
}
