package io.amscotti.janus.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One SSE chunk of a streaming completion. {@code usage} is nullable (only present when
 * {@code stream_options.include_usage} was requested, typically on the final chunk);
 * {@code extras} is the opaque pass-through map.
 *
 * <p><b>Deliberate asymmetry:</b> unlike {@link ChatRequest} and {@link ChatResponse},
 * this record has no {@code @JsonIgnore meta} slot for gateway-internal context. Do
 * <em>not</em> reach for {@code extras} for internal context — {@code extras} is
 * <em>serialized</em> to providers (its null-tolerance is the wire pass-through contract)
 * and using it for request-id/attempt context would leak internal state to the wire.
 * Add a {@code @JsonIgnore meta} component (defaulting to {@code Map.of} like the
 * siblings) when the streaming path first needs per-chunk gateway context.
 */
public record StreamChunk(
        String id,
        String object,
        long created,
        String model,
        List<ChunkChoice> choices,
        Usage usage,
        Map<String, Object> extras) {

    public StreamChunk {
        choices = choices == null ? null : List.copyOf(choices);
        // extras is the opaque pass-through contract: null-valued fields are legitimate
        // payloads, so the copy must tolerate null values (Map.copyOf would NPE).
        extras = extras == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(extras));
    }
    /** Streaming deltas are completion text: excluded from log output unless content
     * logging is explicitly enabled ({@code [janus.privacy] log-content}). */
    @Override
    public String toString() {
        if (ContentLogging.enabled()) {
            return "StreamChunk[id=" + id + ", model=" + model + ", choices=" + choices + ", usage=" + usage
                    + ", extras=" + extras + "]";
        }
        return "StreamChunk[id=" + id + ", model=" + model + ", choices=" + (choices == null ? 0 : choices.size())
                + ", usage="
                + (usage == null ? "absent" : "present") + ", extras=" + extras.size()
                + ", delta content not logged]";
    }
}
