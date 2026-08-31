package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One OpenAI request message (tolerant single record covering every chat role).
 *
 * <p>{@code role} is a raw wire string here — role validation (must be one of
 * {@code system|user|assistant|tool|developer}, tool messages must carry
 * {@code tool_call_id}) is the codec's job ( contract: "validated by the codec").
 * {@code name} is a legal
 * per-message wire field with a canonical home ({@code UserMessage.name}/
 * {@code AssistantMessage.name}/{@code ToolMessage.name}) — the codec maps it from/to
 * the canonical message and re-emits it inside the message (it is never
 * folded into the request {@code extras}). Unknown message fields have no canonical home
 * ( {@code Message} subtypes are sealed records without extras);
 * {@link OpenAiMessageCodec#decodeRequest} folds them into the request {@code extras} so
 * nothing is silently dropped.
 *
 * <p>{@code content} is a {@link String} for classic chat, or a list/array of multimodal
 * parts ({@code text} / {@code image_url}) — mapped onto
 * {@link io.amscotti.janus.core.model.ContentPart}s by the codec.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiMessage(
        String role,
        Object content,
        String name,
        String toolCallId,
        List<OpenAiToolCall> toolCalls,

        @JsonAnySetter @JsonInclude(JsonInclude.Include.NON_EMPTY)
        Map<String, Object> extras) {

    public OpenAiMessage {
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
}
