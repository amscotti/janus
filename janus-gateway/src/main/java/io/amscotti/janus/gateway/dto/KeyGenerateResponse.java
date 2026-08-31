package io.amscotti.janus.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Nullable;
import java.util.List;

/**
 * {@code POST /key/generate} response — {@code key} is shown exactly once (the store
 * holds only the salted hash; list/delete never echo it); {@code budget_duration}
 * echoes the accepted reset window (null/omitted = lifetime budget).
 */
public record KeyGenerateResponse(
        @JsonProperty("key") String key,
        @JsonProperty("key_id") String keyId,
        @JsonProperty("name") @Nullable String name,
        @JsonProperty("models") List<String> models,
        @JsonProperty("budget_duration") @Nullable Long budgetDuration,
        @JsonProperty("created_at") long createdAt,
        @JsonProperty("expires_at") @Nullable Long expiresAt) {}
