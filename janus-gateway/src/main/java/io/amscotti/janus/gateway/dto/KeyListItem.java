package io.amscotti.janus.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.amscotti.janus.store.KeyStatus;
import io.micronaut.core.annotation.Nullable;
import java.util.List;

/**
 * One {@code GET /key/list} item — redacted (no salt/hash/secret; {@code name} = owner).
 */
public record KeyListItem(
        @JsonProperty("id") String id,
        @JsonProperty("prefix") String prefix,
        @JsonProperty("name") @Nullable String name,
        @JsonProperty("models") List<String> models,
        @JsonProperty("status") KeyStatus status,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("expires_at") @Nullable Long expiresAt,
        @JsonProperty("last_used_at") @Nullable Long lastUsedAt,
        @JsonProperty("budget_usd") @Nullable Double budgetUsd,
        @JsonProperty("budget_duration") @Nullable Long budgetDuration,
        @JsonProperty("rpm") @Nullable Integer rpm,
        @JsonProperty("tpm") @Nullable Integer tpm) {}
