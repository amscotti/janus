package io.amscotti.janus.gateway;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.router.Router;
import io.amscotti.janus.router.SessionAffinityLoadBalancer;
import io.amscotti.janus.store.KeyRecord;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.sse.Event;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shared model-face request ladder: the decode →
 * scope-check → governance-preflight → [stream: wrap → meter-on-close → publisher] |
 * [complete → encode-before-finalize → finalize → budget headers] orchestration, plus
 * the catch-path release/meter/ledger backstop, extracted verbatim from the two
 * controllers that had duplicated it (~80% identical, carrying the
 * exactly-once/release invariants — the worst possible thing to triplicate after the
 * publisher pair's own drift history). A face controller is a thin subclass supplying
 * {@link #face}, {@link #decode}, {@link #encode}, {@link #newPublisher} and
 * {@link #statusOf}; the existing controller suites are the byte-pin that made this
 * refactor safe and must stay green unchanged.
 *
 * <p><b>Invariants preserved verbatim (see the per-line comments):</b>
 * <ul>
 * <li>the pre-dispatch governance gate fires before any upstream dispatch (a throttled
 * request is never sent upstream);</li>
 * <li>the stream branch wraps the upstream BEFORE building the publisher, inside a
 * release-guarded try (a construction throw releases the reservation and closes the
 * upstream instead of orphaning both);</li>
 * <li>non-streaming encodes BEFORE finalize (an encode failure skips every ledger
 * side effect — no settled spend for a 500, exactly one ERROR CallRecord);</li>
 * <li>the catch backstop releases the reservation (idempotent), best-effort-records
 * the coarse status bucket, and writes exactly one Tier-1 ERROR CallRecord
 * );</li>
 * <li>the stream's terminal status threads through the shared {@link AtomicReference}
 * so a mid-stream failure is never counted in the 2xx bucket, and client
 * cancels record 499 (the publisher's flip).</li>
 * </ul>
 *
 * <p>{@code @ExecuteOn(TaskExecutors.BLOCKING)} stays on each face controller's route
 * method (Micronaut resolves it per route, not per base class).
 */
abstract class ModelFaceControllerSupport {

    private static final Logger LOG = LoggerFactory.getLogger(ModelFaceControllerSupport.class);

    /**
     * The inbound request header carrying a client conversation id for
     * session-affinity routing (lowercase-hyphen like the {@code anthropic-beta}
     * inbound precedent; response headers use {@code X-Janus-*} PascalCase — this is
     * a request header). Read on all three faces; HTTP header lookup is
     * case-insensitive.
     */
    static final String HEADER_SESSION_ID = "x-janus-session-id";

    private final Router router;
    private final Governance governance;
    private final MetricsRecorder metricsRecorder;
    private final StreamIdleTimeoutResolver streamIdleTimeouts;

    ModelFaceControllerSupport(
            Router router,
            Governance governance,
            MetricsRecorder metricsRecorder,
            StreamIdleTimeoutResolver streamIdleTimeouts) {
        this.router = Objects.requireNonNull(router, "router");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.streamIdleTimeouts = Objects.requireNonNull(streamIdleTimeouts, "streamIdleTimeouts");
    }

    /** The face's Tier-1 {@code face} label (see {@link Face#label()}). */
    protected abstract String face();

    /** Raw body → canonical (the codec owns every wire byte — no second JSON path). */
    protected abstract ChatRequest decode(String rawBody);

    /**
     * Face-specific inbound-header fold after decode. Default is identity; the
     * Anthropic face copies {@code anthropic-beta} into {@code meta} so the
     * adapter can forward it (Claude Code requires those betas for adaptive
     * thinking / context_management).
     */
    protected ChatRequest decorate(HttpRequest<?> httpRequest, ChatRequest request) {
        return request;
    }

    /**
     * The session-affinity fold, applied by the base {@link #execute} ladder right
     * after {@link #decorate} on <b>every</b> face (folding in the base — not in the
     * per-face {@code decorate} overrides — is what keeps all three faces covered
     * without touching them): the inbound {@value #HEADER_SESSION_ID} header is
     * copied into {@code meta} under {@link SessionAffinityLoadBalancer#META_SESSION_ID}
     * so the session-affinity strategy can pick on it. The value is trimmed;
     * absent/blank-after-trim ⇒ no meta entry at all (never a null/blank value).
     * The session id is client-chosen routing input: it lives in the
     * {@code @JsonIgnore} meta map only — never serialized, never logged, never
     * forwarded upstream (the whitelisted-reader rule on the meta contract).
     */
    private static ChatRequest foldSessionId(HttpRequest<?> httpRequest, ChatRequest request) {
        String sessionId = httpRequest.getHeaders().get(HEADER_SESSION_ID);
        if (sessionId == null) {
            return request;
        }
        String trimmed = sessionId.trim();
        if (trimmed.isEmpty()) {
            return request;
        }
        return request.withMetaEntry(SessionAffinityLoadBalancer.META_SESSION_ID, trimmed);
    }

    /** Canonical response → the face's wire body (encode-before-finalize contract). */
    protected abstract String encode(ChatResponse response);

    /**
     * The request-aware encode: faces whose wire shape echoes request fields
     * (Responses) override this; the default delegates to {@link #encode(ChatResponse)}
     * (the chat faces echo nothing request-side).
     */
    protected String encode(ChatRequest request, ChatResponse response) {
        return encode(response);
    }

    /**
     * The face's SSE publisher over the metered upstream (release-guarded window).
     * The {@code idleTimeout} is resolved <b>per dispatch</b> by the ladder (the
     * serving provider's override, else the global — see {@link #execute}); the
     * face only threads it into its publisher.
     */
    protected abstract Publisher<Event<String>> newPublisher(
            Stream<StreamChunk> metered, Duration idleTimeout, AtomicReference<Integer> terminalStatus);

    /**
     * The request-aware publisher seam: faces whose stream
     * frames echo request fields (Responses: the full response object rides created/
     * completed/failed events) need the request; the chat faces delegate.
     */
    protected Publisher<Event<String>> newPublisher(
            ChatRequest request,
            Stream<StreamChunk> metered,
            Duration idleTimeout,
            AtomicReference<Integer> terminalStatus) {
        return newPublisher(metered, idleTimeout, terminalStatus);
    }

    /** The HTTP status the {@link GatewayExceptionHandler} would map this throwable to. */
    protected abstract int statusOf(Throwable throwable);

    /**
     * The shared ladder (javadoc comments preserved from the extracted controllers —
     * they are the invariant documentation, not decoration).
     */
    HttpResponse<?> execute(String rawBody, HttpRequest<?> httpRequest) {
        long startNanos = System.nanoTime();
        String keyId = keyIdOf(httpRequest);
        ChatRequest request = null;
        Governance.Preflight preflight = Governance.Preflight.NONE;
        // The actually-dispatched backend (per-request holder fed by the router's
        // dispatch observer — the LB pick, or the failover target when a retry walked
        // the chain; last delivery wins). The call ledger's provider column is read
        // from here at finalize/settle/failure time — NEVER re-resolved via
        // Router.route, which is deliberately the config-first candidate in
        // balanced/resilient mode and would misattribute per-provider spend exactly
        // in the multi-provider-alias configs the cost-based balancer exists for.
        // Stays null when no backend was dispatched (decode failure, pre-dispatch
        // denial, unknown model).
        AtomicReference<String> dispatchedProvider = new AtomicReference<>();
        try {
            request = foldSessionId(httpRequest, decorate(httpRequest, decode(rawBody)));
            // step 8: per-key model scope against the client alias (no key attached ⇒
            // no-op, so auth-off behavior is byte-identical).
            KeyAuthFilter.checkScope(httpRequest, request.model());
            // pre-dispatch gate: RPM/TPM/budget 429s happen here, before any upstream
            // dispatch — a throttled request is never sent upstream.
            preflight = governance.enforce(httpRequest, request);
            if (request.stream()) {
                Stream<StreamChunk> upstream = null;
                Publisher<Event<String>> publisher;
                try {
                    // The wrap settles at the terminal usage-bearing chunk and releases
                    // the budget reservation on abort/cancel (the publisher's close
                    // hook); the eager upstream send above may still throw — release on
                    // that path too. The dispatch observer fires before stream(...)
                    // returns, so the holder already holds the serving backend when the
                    // wrap is built with it.
                    Stream<StreamChunk> dispatched =
                            router.stream(request, backend -> dispatchedProvider.set(backend.name()));
                    upstream = governance.wrapStream(preflight, request, dispatched, dispatchedProvider.get());
                    // the stream duration is measured to close — the publisher closes
                    // the upstream stream at exhaustion/cancel, so this hook fires when
                    // the SSE response actually ends. The status of a started
                    // stream is 200, EXCEPT when the publisher emitted a mid-stream
                    // error frame / stall — the publisher threads the terminal outcome
                    // (the mapped 5xx) through the shared reference, so a stream that
                    // failed mid-flight is never counted in the 2xx bucket (it would
                    // silently inflate the SLO error-free rate).
                    AtomicReference<Integer> terminalStatus = new AtomicReference<>(HttpStatus.OK.getCode());
                    Stream<StreamChunk> metered = upstream.onClose(() -> {
                        // The meter hook is best-effort like every other recorder call
                        // site (cf. the catch-path guard below): a throwing recorder (a
                        // Micrometer validation failure) is logged and dropped — the
                        // throw would otherwise escape the SSE worker's stream close
                        // uncaught (the governance close hook, registered before this
                        // one, has already settled/released and closed the upstream;
                        // "recording never alters the request path" holds stream-side
                        // too).
                        try {
                            metricsRecorder.recordRequest(
                                    face(), terminalStatus.get(), elapsedMillis(startNanos), keyId);
                        } catch (RuntimeException e) {
                            LOG.warn("metrics recording dropped (recorder failure): {}", e.toString());
                        }
                    });
                    // The publisher construction stays inside the
                    // release-guarded try — a throw in this window must still release
                    // the reservation and close the upstream stream instead of
                    // orphaning both. The idle deadline resolves per dispatch HERE:
                    // the dispatch observer fired before stream(...) returned, so
                    // dispatchedProvider.get already names the serving backend —
                    // the provider's stream-idle override if one is configured, else
                    // the global (the no-override boot is byte-identical to a
                    // single global deadline).
                    publisher = newPublisher(
                            request, metered, streamIdleTimeouts.resolve(dispatchedProvider.get()), terminalStatus);
                } catch (RuntimeException e) {
                    governance.release(preflight);
                    if (upstream != null) {
                        try {
                            upstream.close();
                        } catch (RuntimeException ignored) {
                            // best-effort: the wrap's close hook already guards its own
                            // upstream close; nothing to surface on this failure path.
                        }
                    }
                    throw e;
                }
                return HttpResponse.ok(publisher).contentType(MediaType.TEXT_EVENT_STREAM);
            }
            ChatResponse response;
            try {
                response = router.complete(request, backend -> dispatchedProvider.set(backend.name()));
            } catch (RuntimeException | Error e) {
                // Abort path: an upstream/codec failure after the reservation must not
                // leak pending spend — release before rethrowing (pinned by test). An
                // Error is released too, then rethrown unchanged (the router's own
                // discipline — an Error must release its slot and feed health).
                governance.release(preflight);
                throw e;
            }
            String body;
            try {
                // Encode BEFORE finalize — an encode failure (a canonical the
                // codec's validateResponse rejects) must skip every ledger/spend side
                // effect: no settled spend for a request the client receives as a 500,
                // no OK CallRecord (the exactly-once pin — only the catch-path
                // recordFailure fires, exactly one ERROR row).
                body = encode(request, response);
            } catch (RuntimeException | Error e) {
                // Encode failure after the reservation: release so the 500 does not leak
                // pending spend (mirrors the upstream-abort path above; an Error is
                // released and rethrown unchanged).
                governance.release(preflight);
                throw e;
            }
            Governance.Finalized finalized = governance.finalize(
                    httpRequest, request, response, preflight, elapsedMillis(startNanos), dispatchedProvider.get());
            // Record AFTER encodeResponse/finalize — an encode failure threw
            // above and the catch below records the error bucket once (no double
            // 2xx+error).
            metricsRecorder.recordRequest(face(), HttpStatus.OK.getCode(), elapsedMillis(startNanos), keyId);
            MutableHttpResponse<String> http = HttpResponse.ok(body).contentType(MediaType.APPLICATION_JSON);
            return withBudgetHeaders(http, finalized);
        } catch (RuntimeException | Error e) {
            // A throw from the settle path (a throwing ledger, a
            // negative-usage CostCalculator rejection, or an Error) must not leak the reservation —
            // release here as the backstop (the guarded settle path already released).
            // Safe for every other path: enforce-denied requests carry Preflight.NONE
            // (no-op), the abort paths already released (idempotent, pending clamped at
            // 0), and a post-settle release is a harmless no-op.
            governance.release(preflight);
            // record the coarse status bucket the exception handler will produce
            // (4xx/5xx) and rethrow unchanged — the error envelope is byte-identical
            // (recording never alters the request path).
            recordError(startNanos, keyId, e);
            // one Tier-1 CallRecord for the rejected/failed request (the status
            // mapping is the exception handler's coarse class — 429 → limit, etc.);
            // httpRequest threads the key for pre-dispatch denials (m1: a throttled
            // request must be attributed to the key, never the auth-off sentinel).
            governance.recordFailure(
                    httpRequest, preflight, request, statusOf(e), elapsedMillis(startNanos), dispatchedProvider.get());
            throw e;
        }
    }

    /**
     * The catch-path {@code recordRequest} is best-effort like every other
     * collaborator on this path ({@code writeCallRecord}/{@code notifySoft} swallow+log)
     * — a throwing recorder (a mis-wired adapter, a Micrometer validation failure) must
     * never replace the client's true 401/429/502 envelope with a 500 or skip the
     * call-ledger {@code recordFailure}: log and drop, then rethrow the original
     * exception (the "recording never alters the request path" invariant).
     */
    private void recordError(long startNanos, String keyId, Throwable throwable) {
        try {
            metricsRecorder.recordRequest(face(), statusOf(throwable), elapsedMillis(startNanos), keyId);
        } catch (RuntimeException e) {
            LOG.warn("metrics recording dropped (recorder failure): {}", e.toString());
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /**
     * the governing {@link KeyRecord#id} (null = auth-off). The id is the
     * opaque, non-secret key identifier the per-key series are labeled with (Tier-1
     * privacy contract — see {@link MetricsRecorder}).
     */
    private static String keyIdOf(HttpRequest<?> httpRequest) {
        return httpRequest
                .getAttribute(KeyAuthFilter.KEY_ATTRIBUTE, KeyRecord.class)
                .map(KeyRecord::id)
                .orElse(null);
    }

    /** attach spend headers to a successful non-streaming response. */
    private static MutableHttpResponse<String> withBudgetHeaders(
            MutableHttpResponse<String> response, Governance.Finalized finalized) {
        MutableHttpResponse<String> out = response;
        if (finalized != Governance.Finalized.NONE && finalized.cost() != null) {
            var cost = finalized.cost();
            out = out.header(Governance.HEADER_COST_INPUT, String.valueOf(cost.inputMicroUsd()))
                    .header(Governance.HEADER_COST_OUTPUT, String.valueOf(cost.outputMicroUsd()))
                    .header(Governance.HEADER_COST_CACHE_READ, String.valueOf(cost.cacheReadMicroUsd()))
                    .header(Governance.HEADER_COST_CACHE_CREATION, String.valueOf(cost.cacheCreationMicroUsd()))
                    .header(Governance.HEADER_COST_SEARCH, String.valueOf(cost.searchMicroUsd()))
                    .header(Governance.HEADER_COST_TOTAL, String.valueOf(cost.totalMicroUsd()));
        }
        if (!finalized.softExceeded()) {
            return out;
        }
        out = out.header(Governance.HEADER_BUDGET_WARNING, Governance.WARNING_SOFT);
        if (finalized.usedMicroUsd() != null) {
            out = out.header(Governance.HEADER_BUDGET_USED, String.valueOf(finalized.usedMicroUsd()));
        }
        return out;
    }
}
