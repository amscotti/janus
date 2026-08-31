package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * step 2: {@link Router#balanced} — construction validation (fail fast, the reference
 * {@code integrity_check} philosophy), defensive copies, {@code route} returning the
 * first candidate, {@code complete}/{@code stream} picking via the {@link LoadBalancer}
 * and firing the observation hooks in order (including the exception path), stream
 * wrapping with close-through, and the unchanged {@code UnknownModelException} /
 * {@code models} contracts.
 */
class RouterBalancedTest {

    private static final class ThrowingBackend implements ChatBackend {

        private final String name;
        private final RuntimeException failure;

        ThrowingBackend(String name, RuntimeException failure) {
            this.name = name;
            this.failure = failure;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String baseUrl() {
            return "http://fake/" + name;
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            throw failure;
        }

        @Override
        public Stream<StreamChunk> stream(ChatRequest request) {
            throw failure;
        }
    }

    /** Backend that fails with an {@link Error} — the end hook must fire for any Throwable. */
    private static final class ErrorBackend implements ChatBackend {

        private final String name;
        private final Error failure;

        ErrorBackend(String name, Error failure) {
            this.name = name;
            this.failure = failure;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String baseUrl() {
            return "http://fake/" + name;
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            throw failure;
        }

        @Override
        public Stream<StreamChunk> stream(ChatRequest request) {
            throw failure;
        }
    }

    // --- construction validation (fail fast) -----------------------------------------

    @Test
    void rejectsNullRoutes() {
        assertThrows(
                NullPointerException.class, () -> Router.balanced(null, new RecordingLoadBalancer(TestData.fake("A"))));
    }

    @Test
    void rejectsNullLoadBalancer() {
        assertThrows(NullPointerException.class, () -> Router.balanced(Map.of("m", List.of(TestData.fake("A"))), null));
    }

    @Test
    void rejectsNullAliasKey() {
        Map<String, List<ChatBackend>> routes = new HashMap<>();
        routes.put(null, List.of(TestData.fake("A")));
        assertThrows(
                NullPointerException.class,
                () -> Router.balanced(routes, new RecordingLoadBalancer(TestData.fake("A"))));
    }

    @Test
    void rejectsBlankAliases() {
        for (String blank : List.of("", " ", "  \t ")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Router.balanced(
                            Map.of(blank, List.of(TestData.fake("A"))), new RecordingLoadBalancer(TestData.fake("A"))),
                    blank);
        }
    }

    @Test
    void rejectsNullCandidateList() {
        Map<String, List<ChatBackend>> routes = new HashMap<>();
        routes.put("m", null);
        assertThrows(
                NullPointerException.class,
                () -> Router.balanced(routes, new RecordingLoadBalancer(TestData.fake("A"))));
    }

