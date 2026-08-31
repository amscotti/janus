package io.amscotti.janus.core.model;

import java.util.Map;

/**
 * A <b>hosted</b> tool declaration on a {@link ChatRequest} (used by the OpenAI
 * Responses face): a tool executed <em>server-side</em> by the provider (OpenAI
 * built-ins, Anthropic server tools) rather than round-tripped to the client. Sealed
 * and first-class — extras are pass-through by contract and cannot carry the
 * translation semantics these need.
 *
 * <p>Populated by the Responses face ({@code tools[].type = web_search}) and by
 * Anthropic Messages ingress ({@code tools[].type = web_search_20250305}). Each
 * egress codec translates it to its provider's shape or throws a typed
 * {@code unsupported_hosted_tool}. The OpenAI Chat Completions ingress never sets
 * it (that wire has no hosted-tool spelling).
 */
public sealed interface HostedToolDefinition permits HostedToolDefinition.WebSearch {

    /**
     * The Responses API's {@code web_search} built-in (legacy alias
     * {@code web_search_preview} accepted on decode).
     *
     * @param searchContextSize {@code "low"|"medium"|"high"} (optional; the provider
     *        default applies when null)
     * @param userLocation the optional user-location hint (passthrough shape)
     */
    record WebSearch(String searchContextSize, Map<String, Object> userLocation) implements HostedToolDefinition {

        public WebSearch {
            userLocation = userLocation == null
                    ? null
                    : java.util.Collections.unmodifiableMap(new java.util.HashMap<>(userLocation));
        }

        /** {@code userLocation} is a user-provided PII map: excluded from log output
         * unless content logging is explicitly enabled ({@code [janus.privacy]
         * log-content}). */
        @Override
        public String toString() {
            if (ContentLogging.enabled()) {
                return "WebSearch[searchContextSize=" + searchContextSize + ", userLocation=" + userLocation + "]";
            }
            return "WebSearch[searchContextSize=" + searchContextSize + ", userLocation not logged]";
        }
    }
}
