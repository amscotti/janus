package io.amscotti.janus.gateway;

import io.amscotti.janus.store.KeyGenerator;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * request-auth middleware: a path-aware {@link
 * HttpServerFilter} running before the controllers, reading <b>headers only</b> —
 * never the body, so streaming bodies are untouched and auth failures are plain
 * HTTP envelopes (pre-stream), never SSE frames.
 *
 * <ul>
 * <li>{@code /v1/chat/completions} + {@code /v1/messages} → <b>virtual-key auth</b>:
 * Bearer or {@code x-api-key} (both faces' SDKs), then a single atomic
 * {@link KeyStore#authenticate} call that verifies the salted hash and checks
 * the lifecycle — {@code ACTIVE} + not past {@code expiresAt} ⇒ authenticated
 * (updates {@code lastUsedAt} inside the same atomic transition and attaches
 * the {@link KeyRecord} to the request for the controllers' scope check; a
 * racing revoke either lands before ⇒ 403 or after ⇒ the check already passed,
 * never a torn state). Missing/invalid/expired → 401; revoked → 403 (the reference
 * semantics — clients can distinguish "bad key" from "key taken away").
 * <li>{@code /key/*} → <b>master-key auth</b> (Bearer or {@code x-api-key},
 * timing-safe compare): a virtual key is rejected, the master key accepted.
 * <li>{@code /health} (and, from {@code /metrics}) and every unlisted path
 * (e.g. {@code /v1/models}) → <b>exempt</b> in the plan scopes the auth
 * surface to the two model routes + admin routes; a stricter default for
 * unlisted paths is a governance decision.
 * </ul>
 *
 * <p><b>Filter-level denials are Tier-1 metered.</b> A rejection
 * thrown here never reaches a controller, so {@link #doFilter} records the request
 * itself (face × coarse 4xx bucket) before rethrowing — see the catch in
 * {@code doFilter}. The call ledger is deliberately <em>not</em> written on
 * this path: a pre-dispatch auth denial is a middleware rejection,
 * not a call — it carries no model/usage/cost, and {@code Governance.recordFailure}
 * is only reachable from the controllers. The documented asymmetry is: the metrics
 * series counts the 4xx, the {@code calls} table has no row for it (pinned by
 * {@code MetricsAuthRejectionTest}). A <b>store failure on the auth path</b>
 * (e.g. {@code PgKeyStore}'s {@code IllegalStateException} when Postgres is down)
 * is likewise metered here — face × <b>5xx</b> — before the exception propagates, so a
 * DB outage leaves metric evidence even though no controller ever ran. The
 * recording itself is best-effort (a throwing recorder must never replace the
 * client's true 401/403/429/5xx envelope with the recorder's own failure — the same
 * guard {@code AdminKeysController}'s catch path uses).
 *
 * <p><b>Blocking-store discipline.</b> The virtual-key leg is one blocking
 * {@link KeyStore#authenticate} round-trip — with {@code [janus.store] type =
 * "postgres"} that is pool checkout + SELECT + UPDATE over JDBC. Micronaut runs
 * {@code doFilter} on the Netty event loop, so that work is <b>deferred</b> to
 * {@link TaskExecutors#BLOCKING} (a {@link DeferredAuthPublisher}); the chain
 * proceeds only after the decision passes, and a typed rejection travels as the
 * publisher's error, which {@link GatewayExceptionHandler} renders in the same
 * face-appropriate envelope a synchronous throw produces. The master-key leg is pure
 * in-memory work (one constant-time compare + the {@link MasterKeyThrottle} window)
 * and stays inline — the exact posture {@code AdminExecuteOnTest} pins for the admin
 * controllers applies to the auth filter too: JDBC never runs on the IO thread.
 *
 * <p><b>Auth-off is explicit.</b> When the master key is null ({@code [janus.keys]
 * auth = "off"}), the filter is a no-op passthrough. The boot default is
 * {@code auth = "on"}: a missing key fails the process in {@link MasterKeyProvider}
 * before this filter is asked to serve traffic.
 *
 * <p>Failures throw the typed {@link KeyAuthException}, which {@link
 * GatewayExceptionHandler} (path-aware since renders in the face-appropriate
 * envelope via the new {@link ErrorMapper}/{@link AnthropicErrorMapper} rows.
 *
 * <p><b>Clock.</b> Expiry and {@code lastUsedAt} are governed by the store's injected
 * {@link Clock} (the store reads it inside {@link KeyStore#authenticate},
 * discipline) — the filter never reads a hardcoded {@code Clock.systemUTC}.
 */
@Filter("/**")
class KeyAuthFilter implements HttpServerFilter {

    private static final Logger LOG = LoggerFactory.getLogger(KeyAuthFilter.class);

    /** Request attribute carrying the authenticated {@link KeyRecord} ( step 8 scope check). */
    static final String KEY_ATTRIBUTE = "janus.key.record";

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String API_KEY_HEADER = "x-api-key";
    private static final String BEARER_PREFIX = "Bearer ";

    /** Compiled once instead of {@code String.replaceAll}'s per-call recompile. */
    private static final Pattern MULTI_SLASH = Pattern.compile("/{2,}");

    /**
     * The unit-test executor: runs the deferred auth inline on the subscribing thread,
     * so direct-construction tests observe the decision synchronously (no pool to
     * await). The production executor is the injected {@link TaskExecutors#BLOCKING}
     * pool; the offload itself is integration-pinned ({@code KeyAuthExecuteOnTest}).
     */
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private final KeyStore keyStore;
    private final String masterKey;
    private final MetricsRecorder metricsRecorder;
    private final MasterKeyThrottle masterKeyThrottle;
    private final Executor blockingExecutor;

    @Inject
    KeyAuthFilter(
            KeyStore keyStore,
            MasterKeyProvider masterKeyProvider,
            MetricsRecorder metricsRecorder,
            @Named(TaskExecutors.BLOCKING) Executor blockingExecutor) {
        this(keyStore, masterKeyProvider.masterKey(), metricsRecorder, MasterKeyThrottle.create(), blockingExecutor);
    }

    /** Package-private: tests pin a master key. */
    KeyAuthFilter(KeyStore keyStore, @Nullable String masterKey) {
        this(keyStore, masterKey, MetricsRecorder.noop());
    }

    /** Package-private: tests may pin a recorder (filter rejections are Tier-1). */
    KeyAuthFilter(KeyStore keyStore, @Nullable String masterKey, MetricsRecorder metricsRecorder) {
        this(keyStore, masterKey, metricsRecorder, MasterKeyThrottle.create());
    }

    /**
     * Package-private: tests may pin a fixed-clock throttle (review H3 — the master-key
     * brute-force defense; see {@link MasterKeyThrottle}).
     */
    KeyAuthFilter(
            KeyStore keyStore,
            @Nullable String masterKey,
            MetricsRecorder metricsRecorder,
            MasterKeyThrottle masterKeyThrottle) {
        this(keyStore, masterKey, metricsRecorder, masterKeyThrottle, DIRECT_EXECUTOR);
    }

    /**
     * Package-private: tests may pin the executor the deferred virtual-key auth runs
     * on (the production bean is {@link TaskExecutors#BLOCKING}; see the class javadoc).
     */
    KeyAuthFilter(
            KeyStore keyStore,
            @Nullable String masterKey,
            MetricsRecorder metricsRecorder,
            MasterKeyThrottle masterKeyThrottle,
            Executor blockingExecutor) {
        this.keyStore = Objects.requireNonNull(keyStore, "keyStore");
        this.masterKey = masterKey;
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.masterKeyThrottle = Objects.requireNonNull(masterKeyThrottle, "masterKeyThrottle");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        // The rejection latency must be measured, never hardcoded 0 — the
        // janus_request_duration_seconds timer is the same series successful requests
        // record to, so a 0 sample would drag the p50/p95 down on auth-heavy
        // workloads. The filter is a singleton bean (one doFilter call per request),
        // so the timestamp is a local, never an instance field.
        long startNanos = System.nanoTime();
        if (masterKey == null) {
            return chain.proceed(request); // explicit auth-off (masterKey is null)
        }
        String path = request.getUri().getPath();
        if (path == null) {
            return chain.proceed(request);
        }
        // The membership check runs on the normalized path (trailing slashes stripped,
        // //-runs collapsed) so the filter's protected set and the Micronaut router's
        // path matching cannot diverge on a form like /v1/chat/completions/ or a
        // double-slash URI — a divergence would otherwise let a model route reach the
        // controllers with no auth (pinned by KeyAuthFilterTest).
        path = normalizePath(path);
        // Review L2 hardening: the admin plane is DEFAULT-DENY — every method on
        // /key* requires the master key (a future admin verb can never silently
        // bypass; an unregistered verb 401s instead of leaking the router's 404).
        // The master-key check itself is pure in-memory work (one constant-time
        // compare + the throttle window), so it runs inline; only its rejections
        // are handled by the metered wrapper.
        if (path.startsWith("/key/") || path.equals("/key")) {
            requireMasterKeyMetered(path, request, startNanos);
            return chain.proceed(request);
        }
        // Gate on path AND method for the exact-route faces — the model
        // routes are registered for POST, so a method the router 404s (e.g. GET
        // /v1/chat/completions) must not get a filter 401 (which would leak that
        // the route exists and return the wrong status for a missing method).
        // The path↔face vocabulary lives in {@link Face} —
        // one file instead of a route set + a faceOf map that could drift apart.
        // Prefix faces (Responses, any method) do not gate on POST: their
        // sub-routes (GET/DELETE /v1/responses/{id}) must not fall into the
        // auth-exempt bucket.
        Face face = Face.of(path).orElse(null);
        if (face != null && (!face.gatesOnPostOnly() || request.getMethod() == HttpMethod.POST)) {
            // The virtual-key leg is one blocking KeyStore.authenticate round-trip —
            // JDBC with the Postgres store — so it never runs here on the Netty event
            // loop: the decision is deferred to the blocking executor and the chain
            // proceeds only after it passes (see the class javadoc's blocking-store
            // discipline and DeferredAuthPublisher).
            return new DeferredAuthPublisher(
                    blockingExecutor,
                    () -> requireVirtualKeyMetered(request, face.label(), startNanos),
                    () -> chain.proceed(request));
        }
        return chain.proceed(request);
    }

    /**
     * The master-key leg of one request's auth, wrapped in the filter-level metering:
     * a typed rejection (or a store failure — the catch-all) is recorded (face ×
     * 401/403/429/5xx — always-on Tier-1, never a call row; see the class javadoc and
     * {@code MetricsAuthRejectionTest}) and rethrown unchanged. The recording is
     * best-effort — a throwing recorder must never replace the typed exception with
     * its own failure (the guard pattern; see {@link #recordRejection}).
     */
    private void requireMasterKeyMetered(String path, HttpRequest<?> request, long startNanos) {
        try {
            requireMasterKey(request);
        } catch (KeyAuthException e) {
            recordRejection(faceOf(path), statusOf(e.reason()), elapsedMillis(startNanos), e.keyId());
            throw e;
        } catch (RateLimitExceededException e) {
            // Review H3: the master-key throttle denial is also a filter-level rejection
            // — metered (admin × 429) on the same Tier-1 series, never a call row.
            recordRejection(faceOf(path), HttpStatus.TOO_MANY_REQUESTS.getCode(), elapsedMillis(startNanos), e.keyId());
            throw e;
        } catch (RuntimeException e) {
            // The master-key leg touches no store; a runtime failure here is a bug, not
            // a client error — it is still metered (admin × 5xx) so the evidence exists.
            recordRejection(faceOf(path), HttpStatus.INTERNAL_SERVER_ERROR.getCode(), elapsedMillis(startNanos), null);
            throw e;
        }
    }

    /**
     * The virtual-key leg of one request's auth, wrapped in the filter-level metering.
     * Runs <b>on the blocking executor</b> (see {@link DeferredAuthPublisher}), where
     * two outcomes are recorded before the exception is delivered as the publisher's
     * error:
     *
     * <ul>
     * <li>a typed {@link KeyAuthException} — face × 401/403, the documented
     * pre-dispatch denial accounting (never a call row);</li>
     * <li>any other {@link RuntimeException} — a <b>store failure</b> (e.g.
     * {@code PgKeyStore}'s {@code IllegalStateException} when Postgres is down). It
     * escapes both typed catches and never reaches a controller, so it is metered here
     * (face × 5xx) — otherwise a Postgres outage would 500 every model route with no
     * metric evidence at all (controllers meter their own 5xx; this is the filter's).</li>
     * </ul>
     */
    private void requireVirtualKeyMetered(HttpRequest<?> request, String faceLabel, long startNanos) {
        try {
            requireVirtualKey(request);
        } catch (KeyAuthException e) {
            recordRejection(faceLabel, statusOf(e.reason()), elapsedMillis(startNanos), e.keyId());
            throw e;
        } catch (RuntimeException e) {
            recordRejection(faceLabel, HttpStatus.INTERNAL_SERVER_ERROR.getCode(), elapsedMillis(startNanos), null);
            throw e;
        }
    }

    /**
     * Filter-level Tier-1 recording, <b>best-effort</b>: a throwing recorder must
     * never replace the client's true envelope (the typed 401/403/429 rejection or the
     * store failure's 5xx) with the recorder's own failure — the same guard the admin
     * controller's catch path and {@code KeyRevocationHooks} use. Log-and-drop at WARN
     * (message text only; never key material).
     */
    private void recordRejection(String face, int status, long durationMillis, String keyId) {
        try {
            metricsRecorder.recordRequest(face, status, durationMillis, keyId);
        } catch (RuntimeException e) {
            LOG.warn("metrics recording dropped (recorder failure): {}", e.toString());
        }
    }

    /**
     * Collapse {@code //}-runs and strip the trailing slash so the auth vocabulary
     * matches the router's (Netty normalizes some URI forms, so a strict equality
     * against a hardcoded set is a divergence risk). {@code /} itself is preserved.
     *
     * <p>Package-private: {@link GatewayExceptionHandler} normalizes the path the
     * same way for its Anthropic-route membership test, so the two vocabularies cannot
     * drift apart on the same path space.
     */
    static String normalizePath(String path) {
        String collapsed = MULTI_SLASH.matcher(path).replaceAll("/");
        return collapsed.length() > 1 && collapsed.endsWith("/")
                ? collapsed.substring(0, collapsed.length() - 1)
                : collapsed;
    }

    /**
     * The {@code face} label for a filter-level rejection ( Tier-1): the faces map
     * to their labels via {@link Face}; admin (and any future authed) routes carry the
     * {@code "admin"} value — never a fabricated model face for a request that never
     * touched one.
     */
    private static String faceOf(String path) {
        return Face.of(path).map(Face::label).orElse("admin");
    }

    private static int statusOf(KeyAuthException.Reason reason) {
        return switch (reason) {
            case MISSING, INVALID, EXPIRED, BAD_MASTER -> HttpStatus.UNAUTHORIZED.getCode();
            case REVOKED, SCOPE_DENIED -> HttpStatus.FORBIDDEN.getCode();
        };
    }

    /** Wall-clock millis since {@code startNanos} (the filter-level latency measurement). */
    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private void requireMasterKey(HttpRequest<?> request) {
        // Review H3: the master-key check is a single constant-time compare with no
        // other cost, so candidate keys could otherwise be tried at line rate against
        // /key/*. Once the throttle's failure window trips, further attempts are denied
        // 429 + Retry-After (the gateway-originated rate-limit envelope) until the
        // window rolls — see MasterKeyThrottle. The one exemption: while the window is
        // tripped the VALID master key still authenticates (one constant-time
        // compare), so failures sprayed by an attacker cannot lock the operator out
        // of the admin plane.
        String token = extractToken(request);
        if (masterKeyThrottle.blocked() && (token == null || !constantTimeEquals(token, masterKey))) {
            throw new RateLimitExceededException(
                    RateLimitExceededException.Reason.RATE_LIMIT_EXCEEDED, masterKeyThrottle.retryAfterSeconds(), null);
        }
        if (token == null) {
            throw new KeyAuthException(KeyAuthException.Reason.MISSING, null);
        }
        if (!constantTimeEquals(token, masterKey)) {
            masterKeyThrottle.recordFailure();
            throw new KeyAuthException(KeyAuthException.Reason.BAD_MASTER, null);
        }
        masterKeyThrottle.recordSuccess();
    }

    private void requireVirtualKey(HttpRequest<?> request) {
        String token = extractToken(request);
        if (token == null) {
            throw new KeyAuthException(KeyAuthException.Reason.MISSING, null);
        }
        Optional<KeyGenerator.Parsed> parsed = KeyGenerator.parse(token);
        if (parsed.isEmpty()) {
            throw new KeyAuthException(KeyAuthException.Reason.INVALID, null);
        }
        // The store authenticates atomically: verify → status → expiry → lastUsedAt
        // run inside a single map transition, so a racing revoke either lands before
        // (⇒ REVOKED) or after (⇒ the check already passed — no torn state).
        KeyStore.AuthResult auth =
                keyStore.authenticate(parsed.get().prefix(), parsed.get().secret());
        switch (auth.outcome()) {
            case INVALID ->
                throw new KeyAuthException(
                        KeyAuthException.Reason.INVALID,
                        auth.record() == null ? null : auth.record().id());
            case REVOKED ->
                throw new KeyAuthException(
                        KeyAuthException.Reason.REVOKED, auth.record().id());
            case EXPIRED ->
                throw new KeyAuthException(
                        KeyAuthException.Reason.EXPIRED, auth.record().id());
            case OK -> request.setAttribute(KEY_ATTRIBUTE, auth.record());
        }
    }

    /**
     * Controller-side scope enforcement : when the filter attached a key,
     * the requested model must pass its {@code AccessPolicy}. The check is against
     * {@code ChatRequest.model} — the client alias — per the documented scope
     * semantics ({@link io.amscotti.janus.store.AccessPolicy}); no key attached (auth
     * off) ⇒ no check, so keyless behavior is byte-identical.
     */
    static void checkScope(HttpRequest<?> request, String model) {
        request.getAttribute(KEY_ATTRIBUTE, KeyRecord.class).ifPresent(record -> {
            if (!record.accessPolicy().authorize(model)) {
                throw new KeyAuthException(KeyAuthException.Reason.SCOPE_DENIED, record.id());
            }
        });
    }

    /**
     * Header-only token extraction (never the body): {@code Authorization: Bearer
     * <token>} wins when present, else {@code x-api-key: <token>} (the reference
     * {@code Auth.authenticate/2} precedence). A malformed or non-Bearer
     * {@code Authorization} is treated as missing (401 — the same envelope as
     * the reference implementation's {@code :invalid} for extract failures).
     */
    private static String extractToken(HttpRequest<?> request) {
        String authorization = request.getHeaders().get(AUTHORIZATION_HEADER);
        if (authorization != null && !authorization.isBlank()) {
            String trimmed = authorization.trim();
            if (trimmed.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                String token = trimmed.substring(BEARER_PREFIX.length()).trim();
                if (!token.isEmpty()) {
                    return token;
                }
            }
            return null;
        }
        String apiKey = request.getHeaders().get(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        return null;
    }

    /**
     * Timing-safe compare for the master key: byte-length equality first, then
     * {@link MessageDigest#isEqual} on the raw bytes. The length pre-check is a
     * deliberate trade against the previous zero-padding form: {@code Arrays.copyOf}
     * pads the shorter side with {@code \0}, so a token of {@code <master>\0…\0}
     * compared EQUAL to the master key (verified empirically — an auth-equality
     * function must not carry that property, even when the HTTP transport happens to
     * reject NUL header bytes today; defense-in-depth). The residual leak — whether
     * the lengths match — is negligible for a high-entropy secret behind the
     * {@link MasterKeyThrottle}. The key itself never appears in logs or messages.
     * Package-private so the equality semantics are unit-pinned.
     */
    static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        if (aBytes.length != bBytes.length) {
            return false;
        }
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    /**
     * Runs one blocking auth decision on the injected executor and only then proceeds
     * the filter chain — the seam that keeps JDBC off the Netty event loop (see the
     * class javadoc's blocking-store discipline). Hand-rolled rather than a reactive
     * library because the gateway deliberately carries none: the bridge is a
     * single-shot gate, not a general operator, and Reactive Streams compliance for it
     * is ~60 lines ({@link Bridge}).
     *
     * <p>Semantics: on subscribe the downstream receives its {@link Subscription}
     * immediately and the auth gate is submitted to the executor. A
     * passing gate subscribes {@code chain.proceed(request)} and every signal of the
     * real chain is forwarded verbatim (the bridge is the chain's subscriber). A
     * failing gate delivers the typed exception as the publisher's error — the global
     * {@link GatewayExceptionHandler} renders it exactly as a synchronous throw, so
     * wire behavior is unchanged (pinned by {@code KeyAuthFilterTest}). A client cancel
     * before the gate ran simply never proceeds the chain (no dispatch on a dead
     * request); after it ran, the cancel forwards to the real chain subscription.
     */
    private static final class DeferredAuthPublisher implements Publisher<MutableHttpResponse<?>> {

        private final Executor executor;
        private final Runnable authGate;
        private final Supplier<Publisher<MutableHttpResponse<?>>> downstream;

        DeferredAuthPublisher(
                Executor executor, Runnable authGate, Supplier<Publisher<MutableHttpResponse<?>>> downstream) {
            this.executor = Objects.requireNonNull(executor, "executor");
            this.authGate = Objects.requireNonNull(authGate, "authGate");
            this.downstream = Objects.requireNonNull(downstream, "downstream");
        }

        @Override
        public void subscribe(Subscriber<? super MutableHttpResponse<?>> subscriber) {
            Objects.requireNonNull(subscriber, "subscriber");
            Bridge bridge = new Bridge(subscriber, authGate, downstream);
            subscriber.onSubscribe(bridge);
            try {
                executor.execute(bridge);
            } catch (RuntimeException rejected) {
                // A saturated/shut-down pool (RejectedExecutionException): the gate never
                // ran, so there is nothing to meter — surface the failure itself.
                subscriber.onError(rejected);
            }
        }

        /**
         * The one-shot bridge: {@link Subscription} toward the filter-chain consumer
         * (buffers pre-gate demand, forwards cancel), {@link Subscriber} toward the
         * real {@code chain.proceed} publisher (forwards every signal verbatim — the
         * downstream already got its onSubscribe), and the executor task (runs the
         * gate, then wires the chain in). All cross-thread state is monitor-guarded.
         */
        private static final class Bridge implements Subscription, Subscriber<MutableHttpResponse<?>>, Runnable {

            private final Subscriber<? super MutableHttpResponse<?>> downstream;
            private final Runnable authGate;
            private final Supplier<Publisher<MutableHttpResponse<?>>> downstreamPublisher;

            /** The real chain subscription once the gate passed (guarded by {@code this}). */
            private Subscription upstream;

            /** Demand that arrived before the real subscription existed (buffered). */
            private long pendingDemand;

            /** Set by a cancel racing the gate (guarded by {@code this}). */
            private boolean cancelled;

            Bridge(
                    Subscriber<? super MutableHttpResponse<?>> downstream,
                    Runnable authGate,
                    Supplier<Publisher<MutableHttpResponse<?>>> downstreamPublisher) {
                this.downstream = downstream;
                this.authGate = authGate;
                this.downstreamPublisher = downstreamPublisher;
            }

            // -------------------------------------------------- Subscription

            @Override
            public synchronized void request(long n) {
                if (upstream != null) {
                    // A non-positive n is a downstream spec violation — let the real
                    // upstream surface it the standard way.
                    upstream.request(n);
                    return;
                }
                if (n <= 0) {
                    return; // nothing real to hand it to yet; late handling can only come later
                }
                pendingDemand = pendingDemand + n < 0 ? Long.MAX_VALUE : pendingDemand + n;
            }

            @Override
            public synchronized void cancel() {
                cancelled = true;
                if (upstream != null) {
                    upstream.cancel();
                }
            }

            // -------------------------------------------------- executor task

            @Override
            public void run() {
                if (isCancelled()) {
                    return; // client went away while the task was queued
                }
                try {
                    authGate.run();
                } catch (Throwable t) {
                    downstream.onError(t);
                    return;
                }
                if (isCancelled()) {
                    return; // never dispatch a cancelled request
                }
                try {
                    downstreamPublisher.get().subscribe(this);
                } catch (RuntimeException e) {
                    downstream.onError(e);
                }
            }

            // -------------------------------------- Subscriber (the real chain)

            @Override
            public synchronized void onSubscribe(Subscription subscription) {
                if (cancelled) {
                    subscription.cancel();
                    return;
                }
                this.upstream = subscription;
                long demand = pendingDemand;
                pendingDemand = 0;
                if (demand > 0) {
                    subscription.request(demand);
                }
            }

            @Override
            public void onNext(MutableHttpResponse<?> response) {
                downstream.onNext(response);
            }

            @Override
            public void onError(Throwable t) {
                downstream.onError(t);
            }

            @Override
            public void onComplete() {
                downstream.onComplete();
            }

            private synchronized boolean isCancelled() {
                return cancelled;
            }
        }
    }
}
