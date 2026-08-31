package io.amscotti.janus.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical chat-completion request — the normalized form every codec translates to/from.
 *
 * <p>Reserved-field contract (from the reference implementation spec, binding):
 *
 * <ul>
 * <li>{@code extras} — opaque pass-through map: every request field Janus does not model
 * rides here untouched and is merged back into the outbound provider payload.
 * <li>{@code meta} — gateway-internal context (request id, key id, attempt,...). Never
 * serialized to a provider ({@link JsonIgnore}).
 * <li>{@code reasoning} / {@code cacheControl} — reserved field names (payloads are
 * later releases); reserves the slots so future features can fill them without breaking the record.
 * </ul>
 *
 * <p><b>Serialization constraint (hard, see {@link Message}):</b> serializing this record
 * works because {@code messages} is a declared-typed {@code List<Message>}; Jackson 3
 * honors the interface-level {@code @JsonTypeInfo} only on that path. Never re-serialize
 * {@link #messages} as a bare list ({@code Map.of("messages", request.messages)} or a
 * plain {@code List<Message>}) — the {@code role} discriminator is silently dropped and
 * the output cannot round-trip. Codecs (+) must serialize message lists through
 * declared-typed containers (this record / DTO records / {@code writerFor}).
 *
 * <p><b>Null-tolerance contract.</b> Every opaque map ({@code reasoning},
 * {@code logitBias}, {@code responseFormat}, {@code streamOptions}, {@code extras},
 * {@code meta}) is defensively copied null-tolerantly: JSON objects with null-valued
 * fields are legitimate pass-through payloads and must survive construction
 * ({@code Map.copyOf} would NPE on a null value, which previously escaped the
 * Anthropic {@code decodeRequest} as a raw {@code NullPointerException}). The codec
 * DTOs mirror this so a canonical carrying null-valued maps re-encodes, never crashes.
 * The wire-typed component lists ({@code messages}, {@code tools}, {@code hostedTools},
 * {@code stop}) follow the same rule for null <em>elements</em>: a client
 * {@code "messages":[null]} or {@code "stop":[null]} is malformed, but the proxy
 * passes it through (the codecs validate shape with a typed error; the upstream that
 * receives the re-encoded payload rejects it with a typed error) instead of letting a
 * raw {@code NullPointerException} escape {@code List.copyOf} on wire input.
 *
 * <p><b>Tools are definitions, not invocations.</b> {@code tools} holds {@link
 * ToolDefinition}s (name/description/input-schema); tool <em>invocations</em> live on
 * {@link AssistantMessage#toolCalls} as {@link ToolCall}s. The two never share a type
 * (the canonical previously stored definitions as {@code ToolCall}s with a
 * {@code FunctionCall.arguments} that carried a schema string in this path but real call
 * arguments in the invocation path).
 *
 * @param messages <b>not enforced here</b> — must be validated by callers: the codecs
 * reject null/empty at the decode and encode boundary with a typed error; the
 * record itself constructs (construct-and-fail-later policy)
 * @param stream primitive — codecs default it
 */
public record ChatRequest(
        String model,
        List<Message> messages,
        String system,
        List<ToolDefinition> tools,
        Object toolChoice,
        Double temperature,
        Double topP,
        Integer topK,
        Integer maxTokens,
        List<String> stop,
        Long seed,
        Integer n,
        Double frequencyPenalty,
        Double presencePenalty,
        Map<String, Object> logitBias,
        Map<String, Object> responseFormat,
        boolean stream,
        Map<String, Object> streamOptions,
        Map<String, Object> reasoning,
        Object cacheControl,
        List<HostedToolDefinition> hostedTools,
        Map<String, Object> extras,
        @JsonIgnore Map<String, Object> meta) {

    /** Compatibility form at the earlier (22-arg) arity — no hosted tools. */
    public ChatRequest(
            String model,
            List<Message> messages,
            String system,
            List<ToolDefinition> tools,
            Object toolChoice,
            Double temperature,
            Double topP,
            Integer topK,
            Integer maxTokens,
            List<String> stop,
            Long seed,
            Integer n,
            Double frequencyPenalty,
            Double presencePenalty,
            Map<String, Object> logitBias,
            Map<String, Object> responseFormat,
            boolean stream,
            Map<String, Object> streamOptions,
            Map<String, Object> reasoning,
            Object cacheControl,
            Map<String, Object> extras,
            Map<String, Object> meta) {
        this(
                model,
                messages,
                system,
                tools,
                toolChoice,
                temperature,
                topP,
                topK,
                maxTokens,
                stop,
                seed,
                n,
                frequencyPenalty,
                presencePenalty,
                logitBias,
                responseFormat,
                stream,
                streamOptions,
                reasoning,
                cacheControl,
                null,
                extras,
                meta);
    }

    public ChatRequest {
        // Wire-typed component lists: a client "messages":[null] / "stop":[null] is
        // malformed, but it is wire input — the copy must not escape as a raw
        // NullPointerException from List.copyOf (same NPE-escape-on-wire-input class
        // as the maps below) — null-tolerant unmodifiable copies; the codecs validate
        // shape with a typed error and the upstream rejects the re-encoded payload.
        messages = messages == null ? null : Collections.unmodifiableList(new ArrayList<>(messages));
        tools = tools == null ? null : Collections.unmodifiableList(new ArrayList<>(tools));
        stop = stop == null ? null : Collections.unmodifiableList(new ArrayList<>(stop));
        // The opaque pass-through contract applies to every payload map, not just
        // extras/meta: reasoning/logitBias/responseFormat/streamOptions carry wire
        // objects that may legitimately have null-valued fields ({"effort": null}), so
        // the defensive copy must tolerate null values (Map.copyOf would NPE). HashMap
        // allows null values; wrap for immutability.
        logitBias = logitBias == null ? null : Collections.unmodifiableMap(new HashMap<>(logitBias));
        responseFormat = responseFormat == null ? null : Collections.unmodifiableMap(new HashMap<>(responseFormat));
        streamOptions = streamOptions == null ? null : Collections.unmodifiableMap(new HashMap<>(streamOptions));
        reasoning = reasoning == null ? null : Collections.unmodifiableMap(new HashMap<>(reasoning));
        hostedTools = hostedTools == null ? null : Collections.unmodifiableList(new ArrayList<>(hostedTools));
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
        meta = meta == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(meta));
    }

    /** Gateway-internal copy with one extra meta entry (e.g. inbound {@code anthropic-beta}). */
    public ChatRequest withMetaEntry(String key, Object value) {
        Map<String, Object> next = new HashMap<>(meta);
        next.put(key, value);
        return new ChatRequest(
                model,
                messages,
                system,
                tools,
                toolChoice,
                temperature,
                topP,
                topK,
                maxTokens,
                stop,
                seed,
                n,
                frequencyPenalty,
                presencePenalty,
                logitBias,
                responseFormat,
                stream,
                streamOptions,
                reasoning,
                cacheControl,
                hostedTools,
                extras,
                next);
    }
    /** Conversation text (the system prompt, message bodies, and any text riding the
     * extras map) is excluded from log output unless content logging is explicitly
     * enabled ({@code [janus.privacy] log-content}). Structure — model, message count,
     * tool count, stream flag — still prints so a log line remains diagnosable. */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "ChatRequest[model=" + model + ", messages=" + messages + ", system=" + system + ", tools="
                    + tools + ", toolChoice=" + toolChoice + ", temperature=" + temperature + ", topP=" + topP
                    + ", topK=" + topK + ", maxTokens=" + maxTokens + ", stop=" + stop + ", seed=" + seed
                    + ", n=" + n + ", frequencyPenalty=" + frequencyPenalty + ", presencePenalty="
                    + presencePenalty + ", logitBias=" + logitBias + ", responseFormat=" + responseFormat
                    + ", stream=" + stream + ", streamOptions=" + streamOptions + ", reasoning=" + reasoning
                    + ", cacheControl=" + cacheControl + ", hostedTools=" + hostedTools + ", extras=" + extras
                    + "]";
        }
        return "ChatRequest[model=" + model + ", messages=" + (messages == null ? 0 : messages.size()) + ", tools="
                + (tools == null ? 0 : tools.size()) + ", stream=" + stream + ", extras="
                + extras.size() + ", system/messages content not logged]";
    }
}
