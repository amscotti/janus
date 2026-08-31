package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.store.InMemoryKeyStore;
import io.amscotti.janus.store.KeyGenerator;
import io.amscotti.janus.store.KeyRecord;
import io.amscotti.janus.store.KeyRecordView;
import io.amscotti.janus.store.KeyStore;
import io.micronaut.context.annotation.Property;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * {@link KeyAuthFilter}: path-aware request auth (/ embedded-server
 * pattern, auth <b>on</b> via the test-only {@code janus.test.master-key} property —
 * see {@link TestKeyAuthFactory}):
 *
 * <ul>
 * <li>{@code /v1/chat/completions} + {@code /v1/messages} → virtual-key auth
 * (missing/invalid/expired → 401, revoked → 403, valid → proceeds with the
 * {@link KeyRecord} attached to the request);
 * <li>{@code /key/*} → master-key auth (a virtual key is rejected, the master key —
 * Bearer or {@code x-api-key} — accepted);
 * <li>{@code /health} (and anything else unlisted) → exempt.
 * </ul>
 *
 * <p>Auth-off (no master key) is the default proved by the existing / suites
 * passing unchanged; {@link #authOffFilterIsAPassthrough} pins it at filter level too.
 * The embedded assertions use the shared {@link TestRouterFactory#BACKEND} (canned
 * responses) and the fixed-clock {@link KeyStore} from {@link TestKeyAuthFactory}.
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
class KeyAuthFilterTest {

    private static final String MASTER_KEY = "test-master-key-000";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    KeyStore keyStore;

    // ------------------------------------------------------------ virtual-key auth

    @Test
    void validVirtualKeyPassesAndIsAttachedToRequest() {
        String key = createKey("alice", List.of("deepseek-v4-flash"));
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", key),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus(), "a scoped valid key must pass and reach the router");
        assertTrue(TestRouterFactory.BACKEND.completeCalls.size() >= 1, "the controller must run (key attached)");
    }

    @Test
    void validVirtualKeyWorksViaAuthorizationBearer() {
        String key = createKey("bob", List.of("deepseek-v4-flash"));
        TestRouterFactory.BACKEND.completeReturns(chatResponse());

        HttpResponse<String> http = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + key),
                        String.class);
        assertEquals(HttpStatus.OK, http.getStatus());
    }

    @Test
    void missingHeaderIs401AuthenticationErrorOnBothFaces() {
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        HttpResponse<String> openAi =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.UNAUTHORIZED, openAi.getStatus());
        assertTrue(openAi.body().contains("\"type\":\"authentication_error\""), openAi.body());

        HttpResponse<String> anthropic = errorResponse(
                HttpRequest.POST("/v1/messages", anthropicBody()).contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.UNAUTHORIZED, anthropic.getStatus());
        assertTrue(anthropic.body().contains("\"type\":\"authentication_error\""), anthropic.body());
    }

    @Test
    void unknownKeyIs401AuthenticationError() {
        KeyGenerator.Generated unknown = KeyGenerator.generate(); // well-formed but never stored
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-api-key", unknown.fullKey()));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"authentication_error\""), http.body());
        assertTrue(http.body().contains("invalid or unknown credentials"), http.body());
    }

    @Test
    void revokedKeyIs403PermissionError() {
        KeyStore.CreatedKey created = create("carol", List.of("deepseek-v4-flash"));
        assertTrue(keyStore.revoke(created.record().id()));
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-api-key", created.fullKey()));
        assertEquals(HttpStatus.FORBIDDEN, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"permission_error\""), http.body());
        assertTrue(http.body().contains("gateway key has been revoked"), http.body());
    }

    @Test
    void expiredKeyIs401AuthenticationError() {
        // expiresAt before the fixed store clock → expired relative to the filter's now.
        KeyStore.CreatedKey created = keyStore.create(new KeyStore.KeyCreateRequest(
                "dave",
                List.of("deepseek-v4-flash"),
                TestKeyAuthFactory.CLOCK.millis() - 1_000,
                null,
                null,
                null,
                null));
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-api-key", created.fullKey()));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"authentication_error\""), http.body());
        assertTrue(http.body().contains("gateway key has expired"), http.body());
    }

    @Test
    void malformedKeyStringIs401() {
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/v1/chat/completions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-api-key", "not-a-janus-key"));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"authentication_error\""), http.body());
    }

    // ------------------------------- path-normalization auth (regression)

    /**
     * Regression — the filter's protected set and the router's matching must not
     * diverge on a non-canonical path form. A trailing-slash model route is <b>never</b>
     * 200 without a key (401 from the filter after normalization, or 404 from the
     * router — a bypass would be a 200), and a valid key on the same form is never
     * spuriously rejected as unauthorized.
     */
    @Test
    void trailingSlashModelRouteIsNeverAuthorizedWithoutAKey() {
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        HttpResponse<String> withoutKey =
                errorResponse(HttpRequest.POST("/v1/chat/completions/", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON));
        assertNotEquals(HttpStatus.OK, withoutKey.getStatus(), "a trailing-slash model route must not bypass auth");

        String key = createKey("slash", List.of("deepseek-v4-flash"));
        HttpResponse<String> withKey = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/v1/chat/completions/", requestBody("deepseek-v4-flash"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", key),
                        String.class);
        assertNotEquals(
                HttpStatus.UNAUTHORIZED,
                withKey.getStatus(),
                "a valid key must never be rejected as unauthorized on a trailing-slash route");
    }

    @Test
    void doubleSlashModelRouteIsNeverAuthorizedWithoutAKey() {
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        HttpResponse<String> withoutKey =
                errorResponse(HttpRequest.POST("//v1/chat/completions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON));
        assertNotEquals(HttpStatus.OK, withoutKey.getStatus(), "a double-slash model route must not bypass auth");

        String key = createKey("dslash", List.of("deepseek-v4-flash"));
        HttpResponse<String> withKey = client.toBlocking()
                .exchange(
                        HttpRequest.POST("//v1/chat/completions", requestBody("deepseek-v4-flash"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", key),
                        String.class);
        assertNotEquals(
                HttpStatus.UNAUTHORIZED,
                withKey.getStatus(),
                "a valid key must never be rejected as unauthorized on a double-slash route");
    }

    @Test
    void caseVariantModelRouteIsNeverAuthorizedWithoutAKey() {
        // The router is case-sensitive: /V1/chat/completions has no route (404) and the
        // filter must not treat it as a protected model route either — never a 200.
        HttpResponse<String> http =
                errorResponse(HttpRequest.POST("/V1/chat/completions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON));
        assertNotEquals(HttpStatus.OK, http.getStatus(), "a case-variant model route must not bypass auth");
    }

    @Test
    void percentEncodedPathFormsNeverAuthorizeWithoutAKey() {
        // Percent-encoded path forms (%2F, %2e) are seen RAW by both the
        // filter and the router (Micronaut's AbstractNettyHttpRequest.getPath returns
        // the still-encoded path), so /key%2Fgenerate and /v1/chat%2Fcompletions are
        // unmatched by either consumer → 404, never a 200 bypass. Pinned so a future
        // Netty/Micronaut decode change (decoding in one consumer but not the other)
        // cannot silently open an unguarded auth-bypass seam on the model/admin routes.
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        for (String path : List.of(
                "/key%2Fgenerate",
                "/key%2fgenerate", "/v1/chat%2Fcompletions", "/v1/chat/completions%2F", "/v1/messages%2E")) {
            HttpResponse<String> withoutKey =
                    errorResponse(HttpRequest.POST(path, "{}").contentType(MediaType.APPLICATION_JSON));
            assertNotEquals(
                    HttpStatus.OK,
                    withoutKey.getStatus(),
                    "percent-encoded " + path + " must never be 200 without a key");
        }

        String key = createKey("pct", List.of("deepseek-v4-flash"));
        HttpResponse<String> withKey =
                errorResponse(HttpRequest.POST("/v1/chat%2Fcompletions", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("x-api-key", key));
        assertNotEquals(
                HttpStatus.UNAUTHORIZED,
                withKey.getStatus(),
                "a valid key must never be rejected as unauthorized on a percent-encoded route");
    }

    @Test
    void responsesPrefixRequiresAVirtualKeyOnEveryMethod() {
        // The /v1/responses prefix is virtual-key-authed on ALL
        // methods — POST /v1/responses and the GET/DELETE stub routes must not fall
        // into the auth-exempt bucket (real OpenAI 401s them), and filter-level
        // rejections meter face="responses", never "admin".
        HttpResponse<String> post =
                errorResponse(HttpRequest.POST("/v1/responses", "{}").contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.UNAUTHORIZED, post.getStatus());
        assertTrue(post.body().contains("\"type\":\"authentication_error\""), post.body());

        HttpResponse<String> get = errorResponse(HttpRequest.GET("/v1/responses/resp_1"));
        assertEquals(HttpStatus.UNAUTHORIZED, get.getStatus(), "the stub routes are authed too");

        // A valid key passes the prefix gate and reaches the route (the stub 404s).
        String key = createKey("resp-user", List.of("deepseek-v4-flash"));
        HttpResponse<String> authed =
                errorResponse(HttpRequest.GET("/v1/responses/resp_1").header("x-api-key", key));
        assertEquals(HttpStatus.NOT_FOUND, authed.getStatus(), "past auth, the stub envelope-404s");
        assertTrue(authed.body().contains("response_not_found"), authed.body());

        // Unrelated sub-paths stay exempt (prefix boundary: /v1/responsesfoo is not the face).
        // Unrelated sub-paths stay exempt (prefix boundary: /v1/responsesfoo is not the
        // face) — the router's 404 surfaces, never the filter's 401.
        HttpResponse<String> adjacent =
                errorResponse(HttpRequest.POST("/v1/responsesfoo", requestBody("deepseek-v4-flash"))
                        .contentType(MediaType.APPLICATION_JSON));
        assertNotEquals(HttpStatus.UNAUTHORIZED, adjacent.getStatus(), "prefix boundary respected");
    }

    @Test
    void adminRoutesDefaultDenyEveryMethod() {
        // Review L2: the admin plane gates ALL methods on /key*, not just the verbs
        // registered today — a future admin route added with a new verb (PUT
        // /key/rotate, …) must never silently bypass auth, and an unregistered verb
        // must not leak the router's 404 to an unauthenticated caller.
        HttpResponse<String> unregisteredVerb =
                errorResponse(HttpRequest.DELETE("/key/generate", "{}").contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.UNAUTHORIZED, unregisteredVerb.getStatus(), "default-deny on /key*");
    }

    @Test
    void dotSegmentPathFormsNeverAuthorizeWithoutAKey() {
        // Dot-segment forms are seen RAW by both the filter and the router
        // (Micronaut's getUri.getPath returns the still-raw path — the same
        // percent-encoding pin), so the model-route forms are unmatched by the router →
        // 404, never a 200 bypass. The /key/.. form is admin-classified (its normalized
        // path still starts with /key/), so a virtual key is (correctly) rejected 401 and
        // the master key reaches the router's 404. Pinned so a future decode/normalization
        // change in exactly one consumer cannot silently open the auth-bypass seam.
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        for (String path : List.of(
                "/v1/chat/completions/..",
                "/v1/chat/completions/./",
                "/key/../v1/chat/completions",
                "/v1/../key/generate",
                "/foo/../key/generate")) {
            HttpResponse<String> withoutKey =
                    errorResponse(HttpRequest.POST(path, "{}").contentType(MediaType.APPLICATION_JSON));
            assertNotEquals(
                    HttpStatus.OK, withoutKey.getStatus(), "dot-segment " + path + " must never be 200 without a key");
        }
        String key = createKey("dots", List.of("deepseek-v4-flash"));
        // Model-route dot forms are not in MODEL_ROUTES raw ⇒ exempt ⇒ a valid virtual
        // key is never rejected as unauthorized (the router 404s).
        for (String path : List.of("/v1/chat/completions/..", "/v1/chat/completions/./")) {
            HttpResponse<String> withKey = errorResponse(HttpRequest.POST(path, requestBody("deepseek-v4-flash"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("x-api-key", key));
            assertNotEquals(
                    HttpStatus.UNAUTHORIZED,
                    withKey.getStatus(),
                    "a valid key must never be spuriously rejected on " + path);
        }
        // The /key/.. form is admin-classified: a virtual key is rejected (it is not a
        // master key), the master key passes the filter and the router 404s.
        HttpResponse<String> withVirtual = errorResponse(HttpRequest.POST("/key/../v1/chat/completions", "{}")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", key));
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                withVirtual.getStatus(),
                "a /key/.. form is admin-classified: a virtual key is not a master key");
        HttpResponse<String> withMaster = errorResponse(HttpRequest.POST("/key/../v1/chat/completions", "{}")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", MASTER_KEY));
        assertNotEquals(
                HttpStatus.OK,
                withMaster.getStatus(),
                "the master key passes the filter and the router 404s (never 200)");
        assertNotEquals(
                HttpStatus.UNAUTHORIZED,
                withMaster.getStatus(),
                "a valid master key must never be spuriously rejected on a /key/.. form");
    }

    @Test
    void nonPostOnModelRouteIsRouter404NotFilter401Pinned() {
        // The filter gates on path AND method, so GET /v1/chat/completions
        // has no route and must be the router's 404 — not a filter 401 (which would leak
        // that the route exists and return the wrong status for a method that does not
        // exist). No bypass: the router has no such route either.
        TestRouterFactory.BACKEND.completeReturns(chatResponse());
        HttpResponse<String> get =
                errorResponse(HttpRequest.GET("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON));
        assertNotEquals(HttpStatus.OK, get.getStatus(), "a non-POST model route must not dispatch");
        assertNotEquals(HttpStatus.UNAUTHORIZED, get.getStatus(), "a missing method is the router's status, not a 401");

        HttpResponse<String> getWithKey = errorResponse(HttpRequest.GET("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("x-api-key", createKey("getter", List.of("deepseek-v4-flash"))));
        assertNotEquals(
                HttpStatus.OK, getWithKey.getStatus(), "a valid key on a non-POST model route must still not dispatch");
        assertNotEquals(
                HttpStatus.UNAUTHORIZED,
                getWithKey.getStatus(),
                "a valid key must never be rejected as unauthorized on a non-POST model route");
    }

    // --------------------------------------------------------------- admin routes

    @Test
    void adminRoutesRejectVirtualKeyAndAcceptMasterKey() {
        String virtualKey = createKey("eve", List.of("deepseek-v4-flash"));
        HttpResponse<String> rejected = errorResponse(HttpRequest.POST("/key/generate", "{}")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + virtualKey));
        assertEquals(HttpStatus.UNAUTHORIZED, rejected.getStatus(), "a virtual key is not a master key");

        HttpResponse<String> acceptedBearer = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[\"deepseek-v4-flash\"]}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, acceptedBearer.getStatus(), "the master key via Bearer must reach the admin API");

        HttpResponse<String> acceptedApiKey = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[\"deepseek-v4-flash\"]}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("x-api-key", MASTER_KEY),
                        String.class);
        assertEquals(
                HttpStatus.OK, acceptedApiKey.getStatus(), "the master key via x-api-key must reach the admin API");
    }

    @Test
    void adminRoutesRejectWrongMasterKey() {
        HttpResponse<String> http = errorResponse(HttpRequest.POST("/key/generate", "{}")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer not-the-master-key"));
        assertEquals(HttpStatus.UNAUTHORIZED, http.getStatus());
        assertTrue(http.body().contains("\"type\":\"authentication_error\""), http.body());
        assertTrue(http.body().contains("invalid master key"), http.body());
    }

    // ------------------------------------------------------------------ exempt

    @Test
    void healthEndpointIsExemptFromAuth() {
        HttpResponse<String> http = client.toBlocking().exchange(HttpRequest.GET("/health"), String.class);
        assertEquals(HttpStatus.OK, http.getStatus(), "/health must stay exempt");
    }

    @Test
    void modelsEndpointIsExemptFromAuthPinned() {
        // /v1/models is public metadata (LiteLLM-aligned) — with
        // auth ON it must stay reachable without a key. Pinned so a future "stricter
        // default for unlisted paths" flips it deliberately (the revisit).
        HttpResponse<String> http = client.toBlocking().exchange(HttpRequest.GET("/v1/models"), String.class);
        assertEquals(HttpStatus.OK, http.getStatus(), "/v1/models stays unauthenticated with auth ON");
    }

    // --------------------------------------------------- filter-level unit tests

    @Test
    void masterKeyThrottleDeniesWith429AfterRepeatedFailures() {
        // Review H3: the master-key check alone costs one constant-time compare, so
        // candidate keys could be tried at line rate against /key/*. After the
        // throttle's window trips, further attempts (even with a NEW wrong key) are
        // denied 429 carrying Retry-After — the gateway-originated rate-limit envelope.
        InMemoryKeyStore store = new InMemoryKeyStore(TestKeyAuthFactory.CLOCK);
        MasterKeyThrottle throttle =
                new MasterKeyThrottle(3, java.time.Duration.ofSeconds(60), TestKeyAuthFactory.CLOCK);
        KeyAuthFilter filter = new KeyAuthFilter(store, MASTER_KEY, MetricsRecorder.noop(), throttle);
        ServerFilterChain chain = request -> Publishers.just(HttpResponse.ok());

        for (int i = 0; i < 3; i++) {
            final int attempt = i;
            KeyAuthException bad = assertThrows(
                    KeyAuthException.class,
                    () -> filter.doFilter(
                            HttpRequest.POST("/key/generate", "").header("x-api-key", "wrong-" + attempt), chain));
            assertEquals(KeyAuthException.Reason.BAD_MASTER, bad.reason());
        }
        RateLimitExceededException throttled = assertThrows(
                RateLimitExceededException.class,
                () -> filter.doFilter(
                        HttpRequest.POST("/key/generate", "").header("x-api-key", "another-wrong"), chain));
        assertEquals(RateLimitExceededException.Reason.RATE_LIMIT_EXCEEDED, throttled.reason());
        assertTrue(throttled.retryAfterSeconds() > 0, "the 429 must carry a positive Retry-After");
    }

    @Test
    void successfulMasterKeyAuthResetsTheThrottle() {
        InMemoryKeyStore store = new InMemoryKeyStore(TestKeyAuthFactory.CLOCK);
        MasterKeyThrottle throttle =
                new MasterKeyThrottle(2, java.time.Duration.ofSeconds(60), TestKeyAuthFactory.CLOCK);
        KeyAuthFilter filter = new KeyAuthFilter(store, MASTER_KEY, MetricsRecorder.noop(), throttle);
        ServerFilterChain chain = request -> Publishers.just(HttpResponse.ok());

        assertThrows(
                KeyAuthException.class,
                () -> filter.doFilter(HttpRequest.POST("/key/generate", "").header("x-api-key", "typo"), chain));
        filter.doFilter(HttpRequest.POST("/key/generate", "").header("x-api-key", MASTER_KEY), chain);
        assertThrows(
                KeyAuthException.class,
                () -> filter.doFilter(HttpRequest.POST("/key/generate", "").header("x-api-key", "typo-again"), chain));
        // Success reset the window: one failure of two — the next wrong key is still a
        // plain 401 BAD_MASTER, not a 429.
        KeyAuthException second = assertThrows(
                KeyAuthException.class,
                () -> filter.doFilter(HttpRequest.POST("/key/generate", "").header("x-api-key", "typo-3"), chain));
        assertEquals(KeyAuthException.Reason.BAD_MASTER, second.reason());
    }

    @Test
    void authOffFilterIsAPassthrough() {
        InMemoryKeyStore store = new InMemoryKeyStore(TestKeyAuthFactory.CLOCK);
        KeyAuthFilter filter = new KeyAuthFilter(store, (String) null);
        AtomicBoolean proceeded = new AtomicBoolean();
        ServerFilterChain chain = request -> {
            proceeded.set(true);
            return Publishers.just(HttpResponse.ok());
        };
        Publisher<MutableHttpResponse<?>> publisher =
                filter.doFilter(HttpRequest.POST("/v1/chat/completions", ""), chain);
        assertTrue(proceeded.get(), "auth off ⇒ the filter must pass every request through untouched");
        assertTrue(publisher != null);
    }

    @Test
    void validVirtualKeyIsAttachedToTheRequestAtFilterLevel() {
        InMemoryKeyStore store = new InMemoryKeyStore(TestKeyAuthFactory.CLOCK);
        KeyStore.CreatedKey created = store.create(
                new KeyStore.KeyCreateRequest("frank", List.of("deepseek-v4-flash"), null, null, null, null, null));
        KeyAuthFilter filter = new KeyAuthFilter(store, MASTER_KEY);
        AtomicReference<HttpRequest<?>> seen = new AtomicReference<>();
        ServerFilterChain chain = request -> {
            seen.set(request);
            return Publishers.just(HttpResponse.ok());
        };
        // The virtual-key decision is deferred to the (test: direct) executor, so the
        // chain runs at subscription time — a valid key must proceed and attach the
        // record on that path.
        LatchedSubscriber subscriber = new LatchedSubscriber();
        filter.doFilter(HttpRequest.POST("/v1/chat/completions", "").header("x-api-key", created.fullKey()), chain)
                .subscribe(subscriber);
        subscriber.awaitTerminal();
        assertNull(subscriber.error, "a valid key must proceed the chain, not error");
        assertEquals(1, subscriber.responses, "the deferred publisher must forward the chain's response");
        KeyRecord attached = seen.get()
                .getAttribute(KeyAuthFilter.KEY_ATTRIBUTE, KeyRecord.class)
                .orElseThrow();
        assertEquals(created.record().id(), attached.id(), "the authenticated key record must ride the request");
        assertFalse(
                attached.toString().contains(created.record().prefix() + "-"), "no full-key material in the record");
    }

    @Test
    void virtualKeyAuthRunsOnTheProvidedExecutorNotTheSubscribingThread() {
        // The virtual-key leg is a blocking store round-trip in production, so the
        // filter defers it to an executor — the decision (and only then the chain)
        // must run on THAT executor's thread, never on the thread that subscribed
        // (the Netty event loop in production; KeyAuthExecuteOnTest pins the real
        // wiring through the embedded server).
        InMemoryKeyStore store = new InMemoryKeyStore(TestKeyAuthFactory.CLOCK);
        KeyStore.CreatedKey created = store.create(
                new KeyStore.KeyCreateRequest("offloop", List.of("deepseek-v4-flash"), null, null, null, null, null));
        AtomicReference<String> authenticateThread = new AtomicReference<>();
        ThreadRecordingKeyStore recording = new ThreadRecordingKeyStore(store, authenticateThread);
        AtomicBoolean chainRan = new AtomicBoolean();
        ServerFilterChain chain = request -> {
            chainRan.set(true);
            return Publishers.just(HttpResponse.ok());
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            KeyAuthFilter filter =
                    new KeyAuthFilter(recording, MASTER_KEY, MetricsRecorder.noop(), MasterKeyThrottle.create(), pool);
            LatchedSubscriber subscriber = new LatchedSubscriber();
            filter.doFilter(HttpRequest.POST("/v1/chat/completions", "").header("x-api-key", created.fullKey()), chain)
                    .subscribe(subscriber);
            subscriber.awaitTerminal();
            assertNull(subscriber.error, "a valid key must proceed, not error");
            assertEquals(1, subscriber.responses, "the chain's response must arrive through the deferred publisher");
            assertNotNull(authenticateThread.get(), "the store authenticate must have run");
            assertNotEquals(
                    Thread.currentThread().getName(),
                    authenticateThread.get(),
                    "virtual-key auth must run on the provided executor, not the subscribing thread");
            assertTrue(chainRan.get(), "the chain proceeds only after the offloaded decision passes");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void storeFailureIsMeteredAsFace5xxAndPropagatesUnchanged() {
        // A store failure on the auth path (e.g. PgKeyStore's IllegalStateException
        // when Postgres is down) escapes both typed catches and never reaches a
        // controller — the filter must still leave metric evidence (face × 5xx) and
        // propagate the exception unchanged (the handler's 500 envelope).
        InMemoryKeyStore store = new InMemoryKeyStore(TestKeyAuthFactory.CLOCK);
        FailingAuthenticateKeyStore failing = new FailingAuthenticateKeyStore(store);
        RecordingMetricsRecorder recorder = new RecordingMetricsRecorder();
        KeyAuthFilter filter = new KeyAuthFilter(failing, MASTER_KEY, recorder);
        ServerFilterChain chain = request -> Publishers.just(HttpResponse.ok());
        KeyGenerator.Generated unknown = KeyGenerator.generate(); // well-formed, reaches authenticate

        LatchedSubscriber subscriber = new LatchedSubscriber();
        filter.doFilter(HttpRequest.POST("/v1/chat/completions", "").header("x-api-key", unknown.fullKey()), chain)
                .subscribe(subscriber);
        subscriber.awaitTerminal();

        assertTrue(subscriber.error instanceof IllegalStateException, "the store failure propagates unchanged");
        assertEquals("openai", recorder.lastFace, "the failure is metered on the request's face");
        assertEquals(
                HttpStatus.INTERNAL_SERVER_ERROR.getCode(),
                recorder.lastStatus,
                "a filter-level store failure must be recorded in the 5xx bucket");
        assertTrue(recorder.lastDurationMillis >= 0, "the failure path still records its latency");
    }

    @Test
    void throwingRecorderCannotMaskTheTypedFilterRejection() {
        // The filter-level recordRequest is best-effort (the writeCallRecord /
        // forget-hook guard pattern): a throwing recorder must not replace the typed
        // KeyAuthException with its own failure — the client must still see the
        // documented 401 envelope, not a 500 caused by metrics.
        InMemoryKeyStore store = new InMemoryKeyStore(TestKeyAuthFactory.CLOCK);
        RecordingMetricsRecorder recorder = new RecordingMetricsRecorder();
        recorder.throwOnRecord = true;
        KeyAuthFilter filter = new KeyAuthFilter(store, MASTER_KEY, recorder);
        ServerFilterChain chain = request -> Publishers.just(HttpResponse.ok());
        KeyGenerator.Generated unknown = KeyGenerator.generate();

        LatchedSubscriber subscriber = new LatchedSubscriber();
        filter.doFilter(HttpRequest.POST("/v1/chat/completions", "").header("x-api-key", unknown.fullKey()), chain)
                .subscribe(subscriber);
        subscriber.awaitTerminal();

        assertTrue(
                subscriber.error instanceof KeyAuthException keyAuth
                        && keyAuth.reason() == KeyAuthException.Reason.INVALID,
                "the typed rejection survives a throwing recorder (got: " + subscriber.error + ")");
    }

    @Test
    void constantTimeEqualsComparesEqualUnequalAndDifferentLengthInputs() {
        // The master-key compare is a pure function (
        // MessageDigest.isEqual) with no unit coverage. Correctness pins: equal, unequal,
        // and differing-length inputs — the padding must not make "abc" equal to "abcd".
        assertTrue(KeyAuthFilter.constantTimeEquals("same", "same"));
        assertFalse(KeyAuthFilter.constantTimeEquals("same", "same!"));
        assertFalse(KeyAuthFilter.constantTimeEquals("same", "sam"), "a prefix is not equal");
        assertFalse(KeyAuthFilter.constantTimeEquals("", "x"));
        assertTrue(KeyAuthFilter.constantTimeEquals("", ""));
        assertFalse(KeyAuthFilter.constantTimeEquals("k".repeat(20), "k".repeat(19)), "length differs");
        assertTrue(KeyAuthFilter.constantTimeEquals("k".repeat(20), "k".repeat(20)));
        // The old zero-padding made "<master>\0" compare
        // EQUAL to the master key (Arrays.copyOf pads the shorter side with NUL) — an
        // auth-equality function must never carry that property, transport filters
        // notwithstanding. The length-first compare eliminates it entirely.
        assertFalse(KeyAuthFilter.constantTimeEquals("same\0", "same"), "NUL suffix must not authenticate");
        assertFalse(KeyAuthFilter.constantTimeEquals("same\0\0\0", "same"), "any NUL run must not authenticate");
        assertFalse(KeyAuthFilter.constantTimeEquals("\0same", "same"), "NUL prefix must not authenticate");
    }

    @Test
    void filterLevelRejectionRecordsMeasuredLatencyNotZero() {
        // A filter-level rejection must record the measured filter latency into
        // the janus_request_duration_seconds timer (the same series successful requests
        // record to), never a hardcoded 0 — a 0 sample would drag the p50/p95 down on
        // auth-heavy workloads. A slow store's authenticate makes the elapsed measurable
        // (a fast rejection is legitimately sub-ms; the pin is that the real elapsed is
        // recorded, not that a rejection is slow). The rejection now travels as the
        // deferred publisher's error — the metering still fires on that path.
        InMemoryKeyStore store = new InMemoryKeyStore(TestKeyAuthFactory.CLOCK);
        SlowAuthenticateKeyStore slow = new SlowAuthenticateKeyStore(store);
        RecordingMetricsRecorder recorder = new RecordingMetricsRecorder();
        KeyAuthFilter filter = new KeyAuthFilter(slow, MASTER_KEY, recorder);
        ServerFilterChain chain = request -> Publishers.just(HttpResponse.ok());
        KeyGenerator.Generated unknown = KeyGenerator.generate(); // well-formed, never stored

        LatchedSubscriber subscriber = new LatchedSubscriber();
        filter.doFilter(HttpRequest.POST("/v1/chat/completions", "").header("x-api-key", unknown.fullKey()), chain)
                .subscribe(subscriber);
        subscriber.awaitTerminal();
        assertTrue(subscriber.error instanceof KeyAuthException, "an unknown key is rejected at filter level");
        assertTrue(
                recorder.lastDurationMillis > 0,
                "a filter-level rejection must record the measured latency (got " + recorder.lastDurationMillis + ")");
    }

    /** A {@link KeyStore} that sleeps inside {@code authenticate} so the rejection latency is measurable. */
    private static final class SlowAuthenticateKeyStore implements KeyStore {

        private final InMemoryKeyStore delegate;

        SlowAuthenticateKeyStore(InMemoryKeyStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public CreatedKey create(KeyCreateRequest request) {
            return delegate.create(request);
        }

        @Override
        public Optional<KeyRecord> findByPrefix(String prefix) {
            return delegate.findByPrefix(prefix);
        }

        @Override
        public boolean revoke(String id) {
            return delegate.revoke(id);
        }

        @Override
        public AuthResult authenticate(String prefix, String secret) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return delegate.authenticate(prefix, secret);
        }

        @Override
        public List<KeyRecordView> list() {
            return delegate.list();
        }

        @Override
        public void touch(String prefix) {
            delegate.touch(prefix);
        }
    }

    /** A {@link KeyStore} that records the thread {@code authenticate} ran on (the offload pin). */
    private static final class ThreadRecordingKeyStore implements KeyStore {

        private final KeyStore delegate;
        private final AtomicReference<String> authenticateThread;

        ThreadRecordingKeyStore(KeyStore delegate, AtomicReference<String> authenticateThread) {
            this.delegate = delegate;
            this.authenticateThread = authenticateThread;
        }

        @Override
        public CreatedKey create(KeyCreateRequest request) {
            return delegate.create(request);
        }

        @Override
        public Optional<KeyRecord> findByPrefix(String prefix) {
            return delegate.findByPrefix(prefix);
        }

        @Override
        public boolean revoke(String id) {
            return delegate.revoke(id);
        }

        @Override
        public AuthResult authenticate(String prefix, String secret) {
            authenticateThread.set(Thread.currentThread().getName());
            return delegate.authenticate(prefix, secret);
        }

        @Override
        public List<KeyRecordView> list() {
            return delegate.list();
        }

        @Override
        public void touch(String prefix) {
            delegate.touch(prefix);
        }
    }

    /** A {@link KeyStore} whose {@code authenticate} fails like a down Postgres store. */
    private static final class FailingAuthenticateKeyStore implements KeyStore {

        private final KeyStore delegate;

        FailingAuthenticateKeyStore(KeyStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public CreatedKey create(KeyCreateRequest request) {
            return delegate.create(request);
        }

        @Override
        public Optional<KeyRecord> findByPrefix(String prefix) {
            return delegate.findByPrefix(prefix);
        }

        @Override
        public boolean revoke(String id) {
            return delegate.revoke(id);
        }

        @Override
        public AuthResult authenticate(String prefix, String secret) {
            throw new IllegalStateException("postgres is down");
        }

        @Override
        public List<KeyRecordView> list() {
            return delegate.list();
        }

        @Override
        public void touch(String prefix) {
            delegate.touch(prefix);
        }
    }

    /**
     * Records the last {@code recordRequest} call (latency + label pins); can be armed
     * to throw so tests pin the best-effort guard.
     */
    private static final class RecordingMetricsRecorder implements MetricsRecorder {

        long lastDurationMillis = -1;
        String lastFace;
        int lastStatus = -1;
        boolean throwOnRecord;

        @Override
        public void recordRequest(String face, int status, long durationMillis, String keyId) {
            if (throwOnRecord) {
                throw new IllegalStateException("registry cleared");
            }
            this.lastFace = face;
            this.lastStatus = status;
            this.lastDurationMillis = durationMillis;
        }

        @Override
        public void recordUsage(String keyId, Usage usage, long costMicroUsd) {}

        @Override
        public void forgetKey(String keyId) {}
    }

    /**
     * A {@link org.reactivestreams.Subscriber} that latches the terminal signal (the
     * deferred-publisher tests' awaiting seam; with the test direct executor everything
     * completes inline in {@code subscribe}, with a real pool the latch covers the hop).
     */
    private static final class LatchedSubscriber implements Subscriber<MutableHttpResponse<?>> {

        private final CountDownLatch done = new CountDownLatch(1);

        volatile Throwable error;
        volatile int responses;

        @Override
        public void onSubscribe(Subscription s) {
            s.request(1);
        }

        @Override
        public void onNext(MutableHttpResponse<?> response) {
            responses++;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            done.countDown();
        }

        @Override
        public void onComplete() {
            done.countDown();
        }

        /** Blocks for the terminal signal (inline with the direct executor, one hop with a pool). */
        void awaitTerminal() {
            try {
                assertTrue(done.await(10, TimeUnit.SECONDS), "a terminal signal must arrive");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted awaiting the deferred publisher's terminal signal", e);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private KeyStore.CreatedKey create(String owner, List<String> models) {
        return keyStore.create(new KeyStore.KeyCreateRequest(owner, models, null, null, null, null, null));
    }

    private String createKey(String owner, List<String> models) {
        return create(owner, models).fullKey();
    }

    private static ChatResponse chatResponse() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new io.amscotti.janus.core.model.AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static String requestBody(String model) {
        return "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false}";
    }

    private static String anthropicBody() {
        return "{\"model\":\"deepseek-v4-flash\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private HttpResponse<String> errorResponse(HttpRequest<?> request) {
        HttpClientResponseException exception = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class, () -> client.toBlocking().exchange(request, String.class));
        HttpResponse<?> response = exception.getResponse();
        return HttpResponse.status(response.getStatus())
                .body(response.getBody(String.class).orElse(""));
    }
}
