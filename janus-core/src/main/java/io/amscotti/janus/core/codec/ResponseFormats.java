package io.amscotti.janus.core.codec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Chat Completions {@code response_format} versus Responses {@code text.format}.
 *
 * <p>Canonical {@link io.amscotti.janus.core.model.ChatRequest#responseFormat} is the
 * Chat Completions shape. Responses {@code json_schema} is flat
 * ({@code type, name, strict, schema}); Chat Completions nests those fields under
 * {@code json_schema}. {@code json_object} is the same object on both wires.
 */
public final class ResponseFormats {

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

    private static Map<String, Object> copy(Map<?, ?> format) {
        Map<String, Object> copy = new LinkedHashMap<>();
        format.forEach((k, v) -> copy.put(String.valueOf(k), v));
        return copy;
    }
}
