package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The inner {@code delta} object of the {@code message_delta} event. Wire shape:
 * {@code {"stop_reason":"...","stop_sequence":null}} — the {@code stop_sequence} is
 * non-null only when a user stop sequence terminated generation. Distinct from the
 * content-block {@link AnthropicDelta} family (this one is not {@code type}-discriminated
 * on the wire — it has no {@code type} field).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicStopReasonDelta(String stopReason, String stopSequence) {}
