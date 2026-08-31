package io.amscotti.janus.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Nullable;

/**
 * {@code POST /key/delete} body — exactly one of {@code key_id}/{@code key} is required.
 */
public record KeyDeleteRequest(
        @JsonProperty("key") @Nullable String key,
        @JsonProperty("key_id") @Nullable String keyId) {}
