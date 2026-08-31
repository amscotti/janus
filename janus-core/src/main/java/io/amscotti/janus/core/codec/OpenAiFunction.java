package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI function metadata. {@code parameters} is a JSON-schema object (wire form); the
 * canonical side carries the schema as a raw JSON string (see {@link OpenAiTool}).
 * {@code description} maps to/from the canonical {@link FunctionCall.description}.
 * {@code strict} is OpenAI structured-outputs' flag — the
 * canonical {@link ToolDefinition#strict} slot, omitted when null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiFunction(String name, String description, Map<String, Object> parameters, Boolean strict) {

    public OpenAiFunction {
        parameters = parameters == null ? null : Collections.unmodifiableMap(new HashMap<>(parameters));
    }
}
