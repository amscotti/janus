package io.amscotti.janus.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical chat-completion response. {@code stopReason} is normalized to one of the
 * {@code STOP_REASON_*} constants. Like {@link ChatRequest}, {@code meta} is
 * gateway-internal and never serialized; {@code extras} is the opaque pass-through map.
 */
public record ChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<ChatChoice> choices,
        Usage usage,
        String stopReason,
        List<HostedToolCall> hostedToolCalls,
        Map<String, Object> extras,
        @JsonIgnore Map<String, Object> meta) {

    /** Compatibility form at the earlier (9-arg) arity — no hosted tool calls. */
    public ChatResponse(
            String id,
            String object,
            long created,
            String model,
            List<ChatChoice> choices,
            Usage usage,
            String stopReason,
            Map<String, Object> extras,
            Map<String, Object> meta) {
        this(id, object, created, model, choices, usage, stopReason, null, extras, meta);
    }

    public static final String STOP_REASON_STOP = "stop";
    public static final String STOP_REASON_LENGTH = "length";
    public static final String STOP_REASON_TOOL_CALLS = "tool_calls";
    public static final String STOP_REASON_CONTENT_FILTER = "content_filter";
    public static final String STOP_REASON_ERROR = "error";

    public ChatResponse {
        hostedToolCalls = hostedToolCalls == null ? null : List.copyOf(hostedToolCalls);
        choices = choices == null ? null : List.copyOf(choices);
        // extras/meta are the opaque pass-through contract: null-valued fields are
        // legitimate payloads, so copy must tolerate null values (Map.copyOf would NPE).
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
        meta = meta == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(meta));
    }
    /** Completion text is excluded from log output unless content logging is
     * explicitly enabled ({@code [janus.privacy] log-content}). Structure — id, model,
     * choice count, stop reason — still prints so a log line remains diagnosable. */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "ChatResponse[id=" + id + ", model=" + model + ", choices=" + choices + ", usage=" + usage
                    + ", stopReason=" + stopReason + ", extras=" + extras + "]";
        }
        return "ChatResponse[id=" + id + ", model=" + model + ", choices=" + (choices == null ? 0 : choices.size())
                + ", stopReason=" + stopReason + ", extras=" + extras.size() + ", completion content not logged]";
    }
}
