package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.router.RoundRobinLoadBalancer;
import io.amscotti.janus.router.Router;
import io.amscotti.janus.router.SessionAffinityLoadBalancer;
import io.amscotti.janus.store.FixedWindowRateLimiter;
import io.amscotti.janus.store.InMemoryCallStore;
import io.amscotti.janus.store.InMemorySpendLedger;
import io.amscotti.janus.store.PriceTable;
import io.amscotti.janus.store.PricingRate;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.simple.SimpleHttpHeaders;
import io.micronaut.http.simple.SimpleHttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code x-janus-session-id} inbound-header fold on <b>all three faces</b>
 * (chat / messages / responses): the base {@link ModelFaceControllerSupport} ladder
 * folds the header into {@code ChatRequest.meta} under
 * {@link SessionAffinityLoadBalancer#META_SESSION_ID} right after {@code decorate}
 * — so every face gets it without touching the per-face overrides — and the
 * session-affinity strategy picks on it. Absent or blank-after-trim header ⇒ no
 * meta entry at all (never a null/blank value).
 */
class SessionIdHeaderFoldTest {

    private static final Clock CLOCK = TestKeyAuthFactory.CLOCK;
    private static final String ALIAS = "deepseek-v4-flash";

    /** Global-only resolver (the no-override shape — deadlines are not this test's subject). */
    private static final StreamIdleTimeoutResolver IDLE =
            new StreamIdleTimeoutResolver(Duration.ofSeconds(60), Map.of());

    private static final String HEADER = "x-janus-session-id";
    private static final String META_KEY = SessionAffinityLoadBalancer.META_SESSION_ID;

    /** The three faces, exercised through their controller entry methods directly. */
    private enum Face {
        CHAT("/v1/chat/completions") {
            HttpResponse<?> run(ModelFaceControllerSupport controller, String body, HttpRequest<?> request) {
                return ((ChatCompletionsController) controller).chat(body, request);
            }
        },
        MESSAGES("/v1/messages") {
            HttpResponse<?> run(ModelFaceControllerSupport controller, String body, HttpRequest<?> request) {
                return ((MessagesController) controller).messages(body, request);
            }
        },
        RESPONSES("/v1/responses") {
            HttpResponse<?> run(ModelFaceControllerSupport controller, String body, HttpRequest<?> request) {
                return ((ResponsesController) controller).responses(body, request);
            }
        };

        final String path;

        Face(String path) {
            this.path = path;
        }

        abstract HttpResponse<?> run(ModelFaceControllerSupport controller, String body, HttpRequest<?> request);
    }

    /** Dispatched request captured by the fake backend for one face invocation. */
    private record Dispatched(HttpResponse<?> response, ChatRequest request) {}

    private static Governance governance(InMemoryCallStore calls) {
        return new Governance(
                new FixedWindowRateLimiter(CLOCK),
                PriceTable.of(Map.of(ALIAS, new PricingRate(0.14, 0.28, 0.0, 0.0, 4096))),
                new InMemorySpendLedger(CLOCK, 1000),
                new RecordingNotifier(),
                0.8,
                CLOCK,
                MetricsRecorder.noop(),
                calls);
    }

    private static ChatResponse cannedResponse() {
        return new ChatResponse(
                "resp-1",
                "chat.completion",
                1_700_000_000L,
                ALIAS,
                List.of(new ChatChoice(0, new AssistantMessage("Hello!", null), "stop")),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                Map.of());
    }

    private static String faceBody(Face face) {
        return switch (face) {
            case CHAT -> "{\"model\":\"" + ALIAS + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            case MESSAGES ->
                "{\"model\":\"" + ALIAS + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":16}";
            case RESPONSES -> "{\"model\":\"" + ALIAS + "\",\"input\":\"hello\"}";
        };
    }

    /**
     * One non-streaming request through the face's controller; captures the dispatched
     * canonical. The header carrier is {@link SimpleHttpRequest} with a pre-populated
     * {@link SimpleHttpHeaders} map: it keeps header handling off the Netty builder
     * (whose value validation rejects even the empty string) while using the same
     * case-insensitive lookup the server path uses.
     */
    private static final class RawHeaderRequest extends SimpleHttpRequest<String> {

        private final SimpleHttpHeaders headers;

        RawHeaderRequest(String uri, Map<String, String> rawHeaders) {
            super(HttpMethod.POST, uri, "");
            this.headers = new SimpleHttpHeaders(rawHeaders, ConversionService.SHARED);
        }

        @Override
        public SimpleHttpHeaders getHeaders() {
            return headers;
        }
    }

    private static Dispatched dispatch(Face face, Map<String, String> headers) {
        FakeBackend backend = new FakeBackend("deepseek");
        backend.completeReturns(cannedResponse());
        InMemoryCallStore calls = new InMemoryCallStore(CLOCK, 1000);
        Governance governance = governance(calls);
        Router router = Router.balanced(Map.of(ALIAS, List.of(backend)), new RoundRobinLoadBalancer());
        ModelFaceControllerSupport controller =
                switch (face) {
                    case CHAT -> new ChatCompletionsController(router, governance, MetricsRecorder.noop(), IDLE);
                    case MESSAGES -> new MessagesController(router, governance, MetricsRecorder.noop(), IDLE);
                    case RESPONSES -> new ResponsesController(router, governance, MetricsRecorder.noop(), IDLE);
                };
        HttpRequest<?> request = new RawHeaderRequest(face.path, headers);
        HttpResponse<?> response = face.run(controller, faceBody(face), request);
        assertEquals(200, response.getStatus().getCode(), face + " face served the request");
        assertEquals(1, backend.completeCalls.size(), face + " dispatched exactly once");
        return new Dispatched(response, backend.completeCalls.getFirst());
    }

    @Test
    void headerFoldsIntoMetaOnAllThreeFaces() {
        for (Face face : Face.values()) {
            ChatRequest dispatched = dispatch(face, Map.of(HEADER, "conv-9")).request();
            assertEquals("conv-9", dispatched.meta().get(META_KEY), face + " face folds the session header into meta");
        }
    }

    @Test
    void headerLookupIsCaseInsensitive() {
        for (Face face : Face.values()) {
            // Mixed-case header name: HTTP header lookup is case-insensitive (the
            // same case-insensitive map the server path uses), so the lowercase
            // constant in the fold finds any client spelling. (Whitespace-padded
            // values are unpinnable here — every Micronaut header carrier validates
            // them away, and the server parser strips surrounding OWS; the
            // trim/blank discipline is pinned one level down on the meta-carried
            // values the strategy reads — SessionAffinityLoadBalancerTest.)
            ChatRequest dispatched =
                    dispatch(face, Map.of("X-JANUS-SESSION-ID", "conv-10")).request();
            assertEquals(
                    "conv-10", dispatched.meta().get(META_KEY), face + " face: mixed-case header name folds correctly");
        }
    }

    @Test
    void absentOrEmptyHeaderLeavesNoMetaEntry() {
        for (Face face : Face.values()) {
            assertFalse(
                    dispatch(face, Map.of()).request().meta().containsKey(META_KEY),
                    face + " face: absent header must leave no meta entry");
            // The empty string is the one blank value Micronaut can even represent
            // (every add/map path validates away whitespace-padded values, and the
            // server parser strips surrounding OWS) — it must count as absent: no
            // meta entry at all, never a null/blank value. Whitespace-only and
            // trim semantics are pinned one level down, on the meta-carried values
            // the strategy reads (SessionAffinityLoadBalancerTest).
            assertFalse(
                    dispatch(face, Map.of(HEADER, "")).request().meta().containsKey(META_KEY),
                    face + " face: empty header value must leave no meta entry");
        }
    }

    @Test
    void sessionFoldCoexistsWithTheAnthropicBetaFold() {
        // The session fold runs in the base ladder AFTER decorate: the Anthropic
        // face's override folds anthropic-beta first, and both whitelisted entries
        // coexist on the dispatched canonical.
        ChatRequest dispatched = dispatch(
                        Face.MESSAGES, Map.of("anthropic-beta", "context-management-2025-06-27", HEADER, "conv-11"))
                .request();
        assertEquals("context-management-2025-06-27", dispatched.meta().get("anthropic-beta"));
        assertEquals("conv-11", dispatched.meta().get(META_KEY));
        assertTrue(dispatched.meta().size() >= 2, "both whitelisted meta entries present");
    }
}
