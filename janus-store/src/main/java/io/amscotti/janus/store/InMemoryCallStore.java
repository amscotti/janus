package io.amscotti.janus.store;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The zero-dependency default {@link CallStore} (the "concurrent
 * maps + ring-buffer retention, configurable"). <b>Composition, not duplication:</b>
 * the / concrete classes stay the single implementation of their own logic —
 * {@link InMemoryKeyStore}, the configured {@link RateLimiter} variant
 * ({@link FixedWindowRateLimiter} | {@link TokenBucketRateLimiter}) and
 * {@link InMemorySpendLedger} are embedded and every key/rate/ledger method forwards
 * to them, so a {@code CallStore} bean <em>is</em> the store the gateway already uses
 * ('s factory swap becomes a construction-site change only). The / suites
 * stay untouched and green as the delegates' spec.
 *
 * <p><b>What is new: the per-key call ledger.</b> A {@link ConcurrentHashMap} of
 * bounded newest-first {@link ArrayDeque}s of {@link CallRecord} (stamped with a
 * monotonic global {@code seq} for the Postgres-parity tie-break),
 * retention-configurable (constructor-injected; {@link #DEFAULT_RETENTION} matches the
 * ledger default 1000; the {@code [store]} TOML binding is ). The full constructor also
 * takes the spend-ledger ring retention separately ({@code [janus.limits]
 * ledger-retention} — the two rings are independent knobs). {@link #recordCall}
 * inserts inside one atomic {@code compute} on the key's ring, so eviction is exact:
 * the ring is kept sorted newest-first by {@code (atEpochMillis, seq)} — the same
 * comparator the global view sorts by and the Postgres per-key view/prune orders
 * ({@code ORDER BY at_epoch_millis DESC, seq DESC}) — so under out-of-order writer
 * timestamps (concurrent finalization, clock skew) the record slots in <em>by
 * timestamp</em>, and the {@code (atEpochMillis, seq)}-oldest entry drops when the
 * ring exceeds retention (Recorder drop-only overflow — unbounded or
 * racy appends are impossible; a newly inserted record whose timestamp is older than
 * every retained entry is evicted immediately, exactly like the Postgres prune) and
 * the drop is observable via {@link #dropped}. Readers synchronize on the deque
 * exactly like {@code InMemorySpendLedger}'s recent ring (the CHM compute serializes
 * writers per key but not readers). Null {@code keyId} records (auth-off) are ringed
 * under the {@code ""} sentinel, so per-key views stay well-defined.
 *
 * <p><b>One store clock.</b> The injected {@link Clock}  feeds the
 * key store, the rate limiter and the ledger — the same bean the gateway's
 * {@code KeyStoreFactory} produces; the gateway's {@code CallStoreFactory} passes it through.
 * The convenience constructors build the rate-limiter variant from this clock, so one
 * clock reaches all three concerns; the full constructor receives a pre-built
 * variant, so <b>the caller must construct it with the same clock</b> ('s factory
 * must do this). The {@code CallRecord} timestamps are the writer's responsibility
 * (built from this clock); the store trusts them.
 *
 * <p><b>Units and privacy.</b> Ledger amounts are integer micro-USD ({@link
 * CostCalculator} output); the call ledger holds Tier-1 records only — no user
 * content, no key material (the record type is structural; see {@link CallRecord}).
 *
 * <p>Thread-safe. Both retentions are validated positive at construction (a
 * misconfigured ring fails at boot, discipline).
 */
public final class InMemoryCallStore implements CallStore {

    /** The default per-key ring retention, matching the ledger retention default. */
    public static final int DEFAULT_RETENTION = 1000;

    /** The ring key for auth-off records (null {@code keyId}): never collides with a real id. */
    private static final String AUTH_OFF_SENTINEL = "";

    private final InMemoryKeyStore keys;
    private final RateLimiter rateLimiter;
    private final InMemorySpendLedger ledger;
    private final int retention;
    private final ConcurrentMap<String, Deque<SequencedCall>> calls = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();

    /**
     * Newest-first by {@code (atEpochMillis, seq)} — the single ordering the in-memory
     * per-key ring, the in-memory global view and the Postgres backend all use
     * ({@code ORDER BY at_epoch_millis DESC, seq DESC}): the timestamp leads, and the
     * seq breaks same-millisecond ties (higher seq first).
     */
    private static final Comparator<SequencedCall> NEWEST_FIRST = Comparator.comparingLong(
                    (SequencedCall c) -> c.record().atEpochMillis())
            .reversed()
            .thenComparing(Comparator.comparingLong(SequencedCall::seq).reversed());

    /**
     * A ring entry stamped with a monotonic global sequence: the {@code seq}
     * mirrors the Postgres {@code bigserial} {@code seq} column, giving the in-memory
     * per-key ring and the global newest-first view the same deterministic ordering as
     * the Postgres backend — two records sharing one millisecond order by insertion
     * (higher seq first) instead of by {@code ConcurrentHashMap} iteration order,
     * which is unstable across mutations.
     */
    private record SequencedCall(long seq, CallRecord record) {}

    /**
     * Full constructor ('s factory input): the call-ring retention, the
     * spend-ledger ring retention and the rate-limiter variant are three independent
     * knobs — {@code [janus.store] retention} sizes the call ring while {@code
     * [janus.limits] ledger-retention} sizes the spend-ledger ring, so the factory
     * wires each from its own config key (the single-retention constructors below
     * share one value across both rings). The {@code rateLimiter} is pre-built, so
     * the caller must construct the variant with {@code clock} — only the convenience
     * constructors guarantee one shared clock reaches the key store, ledger and
     * limiter.
     *
     * @param clock the single store clock
     * @param retention the per-key call-ring retention (must be &gt; 0)
     * @param ledgerRetention the per-key spend-ledger ring retention (must be &gt; 0;
     * the {@code recent} spend ring, distinct from the call ring)
     * @param rateLimiter the configured variant ({@code FixedWindowRateLimiter} |
     * {@code TokenBucketRateLimiter}), built with {@code clock}
     */
    public InMemoryCallStore(Clock clock, int retention, int ledgerRetention, RateLimiter rateLimiter) {
        Objects.requireNonNull(clock, "clock");
        if (retention <= 0) {
            throw new IllegalArgumentException("retention must be positive (got " + retention + ")");
        }
        this.retention = retention;
        this.rateLimiter = Objects.requireNonNull(rateLimiter, "rateLimiter");
        this.keys = new InMemoryKeyStore(clock);
        this.ledger = new InMemorySpendLedger(clock, ledgerRetention);
    }

    /**
     * Convenience: one retention sizes both rings (the call ring and the spend-ledger
     * ring share it) with the given rate-limiter variant.
     *
     * @param retention the per-key call-ring <b>and</b> spend-ledger ring retention
     */
    public InMemoryCallStore(Clock clock, int retention, RateLimiter rateLimiter) {
        this(clock, retention, retention, rateLimiter);
    }

    /** Convenience: fixed-window rate limiting with the given retention. */
    public InMemoryCallStore(Clock clock, int retention) {
        this(clock, retention, new FixedWindowRateLimiter(clock));
    }

    /** Convenience: fixed-window rate limiting with {@link #DEFAULT_RETENTION}. */
    public InMemoryCallStore(Clock clock) {
        this(clock, DEFAULT_RETENTION);
    }

    // --- KeyStore delegation (the semantics, unchanged) -------------------------

    @Override
    public CreatedKey create(KeyCreateRequest request) {
        return keys.create(request);
    }

    @Override
    public Optional<KeyRecord> findByPrefix(String prefix) {
        return keys.findByPrefix(prefix);
    }

    @Override
    public boolean revoke(String id) {
        return keys.revoke(id);
    }

    @Override
    public AuthResult authenticate(String prefix, String secret) {
        return keys.authenticate(prefix, secret);
    }

    @Override
    public List<KeyRecordView> list() {
        return keys.list();
    }

    @Override
    public void touch(String prefix) {
        keys.touch(prefix);
    }

    // --- RateLimiter delegation (the semantics, unchanged) ----------------------

    @Override
    public RateLimitResult tryAcquire(String keyId, int limit, long cost) {
        return rateLimiter.tryAcquire(keyId, limit, cost);
    }

    @Override
    public boolean wouldExceed(String keyId, int limit, long estimate) {
        return rateLimiter.wouldExceed(keyId, limit, estimate);
    }

    @Override
    public long accumulate(String keyId, int limit, long actual) {
        return rateLimiter.accumulate(keyId, limit, actual);
    }

    // --- SpendLedger delegation (the semantics, unchanged) ----------------------

    @Override
    public long spendByKey(String keyId, long windowSeconds) {
        return ledger.spendByKey(keyId, windowSeconds);
    }

    @Override
    public long totalSpendByKey(String keyId) {
        return ledger.totalSpendByKey(keyId);
    }

    @Override
    public List<LedgerEntry> recent(String keyId, int n) {
        return ledger.recent(keyId, n);
    }

    @Override
    public ReserveResult reserve(
            String keyId, long estimateMicroUsd, long hardCapMicroUsd, double softFraction, long windowSeconds) {
        return ledger.reserve(keyId, estimateMicroUsd, hardCapMicroUsd, softFraction, windowSeconds);
    }

    @Override
    public void settle(String keyId, long estimateMicroUsd, long actualMicroUsd, long reservationWindowStart) {
        ledger.settle(keyId, estimateMicroUsd, actualMicroUsd, reservationWindowStart);
    }

    @Override
    public void release(String keyId, long estimateMicroUsd, long reservationWindowStart) {
        ledger.release(keyId, estimateMicroUsd, reservationWindowStart);
    }

    @Override
    public void recordSpend(String keyId, long amountMicroUsd) {
        ledger.recordSpend(keyId, amountMicroUsd);
    }

    // --- Call ledger -----------------------------------------------------------

    @Override
    public void recordCall(CallRecord record) {
        Objects.requireNonNull(record, "record");
        String ringKey = record.keyId() == null ? AUTH_OFF_SENTINEL : record.keyId();
        SequencedCall call = new SequencedCall(sequence.incrementAndGet(), record);
        // One atomic compute per append: the ring stays bounded and the eviction is
        // exact under concurrency (writers serialize per key; readers sync on the deque).
        calls.compute(ringKey, (key, ring) -> {
            Deque<SequencedCall> deque = ring == null ? new ArrayDeque<>() : ring;
            synchronized (deque) {
                insertNewestFirst(deque, call);
                while (deque.size() > retention) {
                    deque.removeLast();
                    dropped.incrementAndGet();
                }
            }
            return deque;
        });
    }

    /**
     * Insert {@code call} keeping the ring sorted newest-first by {@link #NEWEST_FIRST}:
     * a record whose timestamp is older than entries already ringed (out-of-order
     * writer timestamps — concurrent finalization, clock skew) slots in <em>behind</em>
     * them, exactly like the Postgres {@code ORDER BY at_epoch_millis DESC, seq DESC}
     * per-key view — so the tail eviction ({@code removeLast}) drops the
     * {@code (atEpochMillis, seq)}-oldest record, the same record the Postgres prune
     * deletes, on every insertion order. The common case (writer timestamps in clock
     * order) stays a head append; only an out-of-order timestamp pays the mid-ring
     * splice. {@code seq} is unique, so the comparator never returns 0 for distinct
     * records and the order is total.
     */
    private static void insertNewestFirst(Deque<SequencedCall> deque, SequencedCall call) {
        // NEWEST_FIRST is a sort-order comparator: compare(a, b) < 0 ⇔ a is newer
        // (a sorts before b), so "newer than the head" is compare(call, head) < 0.
        if (deque.isEmpty() || NEWEST_FIRST.compare(call, deque.peekFirst()) < 0) {
            deque.addFirst(call); // newer than everything ringed: the head
            return;
        }
        if (NEWEST_FIRST.compare(call, deque.peekLast()) >= 0) {
            deque.addLast(call); // older than everything ringed: the tail
            return;
        }
        // Mid-ring: splice behind every strictly newer entry (drain them, add the
        // record, restore them in front — order preserved).
        Deque<SequencedCall> newer = new ArrayDeque<>();
        while (NEWEST_FIRST.compare(deque.peekFirst(), call) < 0) {
            newer.addLast(deque.removeFirst());
        }
        deque.addFirst(call);
        while (!newer.isEmpty()) {
            deque.addFirst(newer.removeLast());
        }
    }

    @Override
    public List<CallRecord> recentCalls(String keyId, int n) {
        String ringKey = keyId == null ? AUTH_OFF_SENTINEL : keyId;
        Deque<SequencedCall> ring = calls.get(ringKey);
        if (ring == null || n <= 0) {
            return List.of();
        }
        synchronized (ring) {
            int count = Math.min(n, ring.size());
            List<CallRecord> result = new ArrayList<>(count);
            var it = ring.iterator();
            for (int i = 0; i < count && it.hasNext(); i++) {
                result.add(it.next().record());
            }
            return List.copyOf(result);
        }
    }

    @Override
    public List<CallRecord> recentCalls(int n) {
        if (n <= 0) {
            return List.of();
        }
        List<SequencedCall> all = new ArrayList<>();
        for (Deque<SequencedCall> ring : calls.values()) {
            synchronized (ring) {
                all.addAll(ring);
            }
        }
        // Newest first across keys, by the same (atEpochMillis, seq) comparator the
        // per-key rings keep — the Postgres (at_epoch_millis DESC, seq DESC) parity.
        all.sort(NEWEST_FIRST);
        List<CallRecord> result = new ArrayList<>(Math.min(n, all.size()));
        for (int i = 0; i < n && i < all.size(); i++) {
            result.add(all.get(i).record());
        }
        return List.copyOf(result);
    }

    @Override
    public long dropped() {
        return dropped.get();
    }
}
