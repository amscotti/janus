/**
 * The gateway's HTTP faces: {@code POST /v1/chat/completions}, {@code POST
 * /v1/messages}, {@code POST /v1/responses} (non-streaming + SSE), {@code GET
 * /v1/models}, admin {@code /key/*}, and OpenAI error-envelope mapping. This
 * package is the HTTP glue. The wire bytes are owned by {@link
 * io.amscotti.janus.core.codec.OpenAiMessageCodec}, model → backend resolution by
 * {@link io.amscotti.janus.router.Router}, and the upstream call by the {@link
 * io.amscotti.janus.provider.ProviderAdapter}; everything here adapts, frames,
 * and maps.
 *
 * <p><b>Wire-byte ownership (non-negotiable).</b> The codec is the single owner of the
 * OpenAI wire shape. The gateway takes the request as a raw {@code @Body String} and
 * calls {@code codec.decodeRequest} (no Micronaut DTO binding, no second JSON path);
 * non-streaming responses are the {@code String} from {@code codec.encodeResponse};
 * each SSE frame is {@code Event.of(codec.encodeChunk(chunk))} terminated by
 * {@code Event.of("[DONE]")} — Micronaut's {@code TextStreamBodyWriter} writes String
 * event data verbatim as {@code data: <value>\n\n} (pinned by byte-level integration
 * assertions). Gateway-owned serialization is limited to the {@code gateway.dto}
 * package: {@link io.amscotti.janus.gateway.dto.OpenAiErrorEnvelope}, {@link
 * io.amscotti.janus.gateway.dto.ModelsResponse}, {@link
 * io.amscotti.janus.gateway.dto.ModelEntry} and the admin-keys API records
 * ({@code io.amscotti.janus.gateway.dto.*}); those carry explicit {@code @JsonProperty}s
 * and explicit native-image reflect-config entries. The {@code gateway.dto} subpackage
 * is the DTO convention the drift guard enforces <em>annotation-independently</em> —
 * every class there must have a {@code reflect-config.json} entry (a serialized-but-
 * unannotated DTO can no longer escape native-image coverage).
 *
 * <p><b>Error mapping.</b> {@link ErrorMapper} is table-driven: {@code
 * OpenAiCodecException} (invalid request → 400, api error → 500), {@code
 * UnknownModelException} → 404 {@code model_not_found} (a drop-in SDK face
 * must not return a retryable 503), {@code ProviderException} by
 * {@code type} (auth → 401, rate limited → 429, upstream 5xx → 502, upstream 4xx →
 * upstream status when present else 502, network → 502, timeout → 504, bad payload →
 * 502), and any unexpected runtime exception → 500 {@code api_error}. Streaming
 * failures split by phase: eager transport/status failures throw from the controller
 * (the global {@link GatewayExceptionHandler} returns a normal HTTP error envelope —
 * no stream was started); failures <em>during</em> iteration are caught by {@link
 * SseChunkPublisher} and emitted as an SSE {@code data: {"error": …}} frame, then the
 * stream completes — never a mid-stream HTTP error, never a hang.
 *
 * <p><b>Concurrency (Micronaut 5.1 reality).</b> The pinned BOM (5.1.0) has no
 * {@code thread-selection: virtual} dispatch (that arrived in 5.2), so the controller
 * methods run on {@code TaskExecutors.BLOCKING} — the eager upstream send in {@code
 * router.stream} never runs on the Netty event loop — and {@link SseChunkPublisher}
 * iterates the upstream {@code Stream} on a JDK virtual thread
 * ({@code Thread.ofVirtual}, native-image clean on JDK 25), which is the hot
 * per-stream path. An idle watchdog (a single daemon {@code ScheduledThreadPoolExecutor})
 * stall-closes any stream idle &gt; 60 s, releasing the upstream connection (the
 * hand-off). A true per-request virtual-thread dispatch is a 5.2+ BOM bump — noted for
 * review, not done here.
 *
 * <p><b>Wiring.</b> {@link ProviderAdapterChatBackend} is the hand-off: it adapts a
 * {@code ProviderAdapter} to {@code ChatBackend} by delegating {@code name/complete/
 * stream}, returning the stream <em>by identity</em> so the close-releases-the-
 * connection contract survives. {@link RouterFactory} builds the {@link
 * io.amscotti.janus.router.Router} from the bound {@code JanusConfig} model-list,
 * load-balancer strategy, and resilience knobs.
 */
package io.amscotti.janus.gateway;
