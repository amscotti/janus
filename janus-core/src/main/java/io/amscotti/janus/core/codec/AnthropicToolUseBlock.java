package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool invocation block (assistant side). Wire shape:
 * {@code {"type":"tool_use","id":"...","name":"...","input":{...}}} plus optional
 * {@code cache_control} (legal on any content block — the standard agent-loop caching
 * pattern puts the breakpoint on the last tool_use). The codec translates these to/from
 * canonical {@link io.amscotti.janus.core.model.ToolCall}s ({@code tool_use.id} ↔
 * {@code ToolCall.id}, decoded {@code input} object ↔ canonical raw-JSON
 * {@code arguments}); the marker has no per-call canonical home, so decode captures it
 * into the request-level {@code cacheControl} fallback and encode re-emits it through
 * that fallback's placement (never dropped).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicToolUseBlock(String type, String id, String name, Object input, Object cacheControl)
        implements AnthropicContentBlock {

    /** Tool-use block with no cache marker. */
    public AnthropicToolUseBlock(String type, String id, String name, Object input) {
        this(type, id, name, input, null);
    }
}
