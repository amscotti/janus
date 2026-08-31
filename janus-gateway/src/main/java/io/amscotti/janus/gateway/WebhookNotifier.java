package io.amscotti.janus.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Webhook {@link Notifier} (; the Notifier.Webhook port): POSTs the
 * event JSON to the configured URL with the <b>JDK {@link java.net.http.HttpClient}</b>
 * — zero new dependencies, native-image clean. Fire-and-forget via
 * {@code HttpClient.sendAsync} on a virtual-thread-friendly completion path: {@link
 * #notify} returns immediately, the async send's failure is swallowed (logged at WARN
 * — <b>exception class name only</b>, never {@code e.toString}: JDK HttpClient
 * failure messages embed the request URI, and the webhook URL may carry a credential
 * in a query parameter) and a synchronous failure (client closed) is caught
 * and logged — {@code notify}
 * <b>never raises</b> and never stalls the request path (adapter contract:
 * MUST NOT raise).
 *
 * <p><b>The URL is parsed lazily in {@link #notify}, not in the constructor.</b>
 * {@code URI.create} throws {@code IllegalArgumentException} on a syntactically-invalid
 * URL; parsing it at construction would abort Micronaut context startup on a typo'd
 * {@code notifier-webhook-url}, contradicting the "a synchronous failure is caught and
 * logged — notify never raises" contract. Lazy parsing means a bad URL degrades to
 * "never notifies" (the existing build-failure path logs and drops), so the gateway
 * always boots. {@link GovernanceFactory} additionally validates the configured URL
 * and falls back to {@link LoggingNotifier} when it is malformed, so a misconfigured
 * deployment boots <em>without</em> webhooks rather than failing.
 *
 * <p>The payload is serialized with the shared {@link GatewayJson} mapper (a plain
 * {@code Map} — no new reflect-config entries). The {@code HttpClient} is injected
 * (package-private constructor) so tests substitute a recording subclass of the JDK
 * client ({@code java.net.http.HttpClient} has a protected constructor) — no network
 * in tests.
 */
final class WebhookNotifier implements Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookNotifier.class);

    /**
     * Per-request timeout: an endpoint that accepts TCP but never responds
     * must not hold its connection/completion state forever. The {@link HttpClient}'s
     * own connect timeout (set in {@code GovernanceFactory.webhookOrLogger}) bounds the
     * connect phase; this bounds the whole send — a stalled webhook degrades to a WARN,
     * and its connection is reclaimed instead of leaking silently.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final String webhookUrl;

    WebhookNotifier(HttpClient httpClient, String webhookUrl) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.webhookUrl = Objects.requireNonNull(webhookUrl, "webhookUrl");
    }

    @Override
    public void notify(String event, Map<String, Object> payload) {
        HttpRequest request;
        try {
            String body = GatewayJson.write(payload);
            // Lazy URI.parse: a syntactically-invalid URL surfaces here — inside
            // the existing catch — as a log-and-drop build failure, never in the
            // constructor (where it would abort the gateway boot).
            request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException e) {
            // Malformed payload / URL: log and drop — never raise into the request path.
            // Log the exception class name, never e.toString — JDK HttpClient
            // failures embed the request URI in their message, and the webhook URL may
            // carry a credential in a query parameter (the "never echo the URL" hygiene
            // guarantee below applies to every failure path).
            LOG.warn(
                    "webhook notification dropped (build failure): {}",
                    e.getClass().getSimpleName());
            return;
        }
        try {
            httpClient
                    .sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            LOG.warn(
                                    "webhook notification failed: {}",
                                    error.getClass().getSimpleName());
                        } else if (response != null && (response.statusCode() < 200 || response.statusCode() >= 300)) {
                            // A non-2xx is a silent delivery failure on the fire-and-forget
                            // path — log it so undelivered notifications are observable
                            // Never echo the webhook URL: it may carry a
                            // credential in a query parameter.
                            LOG.warn(
                                    "webhook notification returned HTTP {} (event not acknowledged)",
                                    response.statusCode());
                        }
                    });
        } catch (RuntimeException e) {
            LOG.warn(
                    "webhook notification dropped (send failure): {}",
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public void forgetKey(String keyId) {
        // no per-key state — nothing to prune (the Notifier.forgetKey contract)
    }
}
