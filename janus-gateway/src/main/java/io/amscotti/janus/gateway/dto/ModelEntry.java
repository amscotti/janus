package io.amscotti.janus.gateway.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of the OpenAI models-list shape ({@code GET /v1/models}). {@code owned_by}
 * is the resolved backend's {@code name}, {@code created} the boot-time epoch
 * second (deterministic per process). Explicit {@code @JsonProperty}s + gateway
 * {@code reflect-config.json} entry (native-image discipline).
 *
 * @param id the model alias (router key)
 * @param object wire discriminator — always {@code "model"}
 * @param created boot-time epoch seconds
 * @param ownedBy backend provider name ({@code "deepseek"})
 */
public record ModelEntry(
        @JsonProperty("id") String id,
        @JsonProperty("object") String object,
        @JsonProperty("created") long created,
        @JsonProperty("owned_by") String ownedBy) {}
