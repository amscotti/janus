package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Anthropic's {@code server_tool_use} content block: a
 * hosted server-side tool execution the model performed — the web-search shape is
 * {@code {type:"server_tool_use", id, name:"web_search", input:{query}}}. Previously
 * this block fell into {@link AnthropicUnknownBlock} and was dropped; the decode maps the
 * web-search spelling into the canonical {@code HostedToolCall.WebSearchCall}
 * (response-level output). Other server-tool names stay dropped via the unknown block
 * (the versioning tolerance is unchanged).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicServerToolUseBlock(String type, String id, String name, Map<String, Object> input)
        implements AnthropicContentBlock {

    public AnthropicServerToolUseBlock {
        input = input == null ? null : Collections.unmodifiableMap(new HashMap<>(input));
    }
}
