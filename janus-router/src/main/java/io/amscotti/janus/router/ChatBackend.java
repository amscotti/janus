package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import java.util.stream.Stream;

/**
 * Provider-agnostic seam the router routes to. One instance per upstream provider
 * entry, keyed in the {@link Router} by model alias. Deliberately <i>not</i> the
 * janus-provider {@code ProviderAdapter} (no {@code auth}) so the router stays within
 * AGENTS.md's "provider/router depend on core only" boundary; the gateway adapts
 * a {@code ProviderAdapter} to this interface with a small delegating adapter.
 *
 * <p><b>Contract.</b>
 *
 * <ul>
 * <li>{@link #name} is the provider name — stable, unique, lower-case (DeepSeek:
 * {@code "deepseek"}); used for display and as the router key.
 * <li>{@link #baseUrl} is the normalized upstream base URL —
 * lets observability distinguish distinct backend <em>instances</em> that share a
 * provider name (two entries for one provider under different aliases) instead of
 * collapsing them onto one Prometheus label set.
 * <li>{@link #complete(ChatRequest)} performs one non-streaming completion.
 * <li>{@link #stream(ChatRequest)} returns a lazily-parsed {@link Stream} of canonical
 * chunks. The caller <b>must</b> close the returned stream (try-with-resources or a
 * finally block) to release the upstream connection — closing is the only way the
 * connection is released, even after the stream has been fully consumed. The gateway
 * owns this lifecycle; the router preserves it by returning the stream unwrapped.
 * <li>{@code meta} passes through untouched for backends: delegation is
 * transparent, a backend never writes {@link ChatRequest#meta} or
 * {@link ChatResponse#meta}, and reads only its own documented whitelisted
 * entries. Meta is gateway-internal context — {@code @JsonIgnore} on the wire,
 * excluded from {@code toString} (never logged) — with a whitelisted-reader rule:
 * the router's session-affinity strategy <b>reads</b> the {@code janus.session-id}
 * entry (the gateway's fold of the inbound {@code x-janus-session-id} header) and
 * never writes; the Anthropic adapter reads the whitelisted
 * {@code anthropic-beta} entry to forward it upstream. A backend that reads or
 * writes any other meta entry is a contract violation (privacy: meta values must
 * never reach a log line or an upstream payload beyond those whitelisted).
 * <li>Failures surface as unchecked {@code ProviderException} from the adapted backend
 * (janus-provider) and are propagated as-is by the router (v1 makes exactly one
 * attempt).
 * </ul>
 *
 * <p><b>Concurrency.</b> Backends are expected to be stateless and thread-safe: a single
 * instance serves concurrent requests once the router and gateway wire it. State that is
 * not thread-safe must be held in effectively-final fields built at construction.
 */
public interface ChatBackend {

    /** Provider name — display/router key, stable, unique, lower-case. */
    String name();

    /** Normalized upstream base URL (identity dimension for observability). */
    String baseUrl();

    /** One non-streaming completion. */
    ChatResponse complete(ChatRequest request);

    /**
     * Streaming completion as a lazily-parsed {@link Stream} of canonical chunks. The
     * caller must close the returned stream to release the upstream connection.
     */
    Stream<StreamChunk> stream(ChatRequest request);
}
