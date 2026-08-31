package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The {@code message_delta} terminal event. Wire shape:
 * {@code {"type":"message_delta","delta":{"stop_reason":"...","stop_sequence":null},
 * "usage":{...}}}. The inner {@code delta} is {@link AnthropicStopReasonDelta};
 * {@code usage} carries the final token counts ({@link AnthropicUsage}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicMessageDelta(String type, AnthropicStopReasonDelta delta, AnthropicUsage usage)
        implements AnthropicSsePayload {}
