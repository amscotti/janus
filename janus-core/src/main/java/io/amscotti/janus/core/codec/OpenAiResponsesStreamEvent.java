package io.amscotti.janus.core.codec;

/**
 * One typed SSE frame produced by the Responses stream encoder (the
 * {@link AnthropicSseEvent} pattern): the {@code event} name
 * ({@code response.output_text.delta}, …) plus the raw JSON {@code data} payload —
 * the data object also carries its own {@code type} and {@code sequence_number}
 * (strict SDK consumers validate both). SSE transport framing is the gateway's job.
 */
public record OpenAiResponsesStreamEvent(String event, String dataJson) {}
