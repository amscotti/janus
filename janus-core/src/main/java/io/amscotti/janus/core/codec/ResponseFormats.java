package io.amscotti.janus.core.codec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chat Completions {@code response_format} versus Responses {@code text.format}
 * and Anthropic {@code output_config.format}.
 *
 * <p>Canonical {@link io.amscotti.janus.core.model.ChatRequest#responseFormat} is the
 * Chat Completions shape. Responses {@code json_schema} is flat
 * ({@code type, name, strict, schema}); Chat Completions nests those fields under
 * {@code json_schema}. {@code json_object} is the same object on both wires.
 */
public final class ResponseFormats {

    /**
     * Anthropic's structured-output format has no schema name. The OpenAI Chat /
     * Responses wrappers require one, so a cross-format import gets this stable name
     * and strict enforcement (Anthropic's format is schema-enforced too).
     */
    static final String DEFAULT_SCHEMA_NAME = "response";

    private ResponseFormats() {}

    /** Responses {@code text.format} (or already-chat-shaped) → Chat Completions object. */
    public static Map<String, Object> toChat(Map<?, ?> format) {
        if (format == null || format.isEmpty()) {
            return null;
        }
        Map<String, Object> copy = copy(format);
        if (!"json_schema".equals(String.valueOf(copy.get("type"))) || copy.containsKey("json_schema")) {
            return copy;
        }
        Map<String, Object> inner = new LinkedHashMap<>();
        for (String key : new String[] {"name", "strict", "schema", "description"}) {
            if (copy.containsKey(key)) {
                inner.put(key, copy.get(key));
            }
        }
        Map<String, Object> chat = new LinkedHashMap<>();
        chat.put("type", "json_schema");
        chat.put("json_schema", inner);
        return chat;
    }

    /** Chat Completions {@code response_format} → Responses {@code text.format}. */
    public static Map<String, Object> toResponses(Map<?, ?> format) {
        if (format == null || format.isEmpty()) {
            return null;
        }
        Map<String, Object> copy = copy(format);
        if (!"json_schema".equals(String.valueOf(copy.get("type")))) {
            return copy;
        }
        Object nested = copy.get("json_schema");
        if (!(nested instanceof Map<?, ?> inner)) {
            return copy;
        }
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("type", "json_schema");
        inner.forEach((k, v) -> responses.put(String.valueOf(k), v));
        return responses;
    }

    /**
     * Chat Completions {@code response_format} → Anthropic {@code output_config.format}.
     * Anthropic structured outputs carry only {@code {type: "json_schema", schema}}:
     * the Chat wrapper's {@code name}/{@code strict}/{@code description} have no
     * format home and are dropped. Non-{@code json_schema} types
     * ({@code json_object}, {@code text}) and a {@code json_schema} without a schema
     * object have no Anthropic home → {@code null}.
     */
    public static Map<String, Object> toAnthropic(Map<?, ?> format) {
        Map<String, Object> flat = toResponses(format);
        if (flat == null || !"json_schema".equals(String.valueOf(flat.get("type")))) {
            return null;
        }
        Object schema = flat.get("schema");
        if (!(schema instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> anthropic = new LinkedHashMap<>();
        anthropic.put("type", "json_schema");
        anthropic.put("schema", schema);
        return anthropic;
    }

    /**
     * Anthropic {@code output_config.format} → Chat Completions {@code response_format}
     * (nested {@code json_schema} wrapper), or {@code null} when the Anthropic format
     * is not a schema-bearing {@code json_schema} object. OpenAI requires
     * {@code json_schema.name}; Anthropic does not carry one, so this direction adds
     * the stable {@value #DEFAULT_SCHEMA_NAME} name and {@code strict:true}.
     */
    public static Map<String, Object> fromAnthropic(Map<?, ?> format) {
        if (format == null
                || !"json_schema".equals(String.valueOf(format.get("type")))
                || !(format.get("schema") instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> chat = toChat(format);
        Object nested = chat.get("json_schema");
        if (nested instanceof Map<?, ?> rawInner) {
            Map<String, Object> inner = new LinkedHashMap<>();
            rawInner.forEach((key, value) -> inner.put(String.valueOf(key), value));
            inner.putIfAbsent("name", DEFAULT_SCHEMA_NAME);
            inner.putIfAbsent("strict", true);
            chat.put("json_schema", inner);
        }
        return chat;
    }

    private static Map<String, Object> copy(Map<?, ?> format) {
        Map<String, Object> copy = new LinkedHashMap<>();
        format.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }
}
