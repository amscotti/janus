package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link Notifier} adapters: {@link LoggingNotifier} is the zero-config
 * WARN sink (notify never throws); {@link WebhookNotifier} POSTs the JSON payload via
 * an injected <b>recording</b> {@link java.net.http.HttpClient} subclass (the JDK
 * client has a protected constructor, so tests override {@code sendAsync} without
 * touching the network) and — the reference adapter contract — {@code notify} never
 * raises: a failing client is swallowed, never propagated into the request path.
 */
class NotifierTest {

    @Test
    void loggingNotifierNeverRaises() {
        LoggingNotifier notifier = new LoggingNotifier();
        notifier.notify("budget_exceeded", Map.of("key_id", "k1", "tier", "soft"));
        // A null-ish payload still must not crash the sink (SLF4J contract).
        notifier.notify("budget_exceeded", Map.of());
    }

    @Test
    void webhookNotifierPostsJsonPayloadViaInjectedClient() throws Exception {
        RecordingHttpClient client = new RecordingHttpClient();
        WebhookNotifier notifier = new WebhookNotifier(client, "http://hooks.example.com/janus");

        notifier.notify(
                "budget_exceeded",
                Map.of("key_id", "k1", "tier", "soft", "committed_micro_usd", 42L, "cap_micro_usd", 1000L));

        assertEquals(1, client.requests.size(), "exactly one POST per event");
        HttpRequest request = client.requests.get(0);
        assertEquals("POST", request.method());
        assertEquals(URI.create("http://hooks.example.com/janus"), request.uri());
        assertEquals(
                Optional.of(Duration.ofSeconds(10)),
                request.timeout(),
                "the send carries a bounded timeout — a stalled webhook must not leak its connection");
        assertEquals(
                "application/json", request.headers().firstValue("Content-Type").orElse(""));
        String body = drainBody(request);
        assertTrue(body.contains("\"key_id\":\"k1\""), body);
        assertTrue(body.contains("\"tier\":\"soft\""), body);
        assertTrue(body.contains("\"committed_micro_usd\":42"), body);
        assertTrue(body.contains("\"cap_micro_usd\":1000"), body);
    }

    @Test
    void webhookNotifierSwallowsSendFailures() {
        RecordingHttpClient client = new RecordingHttpClient(new IllegalStateException("client closed"));
        WebhookNotifier notifier = new WebhookNotifier(client, "http://hooks.example.com/janus");
        // notify must never raise — the failing adapter is swallowed.
        notifier.notify("budget_exceeded", Map.of("key_id", "k1", "tier", "hard"));
        assertEquals(0, client.requests.size(), "the failed send was never recorded");
    }

    @Test
    void webhookNotifierSwallowsBuildFailures() {
        // A payload the mapper cannot serialize is a build failure — notify must still
        // return without raising, and nothing is posted (adapter contract).
        RecordingHttpClient client = new RecordingHttpClient();
        WebhookNotifier notifier = new WebhookNotifier(client, "http://hooks.example.com/janus");
        notifier.notify("budget_exceeded", Map.of("broken", (Object) new ThrowingBean()));
        assertEquals(0, client.requests.size(), "the build failure aborted the send");
    }

    @Test
    void webhookNotifierDoesNotRaiseOnASyntacticallyInvalidUrl() {
        // The URL is parsed lazily (not in the constructor, where it would
        // abort the gateway boot), so a bad URL degrades to "never notifies" — notify
        // still returns without raising and nothing is posted. The GovernanceFactory
        // additionally falls back to the logger, so this is defense-in-depth.
        RecordingHttpClient client = new RecordingHttpClient();
        WebhookNotifier notifier = new WebhookNotifier(client, "http://host with space/hook");
        notifier.notify("budget_exceeded", Map.of("key_id", "k1", "tier", "soft"));
        assertEquals(0, client.requests.size(), "a malformed URL must abort the send, never raise");
    }

    @Test
    void webhookNotifierDoesNotRaiseOnANon2xxResponse() {
        // A non-2xx webhook response is a silent delivery failure on the
        // fire-and-forget path — the whenComplete WARNs and notify must not raise.
        RecordingHttpClient client = new RecordingHttpClient(new StubHttpResponse(500));
        WebhookNotifier notifier = new WebhookNotifier(client, "http://hooks.example.com/janus");
        notifier.notify("budget_exceeded", Map.of("key_id", "k1", "tier", "hard"));
        assertEquals(1, client.requests.size(), "the 500 response was received, not rethrown");
    }

    @Test
    void webhookNotifierDoesNotRaiseOnAnUnparseableScheme() {
        RecordingHttpClient client = new RecordingHttpClient();
        WebhookNotifier notifier = new WebhookNotifier(client, "://missing-scheme");
        notifier.notify("budget_exceeded", Map.of("key_id", "k1", "tier", "soft"));
        assertEquals(0, client.requests.size(), "an unparseable URL must abort the send, never raise");
    }

