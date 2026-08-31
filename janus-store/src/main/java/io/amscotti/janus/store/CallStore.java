package io.amscotti.janus.store;

import java.util.List;

/**
 * The single storage seam: the <b>only</b> store-touching
 * operations in the codebase — {@code recordCall}, {@code spendByKey}, key CRUD
 * and rate-limit counters — folded into one flat interface: the three / seams
 * plus the call ledger. {@code CallStore extends KeyStore, RateLimiter, SpendLedger}
 * (no method-name overlaps: {@code create/findByPrefix/revoke/authenticate/list/
 * touch}, {@code tryAcquire/wouldExceed/accumulate}, {@code spendByKey/recent/
 * reserve/settle/release/recordSpend}) and adds {@link #recordCall},
 * {@link #recentCalls} and {@link #dropped}. One interface = one seam = one contract
 * test ({@code AbstractCallStoreContractTest}, which {@code PostgresCallStoreTest}
 * extends unchanged — the contract "the JDBC store passes the same unit suite as
 * in-memory" harness) = one Postgres target ({@code PostgresCallStore}
 * implements the whole 19-method union (6 {@code KeyStore} + 3 {@code RateLimiter} + 6
 * {@code SpendLedger} + 4 call-ledger), decomposed internally into JDBC pieces).
 *
 * <p><b>Why flat {@code extends}, not an accessor facade.</b>
 * enumerates the operation groups on <em>one</em> interface; the / seams'
 * javadocs already promise this extraction (" extracts/extends, no semantic
 * change"). An accessor facade ({@code callStore.keys},...) would mean three hops
 * for gateway code, a second seam shape for the JDBC implementation, and would not
 * match the interface's flat wording. Consequence, recorded for the Postgres
 * implementation covers the whole union.
 *
 * <p><b>Semantics are the three seams' javadocs, unchanged.</b> The key/rate/ledger
 * methods here are exactly {@link KeyStore}/{@link RateLimiter}/{@link SpendLedger} —
 * is additive and changes no documented behavior; {@link InMemoryCallStore}
 * forwards to the shipped in-memory classes (delegation identity), so a
 * {@code CallStore} bean <em>is</em> the store the gateway already uses ('s
 * factory swap becomes a construction-site change only). The new call-ledger
 * operations are documented below.
 *
 * <p><b>Pinned: {@code accumulate}'s return value.</b> The {@link
 * RateLimiter#accumulate} javadoc deferred pinning its return to completes
 * the handoff: the return is the post-accumulation TPM counter value — <em>the
 * window total</em> (cumulative tokens consumed in the current window), matching the
 * shipped default {@link FixedWindowRateLimiter}. The parity harness pins the
 * concrete value for a known sequence ({@code
 * AbstractCallStoreContractTest.accumulateReturnValueIsPinnedThroughTheSeam}), so
 * {@code PostgresCallStore} inherits a concrete target and must return the
 * same quantity. {@link TokenBucketRateLimiter} (the {@code [janus.limits] window =
 * "sliding"} variant) returns the <em>same</em> quantity for the same sequence — its
 * window total is the net tokens consumed, floored to whole tokens — so the one
 * meaning holds across every store and window selection; a consumer of the
 * return sees identical values on the memory and Postgres backends and under both
 * {@code window} modes.
 *
 * <p><b>No user content.</b> {@link CallRecord} is Tier-1 (no prompt/response
 * bodies, no key material); the store never sees request bodies — the writer
 * (gateway {@code Governance}) holds usage + cost + key id, not content.
 */
public interface CallStore extends KeyStore, RateLimiter, SpendLedger {

    /**
     * Append one per-request {@link CallRecord} to the call ledger: the per-key
     * bounded ring (newest first by {@code (atEpochMillis, seq)} — the same
     * comparator as the global view — retention-configurable; the
     * {@code (atEpochMillis, seq)}-oldest entry is evicted when the ring exceeds
     * retention — Recorder drop-only overflow, observable via
     * {@link #dropped}; a record whose timestamp is older than every retained entry
     * is evicted immediately, whatever its insertion order) and the global
     * newest-first view. One record
     * per request for every outcome the writer reports — success, failure, and
     * streaming settle; <b>aborted streams record nothing</b> (a stream the client
     * disconnected before exhaustion has no terminal usage chunk and is not reported
     * by the gateway writer — the decision, matching the metrics side and
     * the reference recorder; the closed {@link CallStatus} set has no canceled
     * variant). A null {@code keyId} (auth-off request) is ringed under the store's
     * sentinel key, so per-key views stay well-defined (see
     * {@link #recentCalls(String, int)}). Appends unconditionally — the writer calls
     * once per request; the Postgres {@code calls} table keys rows by request id
     * (whether a re-record of the same id overwrites or appends is a decision,
     * not this contract's).
     *
     * <p><b>Does not touch {@link #recordSpend}.</b> The {@code LedgerEntry} ring
     * ('s metrics source) and the {@code CallRecord} ring are distinct in;
     * the gate is the designated decision point to unify them.
     */
    void recordCall(CallRecord record);

    /**
     * The {@code n} newest {@link CallRecord}s for {@code keyId}, newest first by
     * {@code (atEpochMillis, seq)} — the same comparator as the global view and the
     * Postgres {@code ORDER BY at_epoch_millis DESC, seq DESC}, so under out-of-order
     * writer timestamps (concurrent finalization, clock skew) both backends and both
     * views order identically — bounded by the ring retention (fewer when the key has
     * fewer entries; {@code n ≤ 0} ⇒ empty; unknown key ⇒ empty). A null {@code keyId}
     * addresses the auth-off sentinel ring (the records written with null key).
     */
    List<CallRecord> recentCalls(String keyId, int n);

    /**
     * The {@code n} newest {@link CallRecord}s across all keys, newest first — the
     * global cross-key view (the Postgres view reads the same {@code calls}
     * table). Same-timestamp ties are in an unspecified but stable order (callers
     * should not rely on cross-key ordering within one clock millisecond).
     */
    List<CallRecord> recentCalls(int n);

    /**
     * The count of records evicted from per-key rings by retention overflow (the reference
     * {@code Recorder.dropped/0} — raw-event overflow is drop-only and observable, so
     * operators can size retention to traffic). Monotonic, global across keys.
     */
    long dropped();
}
