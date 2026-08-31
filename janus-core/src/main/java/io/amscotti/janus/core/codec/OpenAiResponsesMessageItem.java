package io.amscotti.janus.core.codec;

import java.util.List;

/**
 * The {@code message} output item: assistant text content as one {@code output_text}
 * part. {@code annotations} is always the empty list (hosted-tool citations are not
 * modeled yet).
 */
public record OpenAiResponsesMessageItem(
        String id, String type, String status, String role, List<OpenAiResponsesOutputText> content)
        implements OpenAiResponsesOutputItem {

    public OpenAiResponsesMessageItem {
        content = content == null ? null : List.copyOf(content);
    }
}
