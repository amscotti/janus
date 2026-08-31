package io.amscotti.janus.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Nullable;

/**
 * {@code POST /key/delete} response — {@code deleted: false} for an unknown id.
 */
public record KeyDeleteResponse(
        @JsonProperty("key_id") @JsonInclude(JsonInclude.Include.NON_NULL) @Nullable
        String keyId,

        @JsonProperty("deleted") boolean deleted) {}
