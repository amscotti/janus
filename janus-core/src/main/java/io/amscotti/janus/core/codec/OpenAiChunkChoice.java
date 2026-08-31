package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One streaming choice: a {@link OpenAiDelta} and a nullable {@code finishReason}
 * (present only on the terminal chunk). {@code index} is a primitive so it is always
 * present on the wire — downstream guards rely on it as the per-choice discriminator.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiChunkChoice(int index, OpenAiDelta delta, String finishReason) {}