    @Test
    void rejectsEmptyCandidateList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Router.balanced(Map.of("m", List.of()), new RecordingLoadBalancer(TestData.fake("A"))));
    }

    @Test
    void rejectsNullCandidateEntry() {
        List<ChatBackend> candidates = new ArrayList<>();
        candidates.add(null);
        assertThrows(
                NullPointerException.class,
                () -> Router.balanced(Map.of("m", candidates), new RecordingLoadBalancer(TestData.fake("A"))));
    }

    @Test
    void copiesInputDefensively() {
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        FakeBackend c = TestData.fake("C");
        List<ChatBackend> list = new ArrayList<>(List.of(a, b));
        Map<String, List<ChatBackend>> input = new HashMap<>();
        input.put("m", list);
        Router router = Router.balanced(input, new RecordingLoadBalancer(a));
        // Mutate the caller's map and list after construction — routing must not change.
        list.add(c);
        input.put("m2", List.of(c));
        assertSame(a, router.route("m"));
        assertThrows(UnknownModelException.class, () -> router.route("m2"));
        assertEquals(Set.of("m"), router.models());
    }

    // --- route ---------------------------------------------------------------------

    @Test
    void routeReturnsFirstCandidate() {
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        // route is *not* the LB pick — the recording LB would choose b, route returns a.
        Router router = Router.balanced(Map.of("m", List.of(a, b)), new RecordingLoadBalancer(b));
        assertSame(a, router.route("m"));
    }

    @Test
    void routeUnknownAliasThrows() {
        Router router = Router.balanced(
                Map.of("m", List.of(TestData.fake("A"))), new RecordingLoadBalancer(TestData.fake("A")));
        UnknownModelException e = assertThrows(UnknownModelException.class, () -> router.route("gpt-4"));
        assertEquals("gpt-4", e.model());
    }

    @Test
    void routeRejectsNullAndBlankModels() {
        Router router = Router.balanced(
                Map.of("m", List.of(TestData.fake("A"))), new RecordingLoadBalancer(TestData.fake("A")));
        assertThrows(IllegalArgumentException.class, () -> router.route(null));
        assertThrows(IllegalArgumentException.class, () -> router.route(""));
        assertThrows(IllegalArgumentException.class, () -> router.route("   "));
    }

    // --- complete ------------------------------------------------------------------

    @Test
    void completePicksAndFiresHooksInOrder() {
        ChatResponse expected = TestData.response("m");
        FakeBackend a = TestData.fake("A", expected);
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router = Router.balanced(Map.of("m", List.of(a)), lb);
        ChatRequest request = TestData.request("m");
        ChatResponse actual = router.complete(request);
        assertSame(expected, actual);
        assertSame(request, a.completeCalls.get(0)); // meta passthrough by identity
        assertEquals("m", lb.lastModel);
        assertEquals(List.of(a), lb.lastCandidates); // the full candidate list reaches pick()
        assertEquals(List.of("pick", "start:A", "sample:A", "end:A:true:resp-m"), lb.trace);
    }

    @Test
    void dispatchObserverSeesThePickedBackendNeverRoute() {
        // The observer seam exists for per-provider accounting: route is deliberately
        // the config-first candidate, so a caller attributing calls via route
        // misattributes every request the balancer sends elsewhere. The observer
        // delivers the actual pick.
        ChatResponse expected = TestData.response("m");
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B", expected);
        Router router = Router.balanced(Map.of("m", List.of(a, b)), new RecordingLoadBalancer(b));
        List<ChatBackend> dispatched = new ArrayList<>();
        ChatResponse actual = router.complete(TestData.request("m"), dispatched::add);
        assertSame(expected, actual);
        assertEquals("A", router.route("m").name(), "route() stays the config-first candidate");
        assertEquals(List.of(b), dispatched, "the observer sees the LB pick, never route()'s first candidate");
    }

    @Test
    void throwingDispatchObserverIsContainedAndNeverMasksTheDispatch() {
        ChatResponse expected = TestData.response("m");
        FakeBackend a = TestData.fake("A", expected);
        Router router = Router.balanced(Map.of("m", List.of(a)), new RecordingLoadBalancer(a));
        ChatResponse actual = router.complete(TestData.request("m"), backend -> {
            throw new IllegalStateException("observer exploded");
        });
        assertSame(expected, actual, "a throwing observer must not mask the dispatch it observes");
        assertEquals(1, a.completeCalls.size(), "the dispatch still happened");
    }

    @Test
    void streamDispatchObserverSeesTheOpenedBackendBeforeReturn() {
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B", TestData.response("m"), Stream.of(TestData.chunk()));
        Router router = Router.balanced(Map.of("m", List.of(a, b)), new RecordingLoadBalancer(b));
        List<ChatBackend> dispatched = new ArrayList<>();
        Stream<StreamChunk> routed = router.stream(TestData.request("m"), dispatched::add);
        assertEquals(List.of(b), dispatched, "the observer fired before stream() returned — the serving backend");
        routed.close();
    }

    @Test
    void completeEndFiresWithSuccessFalseWhenBackendThrows() {
        IllegalStateException failure = new IllegalStateException("boom");
        ThrowingBackend a = new ThrowingBackend("A", failure);
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router = Router.balanced(Map.of("m", List.of(a)), lb);
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> router.complete(TestData.request("m")));
        assertSame(failure, e); // propagated untouched
        assertEquals(List.of("pick", "start:A", "end:A:false:null"), lb.trace);
    }

    @Test
    void errorFromBackendStillFiresEndAndReleasesLeastInflightSlot() {
        LeastInflightLoadBalancer lb = new LeastInflightLoadBalancer();
        ErrorBackend a = new ErrorBackend("A", new AssertionError("kaboom"));
        FakeBackend b = TestData.fake("B");
        Router router = Router.balanced(Map.of("m", List.of(a, b)), lb);
        AssertionError e = assertThrows(AssertionError.class, () -> router.complete(TestData.request("m")));
        assertEquals("kaboom", e.getMessage()); // propagated untouched
        assertEquals(0, lb.inflightOf(a)); // end hook fired despite the Error: no leaked slot
    }

    @Test
    void completeUnknownAliasThrowsWithoutTouchingLoadBalancer() {
        RecordingLoadBalancer lb = new RecordingLoadBalancer(TestData.fake("A"));
        Router router = Router.balanced(Map.of("m", List.of(TestData.fake("A"))), lb);
        UnknownModelException e =
                assertThrows(UnknownModelException.class, () -> router.complete(TestData.request("gpt-4")));
        assertEquals("gpt-4", e.model());
        assertTrue(lb.trace.isEmpty()); // resolved before any pick
    }

    @Test
    void completeRejectsNullRequest() {
        Router router = Router.balanced(
                Map.of("m", List.of(TestData.fake("A"))), new RecordingLoadBalancer(TestData.fake("A")));
        assertThrows(NullPointerException.class, () -> router.complete(null));
    }

    @Test
    void completeRejectsNullAndBlankRequestModels() {
        Router router = Router.balanced(
                Map.of("m", List.of(TestData.fake("A"))), new RecordingLoadBalancer(TestData.fake("A")));
        assertThrows(IllegalArgumentException.class, () -> router.complete(TestData.request(null)));
        assertThrows(IllegalArgumentException.class, () -> router.complete(TestData.request(" ")));
    }

    // --- stream --------------------------------------------------------------------

    @Test
    void streamPicksAndFiresHooksInOrder() {
        FakeBackend a = TestData.fake("A", TestData.response("m"), Stream.of(TestData.chunk()));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router = Router.balanced(Map.of("m", List.of(a)), lb);
        Stream<StreamChunk> routed = router.stream(TestData.request("m"));
        assertNotSame(a.stream, routed); // balanced mode wraps the stream
        assertEquals(1, a.streamCalls.size());
        routed.findFirst().orElseThrow(); // first element → TTFT sample
        routed.close(); // close → end hook + underlying close
        assertEquals(List.of("pick", "start:A", "sample:A", "end:A:true:null"), lb.trace);
        assertTrue(a.streamClosed.get()); // close-releases-connection contract preserved
    }

    @Test
    void streamEndFiresWithSuccessFalseWhenOpeningStreamThrows() {
        IllegalStateException failure = new IllegalStateException("boom");
        ThrowingBackend a = new ThrowingBackend("A", failure);
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router = Router.balanced(Map.of("m", List.of(a)), lb);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> router.stream(TestData.request("m")));
        assertSame(failure, e); // propagated untouched
        assertEquals(List.of("pick", "start:A", "end:A:false:null"), lb.trace);
    }

    @Test
    void errorOpeningStreamStillFiresEndAndReleasesLeastInflightSlot() {
        LeastInflightLoadBalancer lb = new LeastInflightLoadBalancer();
        ErrorBackend a = new ErrorBackend("A", new AssertionError("kaboom"));
        FakeBackend b = TestData.fake("B");
        Router router = Router.balanced(Map.of("m", List.of(a, b)), lb);
        assertThrows(AssertionError.class, () -> router.stream(TestData.request("m")));
        assertEquals(0, lb.inflightOf(a)); // stream-open failure on an Error releases the slot too
    }

    @Test
    void streamUnknownAliasThrowsWithoutTouchingLoadBalancer() {
        RecordingLoadBalancer lb = new RecordingLoadBalancer(TestData.fake("A"));
        Router router = Router.balanced(Map.of("m", List.of(TestData.fake("A"))), lb);
        assertThrows(UnknownModelException.class, () -> router.stream(TestData.request("gpt-4")));
        assertTrue(lb.trace.isEmpty()); // resolved before any pick
    }

    @Test
    void streamRejectsNullAndBlankRequestModels() {
        Router router = Router.balanced(
                Map.of("m", List.of(TestData.fake("A"))), new RecordingLoadBalancer(TestData.fake("A")));
        assertThrows(IllegalArgumentException.class, () -> router.stream(TestData.request(null)));
        assertThrows(IllegalArgumentException.class, () -> router.stream(TestData.request(" ")));
    }

    // --- models --------------------------------------------------------------------

    @Test
    void modelsPreservesInsertionOrderAndIsUnmodifiable() {
        // LinkedHashMap pins the caller's config (insertion) order — Map.of's order is unspecified.
        Map<String, List<ChatBackend>> routes = new LinkedHashMap<>();
        routes.put("m1", List.of(TestData.fake("A")));
        routes.put("m2", List.of(TestData.fake("B")));
        Router router = Router.balanced(routes, new RecordingLoadBalancer(TestData.fake("A")));
        assertEquals(List.of("m1", "m2"), List.copyOf(router.models())); // insertion (config) order
        assertThrows(UnsupportedOperationException.class, () -> router.models().add("x"));
    }

    // --- request-aware pick (session-affinity seam) -----------------------------------

    @Test
    void pickReceivesTheChatRequestThroughTheRequestAwareEntryPoint() {
        // The router must call the 3-arg pick — the documented entry point a
        // request-aware strategy (session-affinity) overrides. A router that fell
        // back to the 2-arg form would silently bypass every such strategy.
        FakeBackend a = TestData.fake("A", TestData.response("m"));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router = Router.balanced(Map.of("m", List.of(a)), lb);
        ChatRequest request = TestData.request("m");
        router.complete(request);
        assertSame(request, lb.lastRequest, "the complete path's 3-arg pick receives the ChatRequest");
    }

    @Test
    void streamPickReceivesTheChatRequestToo() {
        FakeBackend a = TestData.fake("A", TestData.response("m"), Stream.of(TestData.chunk()));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router = Router.balanced(Map.of("m", List.of(a)), lb);
        ChatRequest request = TestData.request("m");
        router.stream(request).close();
        assertSame(request, lb.lastRequest, "the stream path threads the request into the 3-arg pick as well");
    }

    @Test
    void metaCarriedSessionIdSticksThroughTheBalancedRouter() {
        // The session id rides ChatRequest.meta from the gateway fold to the
        // strategy: one conversation's requests all land on the HRW winner.
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        FakeBackend a = TestData.fake("A", TestData.response("m"));
        FakeBackend b = TestData.fake("B", TestData.response("m"));
        Router router = Router.balanced(Map.of("m", List.of(a, b)), lb);
        ChatRequest sticky = TestData.request("m").withMetaEntry(SessionAffinityLoadBalancer.META_SESSION_ID, "conv-1");
        for (int i = 0; i < 6; i++) {
            router.complete(sticky);
        }
        assertEquals(6, a.completeCalls.size() + b.completeCalls.size());
        assertTrue(a.completeCalls.isEmpty() || b.completeCalls.isEmpty(), "one session, one backend — never both");
    }

    @Test
    void allTriedRePickUnderAffinityIsHrwDeterministic() {
        // The second 3-arg call site (nextUntried's all-tried re-pick) re-hashes
        // with the SAME session id, so the re-pick returns the session's HRW
        // winner deterministically: the winner fails once (retryable), the walk
        // tries the other backend, and the exhausted re-pick comes back home.
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        FakeBackend a = TestData.fake("A", TestData.response("m"));
        FakeBackend b = TestData.fake("B", TestData.response("m"));
        // Determine the winner for a fixed session id, then make it fail exactly
        // twice (both retries burn through) — attempt 0 (HRW pick) and the re-pick
        // after all candidates are tried must both be the winner; the one
        // config-order walk lands on the loser.
        String stickySession = "conv-repick";
        String winner = lb.pick(
                        "m",
                        List.of(a, b),
                        TestData.request("m").withMetaEntry(SessionAffinityLoadBalancer.META_SESSION_ID, stickySession))
                .name();
        // Both candidates fail exactly once (retryable): attempt 0 is the HRW pick
        // (the winner), the retry walk visits the other candidate, and with
        // everyone tried the re-pick must come back to the session's HRW winner —
        // which now succeeds. The winner is named like the fake it replaces, so
        // the hash sees the identical key.
        BackendException transientFailure = new BackendException(BackendException.TYPE_NETWORK, "boom");
        FailingBackend flakyWinner =
                new FailingBackend(winner, TestData.response("m"), null, List.of(transientFailure));
        FailingBackend flakyOther = new FailingBackend(
                "A".equals(winner) ? "B" : "A", TestData.response("m"), null, List.of(transientFailure));
        Router router = Router.resilient(
                Map.of("m", List.of(flakyWinner, flakyOther)),
                lb,
                new ResilienceConfig(
                        new RetryPolicy(2, 1, 1, 0.0), UpstreamHealth.disabled(), DefaultRetryClassifier.INSTANCE),
                CircuitBreaker.disabled());
        ChatRequest sticky =
                TestData.request("m").withMetaEntry(SessionAffinityLoadBalancer.META_SESSION_ID, stickySession);
        ChatResponse response = router.complete(sticky);
        assertEquals("resp-m", response.id());
        assertEquals(
                2,
                flakyWinner.completeCalls.size(),
                "attempt-0 HRW pick + the all-tried re-pick both chose the winner");
        assertEquals(1, flakyOther.completeCalls.size(), "the retry walk visited the other candidate exactly once");
    }
}
