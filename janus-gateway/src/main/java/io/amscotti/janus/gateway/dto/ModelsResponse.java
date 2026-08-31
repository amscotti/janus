package io.amscotti.janus.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * OpenAI models-list shape ({@code {"object":"list","data":[…]}}) — the gateway-serialized
 * response for {@code GET /v1/models}. Explicit snake_case {@code @JsonProperty}s and an
 * entry in the gateway {@code reflect-config.json} (native-image discipline).
 *
 * @param object wire discriminator — always {@code "list"}
 * @param data model entries in config order
 */
public record ModelsResponse(
        @JsonProperty("object") String object,
        @JsonProperty("data") List<ModelEntry> data) {}
