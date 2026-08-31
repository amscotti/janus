package io.amscotti.janus.provider;

/**
 * One decoded SSE frame: the {@code event} name (last {@code event:} line wins per the
 * SSE spec; {@code "message"} when absent) plus the joined {@code data} payload.
 * Package-private: consumed by the event-aware {@link SseFrameParser#nextEventFrame}
 * accessor the {@link AnthropicAdapter} streams with (Anthropic SSE is event-typed and
 * terminates on {@code event: message_stop}).
 */
record SseEventFrame(String event, String data) {}
