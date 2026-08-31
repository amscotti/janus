package io.amscotti.janus.core.codec;

/**
 * The {@code function_call} output item: {@code call_id} is the tool-call correlation
 * id the client replays in {@code function_call_output} (wire {@code call_id}); {@code id} is the synthesized
 * item id ({@code fc_…}). One canonical {@code ToolCall.id} feeds BOTH (the
 * response table; LiteLLM precedent).
 */
public record OpenAiResponsesFunctionCallItem(
        String id, String type, String status, String callId, String name, String arguments)
        implements OpenAiResponsesOutputItem {}