    @Test
    void webhookSynchronousFailureLogsNeverEchoTheUrl() {
        // JDK HttpClient failure exceptions embed the request URI in their
        // message — a webhook URL carrying a credential in a query parameter must never
        // reach the logs (the javadoc's "never echo the webhook URL" hygiene guarantee).
        // The synchronous throw path (sendAsync throws) logs the exception class name only.
        String secretUrl = "https://hooks.example.com/janus?token=super-secret-value";
        RecordingHttpClient client =
                new RecordingHttpClient(new IllegalStateException("send to " + secretUrl + " failed"));
        ListAppender<ILoggingEvent> logs = captureLogs(WebhookNotifier.class);

        WebhookNotifier notifier = new WebhookNotifier(client, secretUrl);
        notifier.notify("budget_exceeded", Map.of("key_id", "k1", "tier", "hard"));

        assertTrue(
                logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains(secretUrl)),
                "the webhook URL (and any credential in it) must never reach the logs: " + logs.list);
        assertTrue(
                logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("IllegalStateException")),
                "the failure must still be observable via its class name: " + logs.list);
    }

    @Test
    void webhookAsynchronousFailureLogsNeverEchoTheUrl() {
        // The async completion path (sendAsync returns a future that completes
        // exceptionally) is the second place a URL-embedded exception message could
        // leak — the whenComplete WARN is class-name-only too.
        String secretUrl = "https://hooks.example.com/janus?token=super-secret-value";
        RecordingHttpClient client = new RecordingHttpClient(
                CompletableFuture.failedFuture(new IllegalStateException("connect to " + secretUrl + " timed out")));
        ListAppender<ILoggingEvent> logs = captureLogs(WebhookNotifier.class);

        WebhookNotifier notifier = new WebhookNotifier(client, secretUrl);
        notifier.notify("budget_exceeded", Map.of("key_id", "k1", "tier", "hard"));

        assertEquals(1, client.requests.size(), "the send was attempted and failed asynchronously");
        assertTrue(
                logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains(secretUrl)),
                "the webhook URL (and any credential in it) must never reach the logs: " + logs.list);
        assertTrue(
                logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("IllegalStateException")),
                "the failure must still be observable via its class name: " + logs.list);
    }

    /** Attach a recording logback appender to {@code loggerClass}'s logger. */
    private static ListAppender<ILoggingEvent> captureLogs(Class<?> loggerClass) {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    /** A bean whose only getter throws — exercises the mapper's serialization failure path. */
    static final class ThrowingBean {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }

    /** Minimal non-2xx response double — the notifier's whenComplete reads statusCode() only. */
    private static final class StubHttpResponse implements HttpResponse<String> {

        private final int status;

        StubHttpResponse(int status) {
            this.status = status;
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return null;
        }

        @Override
        public String body() {
            return null;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return null;
        }

        @Override
        public HttpClient.Version version() {
            return null;
        }
    }

    private static String drainBody(HttpRequest request) throws Exception {
        CompletableFuture<String> body = new CompletableFuture<>();
        request.bodyPublisher()
                .orElseThrow()
                .subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    private final StringBuilder sb = new StringBuilder();

                    @Override
                    public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                        subscription.request(Long.MAX_VALUE);
                    }

                    @Override
                    public void onNext(java.nio.ByteBuffer item) {
                        byte[] bytes = new byte[item.remaining()];
                        item.get(bytes);
                        sb.append(new String(bytes, StandardCharsets.UTF_8));
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        body.completeExceptionally(throwable);
                    }

                    @Override
                    public void onComplete() {
                        body.complete(sb.toString());
                    }
                });
        return body.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Test double: {@code java.net.http.HttpClient} with a protected no-arg super(). */
    static final class RecordingHttpClient extends HttpClient {

        final List<HttpRequest> requests = new ArrayList<>();
        private final RuntimeException sendFailure;
        private final CompletableFuture<HttpResponse<?>> cannedFuture;
        private final HttpResponse<?> cannedResponse;

        RecordingHttpClient() {
            this(null, null, null);
        }

        RecordingHttpClient(RuntimeException sendFailure) {
            this(sendFailure, null, null);
        }

        RecordingHttpClient(HttpResponse<?> cannedResponse) {
            this(null, null, cannedResponse);
        }

        RecordingHttpClient(CompletableFuture<HttpResponse<?>> cannedFuture) {
            this(null, cannedFuture, null);
        }

        private RecordingHttpClient(
                RuntimeException sendFailure,
                CompletableFuture<HttpResponse<?>> cannedFuture,
                HttpResponse<?> cannedResponse) {
            super();
            this.sendFailure = sendFailure;
            this.cannedFuture = cannedFuture;
            this.cannedResponse = cannedResponse;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            if (sendFailure != null) {
                throw sendFailure;
            }
            requests.add(request);
            if (cannedFuture != null) {
                return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) cannedFuture;
            }
            if (cannedResponse != null) {
                return CompletableFuture.completedFuture((HttpResponse<T>) cannedResponse);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("no blocking sends in tests");
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null;
        }

        @Override
        public SSLParameters sslParameters() {
            return null;
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public Version version() {
            return Version.HTTP_2;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }
    }
}
