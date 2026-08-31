package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A tool result block (user side). Wire shape:
 * {@code {"type":"tool_result","tool_use_id":"...","content":"..."|[...],"is_error":bool}}.
 * {@code content} is {@code Object} (string or block array); {@code isError} nullable.
 * The codec translates these to/from canonical {@link io.amscotti.janus.core.model.ToolMessage}s
 * ({@code tool_use_id} ↔ {@code toolCallId}); {@code is_error} has no canonical home and
 * is dropped on decode (documented + pinned — OpenAI has no equivalent either).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnthropicToolResultBlock(String type, String toolUseId, Object content, Boolean isError)
        implements AnthropicContentBlock {}
