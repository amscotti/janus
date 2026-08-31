package io.amscotti.janus.store;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Prefix-indexed in-memory {@link KeyStore}.
 * A {@link ConcurrentHashMap} keyed by the non-secret prefix gives O(1) auth lookup;
 * an id→prefix index resolves revoke-by-id. All status transitions use atomic
 * {@code compute} on the prefix map, and {@link #authenticate} runs the whole
 * verify → status → expiry → {@code lastUsedAt} sequence inside a <em>single</em>
 * transition, so a revoke <em>wins</em> over a racing auth (pinned by the
 * concurrency smoke test and the {@code authenticate} outcome tests).
 *
 * <p>Timestamps come from the injected {@link Clock} ( discipline — tests pin a
 * fixed clock; production wires {@code Clock.systemUTC} in the gateway factory), so
 * expiry and {@code lastUsedAt} are deterministic in tests and there is no real time
 * on the request path beyond the store's own clock.
 */
public final class InMemoryKeyStore implements KeyStore {

    private static final HexFormat HEX = HexFormat.of();

    private final Clock clock;
    private final ConcurrentMap<String, KeyRecord> byPrefix = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idToPrefix = new ConcurrentHashMap<>();

    public InMemoryKeyStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CreatedKey create(KeyCreateRequest request) {
        Objects.requireNonNull(request, "request");
        KeyStore.validateCaps(request);
        long now = clock.millis();
        // putIfAbsent + retry: "create never clobbers an existing prefix" is a
        // structural invariant, not a probability (fresh material regenerated per
        // attempt — a collision retry also gets a fresh id).
        KeyGenerator.Generated generated;
        KeyRecord record;
        do {
            generated = KeyGenerator.generate();
            record = new KeyRecord(
                    generateId(),
                    generated.prefix(),
                    generated.salt(),
                    generated.secretHash(),
                    request.owner(),
                    request.models(),
                    KeyStatus.ACTIVE,
                    now,
                    request.expiresAt(),
                    null,
                    request.budgetUsd(),
                    request.budgetDuration(),
                    request.rpm(),
                    request.tpm());
        } while (byPrefix.putIfAbsent(record.prefix(), record) != null);
        idToPrefix.put(record.id(), record.prefix());
        return new CreatedKey(record, generated.fullKey());
    }

    @Override
    public Optional<KeyRecord> findByPrefix(String prefix) {
        return Optional.ofNullable(byPrefix.get(prefix));
    }

    @Override
    public AuthResult authenticate(String prefix, String secret) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(secret, "secret");
        // The store clock governs expiry (the discipline; never a caller-supplied
        // "now", so a mis-wired caller cannot silently disable the expiresAt check).
        long now = clock.millis();
        // The entire verify → status → expiry → lastUsedAt sequence is one atomic
        // transition, so a racing revoke (also a compute on this key) cannot
        // interleave: it either lands before ⇒ REVOKED, or after ⇒ the auth already
        // completed on the ACTIVE snapshot (last request through, no torn state).
        AtomicReference<AuthResult> result = new AtomicReference<>();
        byPrefix.compute(prefix, (key, record) -> {
            if (record == null) {
                result.set(new AuthResult(AuthOutcome.INVALID, null));
                return null;
            }
            if (!KeyHash.verify(record.salt(), record.secretHash(), secret)) {
                // A wrong secret must not return the credential-bearing record — the
                // caller is not authenticated, so it gets no salt/hash material to attack
                // offline (same shape as the unknown-prefix INVALID).
                result.set(new AuthResult(AuthOutcome.INVALID, null));
                return record;
            }
            if (record.status() == KeyStatus.REVOKED) {
                result.set(new AuthResult(AuthOutcome.REVOKED, record));
                return record;
            }
            if (!record.isActive(now)) {
                result.set(new AuthResult(AuthOutcome.EXPIRED, record));
                return record;
            }
            KeyRecord updated =
                    (record.lastUsedAt() != null && record.lastUsedAt() >= now) ? record : record.withLastUsedAt(now);
            result.set(new AuthResult(AuthOutcome.OK, updated));
            return updated;
        });
        return result.get();
    }

    @Override
    public boolean revoke(String id) {
        Objects.requireNonNull(id, "id");
        String prefix = idToPrefix.get(id);
        if (prefix == null) {
            return false;
        }
        AtomicReference<Boolean> found = new AtomicReference<>(false);
        byPrefix.compute(prefix, (key, record) -> {
            if (record == null || !record.id().equals(id)) {
                return record;
            }
            found.set(true);
            return record.status() == KeyStatus.REVOKED ? record : record.revoked();
        });
        return found.get();
    }

    @Override
    public List<KeyRecordView> list() {
        List<KeyRecordView> views = new ArrayList<>(byPrefix.size());
        for (KeyRecord record : byPrefix.values()) {
            views.add(record.toView());
        }
        views.sort(Comparator.comparingLong(KeyRecordView::createdAt).thenComparing(KeyRecordView::id));
        return List.copyOf(views);
    }

    @Override
    public void touch(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        long now = clock.millis();
        byPrefix.compute(prefix, (key, record) -> {
            // No-op for unknown prefixes and non-ACTIVE records: a revoked record's
            // lastUsedAt must never be bumped (m1 — status guard).
            if (record == null || record.status() != KeyStatus.ACTIVE) {
                return record;
            }
            if (record.lastUsedAt() != null && record.lastUsedAt() >= now) {
                return record;
            }
            return record.withLastUsedAt(now);
        });
    }

    private static String generateId() {
        byte[] bytes = new byte[16];
        KeyGenerator.RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }
}
