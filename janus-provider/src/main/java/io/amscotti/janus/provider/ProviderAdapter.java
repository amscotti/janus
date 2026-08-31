package io.amscotti.janus.provider;

import io.amscotti.janus.core.codec.OpenAiCodecException;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import java.util.stream.Stream;

/**
 * Provider SPI (AGENTS.md): a chat-completions upstream Janus can route to. A provider
 * adapter translates canonical model types to/from its own wire format and performs
 * the upstream HTTP call.
 *
 * <p><b>Contract.</b>
 *
 * <ul>
 * <li>{@link #name} is the router key: stable, unique, lower-case
 * (DeepSeek: {@code "deepseek"}).
 * <li>{@link #baseUrl} returns the normalized base URL — no trailing slash, and a
 * trailing {@code /v1} stripped (the reference normalization rule). Each adapter then appends
 * its own full versioned path — {@code /v1/chat/completions} (OpenAI-compatible) resp.
 * {@code /v1/messages} (Anthropic) — so {@code https://api.openai.com} and {@code
 * https://api.openai.com/v1} both reach {@code /v1/chat/completions}.
 * <li>{@link #auth} describes how the adapter authenticates upstream.
 * <li>{@link #complete(ChatRequest)} performs a non-streaming upstream completion.
 * <li>{@link #stream(ChatRequest)} returns a lazily-parsed {@link Stream} of canonical
 * chunks. The caller <b>must</b> close the returned stream (try-with-resources or a
 * finally block) to release the upstream connection — closing is the only way the
 * connection is released, even after the stream has been fully consumed (the
 * gateway,, owns the lifecycle; pins the contract with a test).
 * </ul>
 *
 * <p><b>Errors.</b> Upstream and transport failures surface as unchecked {@link
 * ProviderException} carrying a stable {@code type} discriminator (the gateway,, maps
 * it to the OpenAI error envelope without re-parsing) and a {@code retryable} flag (the
 * router,, reads it for its retry policy). Client-side canonical validation failures
 * surface as {@link OpenAiCodecException} (invalid request) — those are caller bugs, not
 * provider failures.
 *
 * <p><b>Meta.</b> {@link ChatRequest#meta} is gateway-internal context (request id, key
 * id, attempt,...). The adapter must never serialize it, must not read it for wire
 * decisions, and must pass it through to the returned {@link ChatResponse} untouched.
 * Gateway-internal context must not be smuggled to the upstream. The one whitelisted
 * exception: the gateway copies the inbound client header {@code anthropic-beta} into
 * the {@code anthropic-beta} meta entry so the Anthropic adapter can forward it as the
 * upstream {@code anthropic-beta} header (a per-request client opt-in, not
 * gateway-internal context).
 *
 * <p><b>Concurrency.</b> Adapters are expected to be stateless and thread-safe: a single
 * adapter instance serves concurrent requests once the router and gateway wire
 * it. State that is not thread-safe (a client, a codec, a connection pool) must be held in
 * effectively-final fields built at construction.
 *
 * <p><b>Timeout semantics.</b> The JDK {@code HttpClient} request timeout covers
 * <b>header arrival only</b> — on {@link #complete(ChatRequest)} and {@link
 * #stream(ChatRequest)} alike, so a slow upstream that has not answered with response
 * headers by the request deadline surfaces as {@link ProviderException#TYPE_TIMEOUT},
 * while a long-running completion whose headers have already arrived is <em>not</em> cut
 * at that wall-clock deadline. Once response headers have arrived, the <b>non-streaming</b>
 * body — the success body and the non-2xx error body alike — is read under the adapter's
 * {@code bodyReadTimeout} (a generous wall-clock deadline, default 300 s) so a stalled
 * upstream cannot pin a worker thread forever; exceeding it surfaces as {@link
 * ProviderException#TYPE_TIMEOUT}. The <b>streaming</b> body is read incrementally and is
 * not wall-clock-bounded by the adapter: the gateway owns the streaming (SSE idle)
 * deadline.
 */
public interface ProviderAdapter {

    /** Router key: stable, unique, lower-case. */
    String name();

    /**
     * Normalized upstream base URL — no trailing slash, trailing {@code /v1} stripped;
     * the adapter appends its own full versioned path ({@code /v1/chat/completions}
     * resp. {@code /v1/messages}) on top of it.
     */
    String baseUrl();

    /** Authentication scheme + credential for the upstream. */
    ProviderAuth auth();

    /** Non-streaming upstream completion. */
    ChatResponse complete(ChatRequest request);

    /**
     * Streaming upstream completion as a lazily-parsed {@link Stream} of canonical chunks.
     * The upstream HTTP request is sent eagerly (transport/status failures throw before any
     * chunk); SSE frames are decoded as the stream is consumed. The caller must close the
     * returned stream to release the upstream connection.
     */
    Stream<StreamChunk> stream(ChatRequest request);
}
