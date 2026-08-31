package io.amscotti.janus.core.codec;

/**
 * The {@code web_search_call} output item: one hosted search the provider
 * performed while answering. The result-document detail rides only with
 * {@code include} flags the stateless face drops — the item carries the action.
 */
public record OpenAiResponsesWebSearchCallItem(String id, String type, String status, Action action)
        implements OpenAiResponsesOutputItem {

    /** The executed action: a search with its query. */
    public record Action(String type, String query) {}
}
