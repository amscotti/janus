package io.amscotti.janus.core.codec;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One output item of a Responses API response object, discriminated by the wire
 * {@code type} property (the {@link AnthropicContentBlock} pattern — a real record
 * component on every subtype via {@code As.EXISTING_PROPERTY}, so the wire output is
 * exactly one {@code type} field per item).
 *
 * <p>Items: {@code message} (assistant text), {@code function_call}, and the
 * {@code web_search_call} family. Encode-only today (the face builds these from the
 * canonical response; it never parses an upstream Responses object — upstreams speak
 * chat-completions), but the closed {@code @JsonSubTypes} keeps a future decode
 * (stateful retrieval, if ever added) native-image clean.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = OpenAiResponsesMessageItem.class, name = "message"),
    @JsonSubTypes.Type(value = OpenAiResponsesFunctionCallItem.class, name = "function_call"),
    @JsonSubTypes.Type(value = OpenAiResponsesWebSearchCallItem.class, name = "web_search_call"),
})
public sealed interface OpenAiResponsesOutputItem
        permits OpenAiResponsesMessageItem, OpenAiResponsesFunctionCallItem, OpenAiResponsesWebSearchCallItem {

    /** The synthesized item id ({@code msg_…} / {@code fc_…}) — distinct from call ids. */
    String id();

    /** Item lifecycle on the wire: {@code completed} for a fully-delivered item. */
    String status();
}
