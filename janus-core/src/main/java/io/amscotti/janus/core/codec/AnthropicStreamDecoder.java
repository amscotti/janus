package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.StreamChunk;

/**
 * Stateful Anthropic SSE → canonical-stream decoder (one instance per stream, produced
 * by {@link AnthropicMessageCodec#newStreamDecoder}). Per-stream state lets the
 * decoder merge the two usage-bearing events Anthropic splits across the wire:
 * {@code message_start} carries the prompt side ({@code message.usage.input_tokens}) and
 * {@code message_delta} carries the completion side ({@code usage.output_tokens} — never
 * {@code input_tokens}). The merged terminal usage is delivered on the
 * {@code message_delta} chunk so downstream consumers (governance settling, the
 * {@link AnthropicStreamEncoder}) see the full prompt + completion picture on the chunk
 * they settle on — the stateless {@link AnthropicMessageCodec#decodeChunk} sees one
 * event at a time and would report {@code promptTokens=0} for every Anthropic-derived
 * stream.
 *
 * <p>Contract: call {@link #decodeChunk(String, String)} once per SSE event frame in wire
 * order. No-op events return {@code null} exactly as the stateless {@code decodeChunk};
 * the only behavioral difference is the merged usage on the terminal chunk.
 */
public interface AnthropicStreamDecoder {

    /**
     * One SSE event payload → a canonical chunk (or {@code null} for no-op/unknown
     * events). Stateful across calls within one stream.
     *
     * @param eventType the SSE {@code event:} name ({@code message_start},...)
     * @param dataJson the SSE {@code data:} payload JSON
     * @return the canonical chunk, or {@code null} when the event carries no canonical content
     * @throws AnthropicCodecException for {@code error} events and malformed payloads
     */
    StreamChunk decodeChunk(String eventType, String dataJson) throws AnthropicCodecException;
}
