package io.amscotti.janus.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Canonical System One decision response: one {@link DecisionAnswer} per
 * question name, plus token {@link Usage}. Usage follows the chat
 * convention (prompt tokens are regular input, cache splits ride their own
 * fields) so the existing pricing and call-ledger machinery applies
 * unchanged; System One upstreams report {@code output_tokens: 0} and bill
 * input only.
 *
 * <p>{@code extras} carries unmodeled upstream fields (e.g. Ollama's {@code
 * prompt_eval_cached_count}) through to the client encode; {@code meta} is
 * gateway-internal and never serialized.
 */
public record DecisionResponse(
        String model,
        Map<String, DecisionAnswer> answers,
        Usage usage,
        Map<String, Object> extras,
        @JsonIgnore Map<String, Object> meta) {

    public DecisionResponse {
        answers = answers == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(answers));
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
        meta = meta == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(meta));
    }

    /** Answers are decision content: structure only, unless content logging is on. */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "DecisionResponse[model=" + model + ", answers=" + answers + ", usage=" + usage + ", extras="
                    + extras + "]";
        }
        return "DecisionResponse[model=" + model + ", answers=" + (answers == null ? 0 : answers.size()) + ", usage="
                + usage + ", extras=" + extras.size() + ", answers content not logged]";
    }
}
