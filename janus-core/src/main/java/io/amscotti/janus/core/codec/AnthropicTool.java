package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A tool declaration on an Anthropic request ({@code name}/{@code description}/
 * {@code input_schema}/{@code cache_control}). The codec translates these to/from
 * canonical {@code tools} ({@code input_schema} object ↔ canonical raw-JSON schema
 * {@code arguments}, {@code description} ↔ {@code FunctionCall.description}); a missing/
 * blank canonical schema encodes as the default
 * {@code {"type":"object","properties":{}}} (openai_tool_to_anthropic
 * precedent).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicTool(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Object cacheControl,
        String type,
        Integer maxUses,
        Map<String, Object> userLocation) {

    /** Compatibility form at the earlier (4-arg) arity — a custom tool (no type slot). */
    public AnthropicTool(String name, String description, Map<String, Object> inputSchema, Object cacheControl) {
        this(name, description, inputSchema, cacheControl, null, null, null);
    }

    public AnthropicTool {
        inputSchema = inputSchema == null ? null : Collections.unmodifiableMap(new HashMap<>(inputSchema));
        userLocation = userLocation == null ? null : Collections.unmodifiableMap(new HashMap<>(userLocation));
    }
}
