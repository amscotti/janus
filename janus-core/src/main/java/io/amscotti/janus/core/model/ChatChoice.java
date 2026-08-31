package io.amscotti.janus.core.model;

/**
 * One completion choice in a {@link ChatResponse}.
 *
 * <p>{@code finishReason} holds the <em>canonical</em> stop-reason value (one of
 * {@code ChatResponse.STOP_REASON_*}, unknown wire values pass through verbatim) — the
 * codecs normalize it at the decode boundary through {@link
 * io.amscotti.janus.core.codec.StopReasonTable}, mirroring the response-level
 * {@code ChatResponse.stopReason}, so both spellings always agree (e.g. an upstream
 * OpenAI {@code "function_call"} becomes {@code "tool_calls"} here).
 */
public record ChatChoice(int index, Message message, String finishReason) {}
