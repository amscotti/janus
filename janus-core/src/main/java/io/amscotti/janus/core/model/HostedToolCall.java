package io.amscotti.janus.core.model;

/**
 * A <b>hosted</b> tool <em>invocation</em> observed on a {@link ChatResponse} (used by
 * the OpenAI Responses face): a search or other server-side execution the provider
 * performed while answering. Carried on the response (not inside a choice's message)
 * because it is response-level output, like {@code usage} — the assistant text still
 * rides the normal choices. The Responses face emits these as typed output items
 * ({@code web_search_call}); the Anthropic face re-emits them as
 * {@code server_tool_use} blocks; the OpenAI chat face has no wire home and drops
 * them (documented — its ingress never produces them either).
 */
public sealed interface HostedToolCall permits HostedToolCall.WebSearchCall {

    /** One executed web search. */
    record WebSearchCall(String query) implements HostedToolCall {

        /** The search query is user conversation content: excluded from log output
         * unless content logging is explicitly enabled ({@code [janus.privacy]
         * log-content}). */
        @Override
        public String toString() {
            if (ContentLogging.enabled()) {
                return "WebSearchCall[query=" + query + "]";
            }
            return "WebSearchCall[query not logged]";
        }
    }
}
