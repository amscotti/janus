package io.amscotti.janus.store;

import java.util.List;

/**
 * Redacted view of a {@link KeyRecord} for admin surfaces ({@link KeyStore#list}).
 * <b>Structurally carries no secret material</b>: no {@code salt}, no
 * {@code secretHash}, and no full-key field — the redaction is a compile-time
 * property of the type (a caller cannot accidentally serialize the credentials the
 * way a filter-based redaction could). The secret itself is never stored anyway
 * (only salted hashes); dropping hash+salt additionally prevents offline-cracking
 * of exfiltrated admin exports.
 *
 * @param id opaque key id
 * @param prefix non-secret base62 index
 * @param owner human-readable owner/label; nullable
 * @param models per-key model scope (empty = allow all)
 * @param status lifecycle status
 * @param createdAt epoch millis of creation
 * @param expiresAt epoch millis after which the key no longer authenticates; null =
 * never expires
 * @param lastUsedAt epoch millis of the last successful auth; null = never used
 * @param budgetUsd per-key budget cap in USD — data only
 * @param budgetDuration per-key budget reset window in seconds; null = lifetime budget —
 * data only
 * @param rpm per-key requests-per-minute cap — data only
 * @param tpm per-key tokens-per-minute cap — data only
 */
public record KeyRecordView(
        String id,
        String prefix,
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

    public KeyRecordView {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
