package io.amscotti.janus.store;

import java.util.List;
import java.util.Objects;

/**
 * A gateway key record (mirrors the reference implementation semantics's {@code Janus.Keys.Key}). The full key
 * string a client presents is {@code sk-janus-<prefix>-<secret>} (see
 * {@link KeyGenerator}); this record persists only the non-secret {@code prefix}
 * (the O(1) index), the per-key {@code salt} and the salted SHA-256
 * {@code secretHash} — the secret itself is <b>never stored</b> and is returned to
 * the caller exactly once at creation ({@link KeyStore#create}).
 *
 * <p>{@code models} is the per-key model scope (empty = allow all) carried as data;
 * {@link #accessPolicy} derives the {@link AccessPolicy} the gateway enforces
 * against {@code ChatRequest.model} ( alias-scope semantics — see
 * {@link AccessPolicy}). {@code budgetUsd}/{@code rpm}/{@code tpm} are carried as
 * <b>data only</b> — enforcement is (mirrors the reference policy record carrying
 * {@code rate_limit_overrides}/{@code budget_overrides} unused there);
 * {@code lastUsedAt} is updated by the auth path so the metrics layer's per-key usage series has
 * the data.
 *
 * <p><b>Security posture.</b> {@code salt}/{@code secretHash} accessors return
 * defensive copies (a caller cannot mutate the stored credential material through the
 * record), and no accessor ever exposes the plaintext secret.
 *
 * @param id opaque key id (random, generated at creation)
 * @param prefix non-secret base62 index used by {@code findByPrefix}
 * @param salt per-key random salt (16 bytes)
 * @param secretHash salted SHA-256 of the secret (never the plaintext)
 * @param owner human-readable owner/label ("name" in the admin API); nullable
 * @param models per-key model scope (empty = allow all)
 * @param status lifecycle status
 * @param createdAt epoch millis of creation (store {@link java.time.Clock})
 * @param expiresAt epoch millis after which the key no longer authenticates; null =
 * never expires
 * @param lastUsedAt epoch millis of the last successful auth; null = never used
 * @param budgetUsd per-key budget cap in USD — data only, enforced by
 * @param budgetDuration per-key budget reset window in seconds; null = the budget is
 * lifetime (all-time) — data only, enforced by
 * @param rpm per-key requests-per-minute cap — data only, enforced by
 * @param tpm per-key tokens-per-minute cap — data only, enforced by
 */
public record KeyRecord(
        String id,
        String prefix,
        byte[] salt,
        byte[] secretHash,
        String owner,
        List<String> models,
        KeyStatus status,
        long createdAt,
        Long expiresAt,
        Long lastUsedAt,
        Double budgetUsd,
        Long budgetDuration,
        Integer rpm,
        Integer tpm) {

    public KeyRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(salt, "salt");
        Objects.requireNonNull(secretHash, "secretHash");
        Objects.requireNonNull(status, "status");
        models = models == null ? List.of() : List.copyOf(models);
        salt = salt.clone();
        secretHash = secretHash.clone();
    }

    @Override
    public byte[] salt() {
        return salt.clone();
    }

    @Override
    public byte[] secretHash() {
        return secretHash.clone();
    }

    /**
     * Redacted {@code toString}: the auto-generated record form would
     * print the hex of {@code salt} and {@code secretHash}, materializing offline-
     * crackable credential material in any log line that ever prints a record. The
     * override keeps the record-shaped output but replaces the two byte arrays with
     * {@code [redacted]} — matching the {@link KeyRecordView} posture.
     */
    @Override
    public String toString() {
        return "KeyRecord[id=" + id + ", prefix=" + prefix + ", salt=[redacted], secretHash=[redacted], owner=" + owner
                + ", models=" + models + ", status=" + status + ", createdAt=" + createdAt + ", expiresAt=" + expiresAt
                + ", lastUsedAt=" + lastUsedAt + ", budgetUsd=" + budgetUsd + ", budgetDuration=" + budgetDuration
                + ", rpm=" + rpm + ", tpm=" + tpm + "]";
    }

    /**
     * Is this key usable at {@code now} (epoch millis)? {@code ACTIVE} and not past
     * {@code expiresAt}.
     */
    public boolean isActive(long now) {
        return status == KeyStatus.ACTIVE && (expiresAt == null || expiresAt > now);
    }

    /**
     * The access policy derived from this key's model scope (the admin API scopes
     * by {@code models} only; denied lists are always empty — deny-beats-allow is
     * carried by the {@link AccessPolicy} shape for +).
     */
    public AccessPolicy accessPolicy() {
        return new AccessPolicy(models, List.of());
    }

    /** A copy of this record with {@link KeyStatus#REVOKED} (atomic store transition). */
    public KeyRecord revoked() {
        return new KeyRecord(
                id,
                prefix,
                salt,
                secretHash,
                owner,
                models,
                KeyStatus.REVOKED,
                createdAt,
                expiresAt,
                lastUsedAt,
                budgetUsd,
                budgetDuration,
                rpm,
                tpm);
    }

    /** A copy of this record with {@code lastUsedAt} set to {@code at} (best-effort CAS). */
    public KeyRecord withLastUsedAt(long at) {
        return new KeyRecord(
                id,
                prefix,
                salt,
                secretHash,
                owner,
                models,
                status,
                createdAt,
                expiresAt,
                at,
                budgetUsd,
                budgetDuration,
                rpm,
                tpm);
    }

    /** The redacted view exposed by {@link KeyStore#list()} — never carries secret material. */
    public KeyRecordView toView() {
        return new KeyRecordView(
                id,
                prefix,
                owner,
                models,
                status,
                createdAt,
                expiresAt,
                lastUsedAt,
                budgetUsd,
                budgetDuration,
                rpm,
                tpm);
    }
}
