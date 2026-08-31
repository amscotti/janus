package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.StreamChunk;
import java.util.List;

/**
 * Stateful canonical-stream → Anthropic SSE event encoder (multi-block: interleaved text
 * + N tool calls). One instance per SSE stream, produced by
 * {@link AnthropicMessageCodec#newStreamEncoder}.
 *
 * <p><b>Contract (pinned in tests; the publisher must honor it):</b> the first
 * {@link #feed(StreamChunk)} carrying a non-null {@code delta} opens the stream —
 * {@code message_start} (id/model from the chunk) + a lazily-created content block
 * ({@code content_block_start(index 0, text)} — or {@code tool_use} when the first delta
 * carries tool fragments); every content-bearing delta afterwards emits
 * {@code content_block_delta(text_delta | input_json_delta)}. Block indices are assigned
 * in arrival order (text and tools share one counter); a tool fragment for a new tool
 * closes the current block and opens a {@code tool_use} block. {@link #finish}
 * <em>must always be called</em> — it emits the terminal sequence ({@code content_block_stop}
 * for the open block when any, {@code message_delta(stop_reason, usage)},
 * {@code message_stop}); a stream whose consumer drops the {@code finish} call is left
 * unterminated (a stopped mid-stream SSE). A stream fed only null-delta chunks never
 * emits a {@code message_delta} without the {@code message_start} opener: with
 * nothing to deliver {@code finish} emits nothing; a usage-only feed gets a synthesized
 * opener so the terminal sequence stays well-formed. A zero-feed stream (no
 * {@link #feed} calls at all) also gets the synthesized opener — the Anthropic face
 * must never leave an SSE wire empty.
 */
public interface AnthropicStreamEncoder {

    /**
     * Map one canonical {@link StreamChunk} to zero or more Anthropic SSE events.
     *
     * @param canonical the chunk (role-only, text-delta, tool-call fragment, usage-only,...)
     * @return the events to emit for this chunk; empty for deltas carrying no content
     * @throws AnthropicCodecException if a tool fragment's arguments fail to render
     */
    List<AnthropicSseEvent> feed(StreamChunk canonical) throws AnthropicCodecException;

    /**
     * Close the stream: {@code content_block_stop} for every open block (in index
     * order), {@code message_delta} (stop reason from the last seen {@code finishReason}
     * — {@code tool_use} when {@code tool_calls} — usage from the last seen chunk usage)
     * and {@code message_stop}. Idempotent-safe: after the first call no further events
     * are produced.
     */
    List<AnthropicSseEvent> finish() throws AnthropicCodecException;
}
