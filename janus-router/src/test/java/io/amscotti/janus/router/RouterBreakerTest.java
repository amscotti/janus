package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Spliterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * steps 4-5: {@link Router#resilient} wired to a real {@link CircuitBreaker} — the
 * attempt-loop integration (OPEN backends skipped with the LB seeing only {@code
 * canTry} backends, all-open fail-open probe on the first candidate, connect failures
 * accumulate across requests and trip at the threshold, the retry walk respects {@code
 * canTry}, half-open probes end-to-end) and the streaming-safe stream wrap (zero-chunk
 * failures trip, mid-stream failures don't, clean exhaustion resets, early close records
 * nothing and releases the probe). Fake backends and fake streams, fixed clock, no
 * network, no real time. The 4-arg path with {@code CircuitBreaker.disabled} must
 * reproduce the 3-arg behavior (pinned here and by the unchanged tests).
 */
class RouterBreakerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** Test clock whose {@code advance} drives the breaker's rolling window and cooldown. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private static BackendException networkError() {
        return new BackendException(BackendException.TYPE_NETWORK, "connection reset");
    }

    private static CircuitBreaker breaker(int threshold, long windowMillis, long cooldownMillis, MutableClock clock) {
        return CircuitBreaker.create(
                new CircuitBreakerConfig(threshold, Duration.ofMillis(windowMillis), Duration.ofMillis(cooldownMillis)),
                clock);
    }

    private static ResilienceConfig config(int maxRetries, UpstreamHealth health, List<Long> sleeps) {
        return new ResilienceConfig(
                new RetryPolicy(maxRetries, 10, 100, 0.0, new Random(1), sleeps::add),
                health,
                DefaultRetryClassifier.INSTANCE);
    }

    /** Load-balancer double that picks the first candidate and records what it saw. */
    private static final class FirstCandidateLoadBalancer implements LoadBalancer {

        final List<String> trace = new ArrayList<>();
        List<ChatBackend> lastCandidates = List.of();

        @Override
        public String name() {
            return "first-candidate";
        }

        @Override
        public ChatBackend pick(String model, List<ChatBackend> candidates) {
            lastCandidates = candidates;
            trace.add("pick");
            return candidates.getFirst();
        }

        @Override
        public void onRequestStart(String model, ChatBackend backend) {
            trace.add("start:" + backend.name());
        }

        @Override
        public void onLatencySample(String model, ChatBackend backend, long elapsedNanos) {
            trace.add("sample:" + backend.name());
        }

        @Override
        public void onRequestEnd(String model, ChatBackend backend, boolean success, ChatResponse response) {
            trace.add("end:" + backend.name() + ":" + success + ":" + (response == null ? "null" : response.id()));
        }
    }

    /**
     * Load-balancer double that picks candidates by config-order index from a fixed
     * sequence (clamped to the candidate list size), so tests can drive "LB picks the
     * second candidate" and "LB picks the first on the next request".
     */
    private static final class PickSequenceLoadBalancer implements LoadBalancer {

        private final List<Integer> picks;
        private int next;
        final List<String> trace = new ArrayList<>();

        PickSequenceLoadBalancer(List<Integer> picks) {
            this.picks = picks;
        }

        @Override
        public String name() {
            return "pick-sequence";
        }

        @Override
        public ChatBackend pick(String model, List<ChatBackend> candidates) {
            int index = picks.get(Math.min(next, picks.size() - 1));
            next++;
            trace.add("pick:" + index);
            return candidates.get(Math.min(index, candidates.size() - 1));
        }

        @Override
        public void onRequestStart(String model, ChatBackend backend) {}

        @Override
        public void onLatencySample(String model, ChatBackend backend, long elapsedNanos) {}

        @Override
        public void onRequestEnd(String model, ChatBackend backend, boolean success, ChatResponse response) {}
    }

    /**
     * Load-balancer double whose end hook throws: a misbehaving strategy must
     * not mask the backend result, double-deliver the end hook, or leak the underlying
     * stream on close. Records what it saw before throwing so tests can pin ordering.
     */
    private static class ThrowingEndHookLoadBalancer implements LoadBalancer {

        final List<String> trace = new ArrayList<>();
        private final ChatBackend pickResult;

        ThrowingEndHookLoadBalancer(ChatBackend pickResult) {
            this.pickResult = pickResult;
        }

        @Override
        public String name() {
            return "throwing-end-hook";
        }

        @Override
        public ChatBackend pick(String model, List<ChatBackend> candidates) {
            trace.add("pick");
            return pickResult;
        }

        @Override
        public void onRequestStart(String model, ChatBackend backend) {
            trace.add("start");
        }

        @Override
        public void onLatencySample(String model, ChatBackend backend, long elapsedNanos) {
            trace.add("sample");
        }

        @Override
        public void onRequestEnd(String model, ChatBackend backend, boolean success, ChatResponse response) {
            trace.add("end:" + success);
            throw new IllegalStateException("hook boom");
        }
    }

    /** Backend whose stream throws after {@code failAfter} delivered chunks; -1 = clean. */
    private static final class StreamBackend implements ChatBackend {
        private final String name;
        private final int failAfter;
        private final RuntimeException failure;
        final List<ChatRequest> streamCalls = new ArrayList<>();

        StreamBackend(String name, int failAfter, RuntimeException failure) {
            this.name = name;
            this.failAfter = failAfter;
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
            throw new UnsupportedOperationException("complete not used for stream backends");
        }

        @Override
        public Stream<StreamChunk> stream(ChatRequest request) {
            streamCalls.add(request);
            AtomicInteger emitted = new AtomicInteger();
            Spliterator<StreamChunk> spliterator = new Spliterator<>() {
                @Override
                public boolean tryAdvance(Consumer<? super StreamChunk> action) {
                    if (failAfter >= 0 && emitted.get() == failAfter) {
                        throw failure;
                    }
                    if (emitted.get() >= 2) {
                        return false; // clean exhaustion after 2 chunks
                    }
                    emitted.incrementAndGet();
                    action.accept(TestData.chunk());
                    return true;
                }

                @Override
                public Spliterator<StreamChunk> trySplit() {
                    return null;
                }

                @Override
                public long estimateSize() {
                    return 2;
                }

                @Override
                public int characteristics() {
                    return Spliterator.ORDERED;
                }
            };
            return StreamSupport.stream(spliterator, false);
        }
    }

    // --- attempt-loop integration -----------------------------------------------------

    @Test
    void openBackendIsSkippedAndNextCandidateCarriesTheRequest() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a); // A OPEN, cooldown pending
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // failover
        assertEquals(List.of(b), lb.lastCandidates); // the LB saw only canTry backends
        assertEquals(0, a.completeCalls.size());
        assertEquals(1, b.completeCalls.size());
    }

    @Test
    void allOpenFailsOpenWithAProbeToTheFirstCandidate() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a);
        breaker.recordConnectFailure(b); // both OPEN
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-A", router.complete(TestData.request("m")).id()); // fail-open probe
        assertEquals(List.of(a, b), lb.lastCandidates); // health-filtered full list reached the LB
        assertEquals(1, a.completeCalls.size());
        assertEquals(0, b.completeCalls.size());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a)); // probe success recovered A
    }

    @Test
    void connectFailuresAccumulateAcrossRequestsAndTripAtTheThreshold() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(2, 60_000, 30_000, clock);
        FailingBackend a = new FailingBackend(
                "A", TestData.response("m"), null, new ArrayList<>(List.of(networkError(), networkError())));
        FakeBackend b = TestData.fake("B");
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertThrows(BackendException.class, () -> router.complete(TestData.request("m"))); // 1/2
        assertThrows(BackendException.class, () -> router.complete(TestData.request("m"))); // 2/2 → OPEN
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // A skipped → B
        assertEquals(2, a.completeCalls.size());
        assertEquals(1, b.completeCalls.size());
    }

    @Test
    void retryWalkRespectsCanTryForABackendThatTripsMidRequest() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), null, new ArrayList<>(List.of(networkError())));
        FakeBackend b = TestData.fake("B");
        List<Long> sleeps = new ArrayList<>();
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(
                Map.of("m", List.of(a, b)), lb, config(1, new RecordingUpstreamHealth(), sleeps), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // retry skipped A
        assertEquals(1, a.completeCalls.size());
        assertEquals(1, b.completeCalls.size());
        assertEquals(List.of(10L), sleeps);
    }

    @Test
    void halfOpenProbeSuccessClosesTheBreaker() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a); // OPEN at T0
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // blocked
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a));
        clock.advance(1000); // cooldown elapsed
        assertEquals("resp-A", router.complete(TestData.request("m")).id()); // exactly one probe
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a)); // probe success → closed
        assertEquals(1, a.completeCalls.size());
    }

    @Test
    void halfOpenProbeFailureReopensWithFreshCooldown() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), null, new ArrayList<>(List.of(networkError())));
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a); // OPEN at T0
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // blocked
        clock.advance(1000); // cooldown elapsed
        assertThrows(BackendException.class, () -> router.complete(TestData.request("m"))); // probe to A failed
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a)); // probe failure re-opened
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // fresh cooldown → A denied
        assertEquals(1, a.completeCalls.size()); // only the probe was dispatched to A
    }

    @Test
    void cooldownElapsedBackendsNotPickedAreNotWedgedByTheProbeClaim() {
        // C1 regression: two OPEN backends both past cooldown; the LB picks the second.
        // The first is gated (eligible) but never claimed — the claim belongs to the
        // dispatched backend only, so the first stays dispatchable on the next request.
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a); // both OPEN at T0
        breaker.recordConnectFailure(b);
        clock.advance(1000); // cooldown elapsed for both
        PickSequenceLoadBalancer lb = new PickSequenceLoadBalancer(List.of(1, 0)); // second, then first
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // probe → B
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(b)); // probe success closed B
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a)); // A was gated, not claimed
        assertTrue(breaker.canTry(a)); // A's probe slot did not leak: still eligible
        assertEquals("resp-A", router.complete(TestData.request("m")).id()); // next request probes A
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a)); // A recovered too
    }

    @Test
    void openBackendBehindAHealthyCandidateIsNotWedgedWhenTheLbPicksTheHealthyOne() {
        // C1 regression: a healthy CLOSED backend listed before a cooldown-elapsed OPEN
        // backend gets the dispatch; the OPEN backend's claim must not leak (it is never
        // claimed during filtering), so it stays eligible and is dispatched later.
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend healthy = TestData.fake("B");
        FakeBackend open = TestData.fake("A");
        breaker.recordConnectFailure(open); // OPEN at T0
        clock.advance(1000); // cooldown elapsed
        PickSequenceLoadBalancer lb = new PickSequenceLoadBalancer(List.of(0, 1)); // healthy, then open
        Router router = Router.resilient(Map.of("m", List.of(healthy, open)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // LB picked the healthy candidate
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(open)); // not claimed by the filter
        assertTrue(breaker.canTry(open)); // no leaked probe claim
        assertEquals("resp-A", router.complete(TestData.request("m")).id()); // probe reaches the OPEN backend
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(open));
    }

    @Test
    void failOpenSkipsABusyProbeSlotAndProbesTheNextCandidate() {
        // Regression: the all-open fail-open path must not double-dispatch onto a
        // backend whose probe slot is already claimed (a concurrent probe in flight) —
        // the bounded re-pick moves to the next claimable candidate.
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a);
        breaker.recordConnectFailure(b); // both OPEN, cooldown pending → gate denies both
        assertTrue(breaker.claimProbe(a)); // a concurrent probe is in flight on A
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // re-pick skipped A → B
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a)); // A's probe untouched
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(b)); // B's probe succeeded
        assertEquals(0, a.completeCalls.size()); // no double dispatch onto A's busy slot
    }

    @Test
    void allOpenFailOpenProbeFailureReopensWithAFreshCooldown() {
        // The all-open fail-open probe's failure re-trips the breaker with a fresh
        // cooldown (the dispatch-time claim moved OPEN → HALF_OPEN first) — the plan's
        // "probe outcome re-trips or recovers" holds for fail-open probes too.
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), null, new ArrayList<>(List.of(networkError())));
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a);
        breaker.recordConnectFailure(b); // both OPEN at T0
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        clock.advance(500); // still inside the original cooldown
        assertThrows(BackendException.class, () -> router.complete(TestData.request("m"))); // fail-open probe → A
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a)); // probe failure re-tripped A
        clock.advance(500); // the original cooldown (from T0) would have elapsed
        assertFalse(breaker.canTry(a)); // ... but the probe failure reset it: fresh cooldown from T0+500
        assertTrue(breaker.canTry(b)); // B was never probed → still OPEN on the original schedule
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // B's cooldown elapsed → probe
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(b));
    }

    @Test
    void allProbeSlotsBusyFailsTheRequestInsteadOfDoubleDispatching() {
        // Boundary: when every candidate's probe slot is busy, the request fails with a
        // network-type BackendException rather than double-dispatching onto a busy probe
        // (exactly-one-probe discipline preserved).
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a);
        breaker.recordConnectFailure(b); // both OPEN, cooldown pending
        assertTrue(breaker.claimProbe(a));
        assertTrue(breaker.claimProbe(b)); // both probe slots busy
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        BackendException e = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertEquals(BackendException.TYPE_NETWORK, e.type());
        assertEquals(0, a.completeCalls.size()); // no double dispatch onto a busy probe
        assertEquals(0, b.completeCalls.size());
    }

    @Test
    void allProbeSlotsBusyIsRetriedOnceASlotFrees() {
        // "Every probe slot busy" is transient contention — probes complete
        // momentarily — so it must ride the same retry budget as a dispatch failure instead
        // of failing the request hard (with a retryable-flagged exception that was never
        // actually retried). Here the backoff releases A's probe, so the retry dispatches
        // and succeeds.
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a);
        breaker.recordConnectFailure(b); // both OPEN, cooldown pending
        assertTrue(breaker.claimProbe(a));
        assertTrue(breaker.claimProbe(b)); // both probe slots busy → attempt 0 cannot dispatch
        List<Long> sleeps = new ArrayList<>();
        RetryPolicy retry = new RetryPolicy(2, 10, 100, 0.0, new Random(1), millis -> {
            sleeps.add(millis);
            breaker.releaseProbe(a); // the contention clears during backoff
        });
        ResilienceConfig config =
                new ResilienceConfig(retry, new RecordingUpstreamHealth(), DefaultRetryClassifier.INSTANCE);
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, config, breaker);
        assertEquals("resp-A", router.complete(TestData.request("m")).id()); // retried after backoff
        assertEquals(1, a.completeCalls.size()); // dispatched exactly once, on the retry
        assertEquals(0, b.completeCalls.size());
        assertEquals(List.of(10L), sleeps); // one backoff before the retry
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a)); // probe success recovered A
    }

    @Test
    void clientErrorsDoNotCountTowardHealthOrTheBreaker() {
        // A 4xx is the client's fault, not the upstream's — the classifier
        // never retries it ("a credentials or request-shape problem will not fix itself"),
        // and it must not soft-exclude a healthy upstream or trip the breaker. A burst of
        // client 400s must leave the backend healthy and the breaker CLOSED.
        MutableClock clock = new MutableClock(T0);
        BackendException badRequest =
                new BackendException(BackendException.TYPE_UPSTREAM_4XX, "400 bad request", 400, null);
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), null, new ArrayList<>(List.of(badRequest, badRequest)));
        FakeBackend b = TestData.fake("B");
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 60_000, clock); // allowed-fails 1
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock); // failure threshold 1
        List<Long> sleeps = new ArrayList<>();
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, config(5, health, sleeps), breaker);
        assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertThrows(BackendException.class, () -> router.complete(TestData.request("m"))); // 2nd client error
        assertEquals(2, a.completeCalls.size()); // each request was exactly one attempt
        assertTrue(sleeps.isEmpty()); // never retried → no backoff
        assertEquals(List.of(a, b), health.healthy(List.of(a, b))); // allowed-fails(1)+1 → still healthy
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a)); // threshold(1) never reached
        assertTrue(breaker.canTry(a));
    }

    // --- streaming-safe wrap outcomes -------------------------------------------------

    @Test
    void zeroChunkStreamFailureTripsTheBreaker() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        StreamBackend a = new StreamBackend("A", 0, networkError()); // throws before the first chunk
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, ResilienceConfig.none(), breaker);
        try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
            assertThrows(BackendException.class, () -> routed.forEach(chunk -> {}));
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a)); // signal 2 before first chunk trips
        assertEquals(1, a.streamCalls.size());
    }

    @Test
    void connectTimeStreamFailureTripsTheBreakerAndTheRetryWalkSkipsIt() {
        // Signal 1 from the stream connect path — stream itself throws (nothing
        // delivered). The breaker trips on it, and the retry walk skips the tripped
        // backend on the next attempt (failover to the healthy candidate).
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), null, new ArrayList<>(List.of(networkError())));
        StreamBackend b = new StreamBackend("B", -1, null); // clean 2-chunk stream
        List<Long> sleeps = new ArrayList<>();
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(
                Map.of("m", List.of(a, b)), lb, config(1, new RecordingUpstreamHealth(), sleeps), breaker);
        try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
            assertEquals(2, routed.toList().size()); // retry walked past the tripped A → B
        }
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(a)); // connect failure tripped A
        assertEquals(1, a.streamCalls.size());
        assertEquals(1, b.streamCalls.size());
        assertEquals(List.of(10L), sleeps);
    }

    @Test
    void midStreamFailureAfterPartialDeliveryDoesNotTrip() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 30_000, clock);
        StreamBackend a = new StreamBackend("A", 1, networkError()); // throws after 1 chunk
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, ResilienceConfig.none(), breaker);
        try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
            assertThrows(BackendException.class, () -> routed.forEach(chunk -> {}));
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a)); // transient → no count
        assertEquals(1, a.streamCalls.size());
    }

    @Test
    void cleanStreamExhaustionResetsTheBreaker() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        StreamBackend a = new StreamBackend("A", -1, null); // clean 2-chunk stream
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a); // OPEN
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // blocked
        clock.advance(1000); // cooldown elapsed → A becomes the half-open probe target
        try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
            assertEquals(2, routed.toList().size());
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(a)); // clean exhaustion → recordSuccess
        assertEquals(1, a.streamCalls.size());
    }

    @Test
    void earlyCloseRecordsNothingAndReleasesTheHalfOpenProbe() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        StreamBackend a = new StreamBackend("A", -1, null);
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a); // OPEN
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // blocked
        clock.advance(1000);
        Stream<StreamChunk> routed = router.stream(TestData.request("m"));
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a)); // probe claimed at dispatch
        routed.close(); // client disconnect, nothing consumed
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a)); // no success/failure recorded
        assertTrue(breaker.canTry(a)); // probe slot released → no leak, next dispatch re-probes
        assertEquals(1, a.streamCalls.size());
    }

    @Test
    void midStreamProbeFailureReleasesTheSlotAndStaysHalfOpen() {
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        StreamBackend a = new StreamBackend("A", 1, networkError()); // yields 1 chunk then dies
        FakeBackend b = TestData.fake("B");
        breaker.recordConnectFailure(a); // OPEN
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // blocked
        clock.advance(1000);
        try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
            assertThrows(BackendException.class, () -> routed.forEach(chunk -> {}));
        }
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state(a)); // transient: no re-open
        assertTrue(breaker.canTry(a)); // probe released → another probe can be dispatched
    }

    @Test
    void wrapStreamDoesNotAdvertiseSubsizedCharacteristics() {
        // The router's wrap spliterator always returns null from trySplit, so
        // it must not advertise SUBSIZED — a contract lie for any consumer that parallelizes
        // or reads characteristics. SIZED is retained because estimateSize delegates.
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        Spliterator<StreamChunk> sized = StreamSupport.stream(
                        new Spliterator<StreamChunk>() {
                            @Override
                            public boolean tryAdvance(Consumer<? super StreamChunk> action) {
                                return false; // empty stream: characteristics are all that matter here
                            }

                            @Override
                            public Spliterator<StreamChunk> trySplit() {
                                return null;
                            }

                            @Override
                            public long estimateSize() {
                                return 2;
                            }

                            @Override
                            public int characteristics() {
                                return Spliterator.ORDERED | Spliterator.SIZED | Spliterator.SUBSIZED;
                            }
                        },
                        false)
                .spliterator();
        FakeBackend a = TestData.fake("A", TestData.response("A"), StreamSupport.stream(sized, false));
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, ResilienceConfig.none(), breaker);
        try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
            int characteristics = routed.spliterator().characteristics();
            assertEquals(0, characteristics & Spliterator.SUBSIZED); // never advertised
            assertTrue((characteristics & Spliterator.SIZED) != 0); // SIZED still delegated
        }
    }

    // --- LB hook containment ---------------------------------------------------------

    @Test
    void throwingEndHookDoesNotMaskTheBackendResultAndFiresExactlyOnce() {
        // (1) An end hook that throws on the success path must not (a) replace the
        // backend response with a 500 for a request the upstream served successfully, or
        // (b) trigger a second end(false, null) delivery from the catch path — a double
        // delivery would undercount least-inflight state (decrement twice → negative).
        FakeBackend a = TestData.fake("A");
        LeastInflightLoadBalancer real = new LeastInflightLoadBalancer();
        LoadBalancer throwing = new LoadBalancer() {
            @Override
            public String name() {
                return "least-inflight-throwing-end";
            }

            @Override
            public ChatBackend pick(String model, List<ChatBackend> candidates) {
                return real.pick(model, candidates);
            }

            @Override
            public void onRequestStart(String model, ChatBackend backend) {
                real.onRequestStart(model, backend);
            }

            @Override
            public void onLatencySample(String model, ChatBackend backend, long elapsedNanos) {
                real.onLatencySample(model, backend, elapsedNanos);
            }

            @Override
            public void onRequestEnd(String model, ChatBackend backend, boolean success, ChatResponse response) {
                real.onRequestEnd(model, backend, success, response); // release the slot first
                throw new IllegalStateException("hook boom");
            }
        };
        Router router = Router.resilient(Map.of("m", List.of(a)), throwing, ResilienceConfig.none());
        assertEquals("resp-A", router.complete(TestData.request("m")).id()); // the result is NOT masked
        assertEquals(0, real.inflightOf(a)); // exactly one start/end pair — no double delivery
    }

    @Test
    void throwingStartHookDoesNotPreventTheDispatch() {
        // A throwing onRequestStart must not abort the request it was observing —
        // the dispatch proceeds and the end hook still fires exactly once.
        FakeBackend a = TestData.fake("A");
        ThrowingEndHookLoadBalancer lb = new ThrowingEndHookLoadBalancer(a) {
            @Override
            public void onRequestStart(String model, ChatBackend backend) {
                trace.add("start");
                throw new IllegalStateException("start boom");
            }
        };
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, ResilienceConfig.none());
        assertEquals("resp-A", router.complete(TestData.request("m")).id());
        assertEquals(1, a.completeCalls.size()); // dispatched despite the throwing hook
        assertEquals(List.of("pick", "start", "sample", "end:true"), lb.trace);
    }

    @Test
    void throwingEndHookOnStreamCloseStillClosesTheUnderlyingStream() {
        // (2) A throwing end hook on stream close must not leak the upstream
        // connection — underlying.close runs in a finally independent of the hook — and
        // the hook exception must not escape Stream.close.
        FakeBackend a = TestData.fake("A", TestData.response("A"), Stream.of(TestData.chunk()));
        ThrowingEndHookLoadBalancer lb = new ThrowingEndHookLoadBalancer(a);
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, ResilienceConfig.none());
        Stream<StreamChunk> routed = router.stream(TestData.request("m"));
        routed.close(); // must not throw
        assertTrue(a.streamClosed.get()); // close-releases-connection preserved despite the hook
        assertEquals(List.of("pick", "start", "end:true"), lb.trace);
    }

    @Test
    void throwingLatencySampleOnStreamDoesNotAbortTheStream() {
        // A throwing TTFT sample on the first consumed element must not abort
        // the stream — the chunk is still delivered and the close path still fires.
        FakeBackend a = TestData.fake("A", TestData.response("A"), Stream.of(TestData.chunk()));
        ThrowingEndHookLoadBalancer base = new ThrowingEndHookLoadBalancer(a);
        LoadBalancer lb = new LoadBalancer() {
            @Override
            public String name() {
                return "throwing-sample";
            }

            @Override
            public ChatBackend pick(String model, List<ChatBackend> candidates) {
                return base.pick(model, candidates);
            }

            @Override
            public void onRequestStart(String model, ChatBackend backend) {
                base.onRequestStart(model, backend);
            }

            @Override
            public void onLatencySample(String model, ChatBackend backend, long elapsedNanos) {
                base.trace.add("sample");
                throw new IllegalStateException("sample boom");
            }

            @Override
            public void onRequestEnd(String model, ChatBackend backend, boolean success, ChatResponse response) {
                base.onRequestEnd(model, backend, success, response);
            }
        };
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, ResilienceConfig.none());
        try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
            assertEquals(1, routed.toList().size()); // the chunk is delivered despite the sample throw
        }
        assertEquals(List.of("pick", "start", "sample", "end:true"), base.trace);
        assertTrue(a.streamClosed.get());
    }

    // --- streaming zero-chunk failures feed health -----------------------------------

    @Test
    void zeroChunkStreamFailureFeedsHealthEvenWithTheBreakerDisabled() {
        // A connect-then-die stream (opens, then throws before the first chunk)
        // must feed health exactly like a connect failure — otherwise the breaker-disabled
        // operator disable (threshold 0) would have nothing excluding such a backend, and
        // docs/routing.md's "health and the breaker consume the same per-attempt failure
        // events" would be a lie on the streaming path.
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 60_000, clock); // allowed-fails 1
        StreamBackend a = new StreamBackend("A", 0, networkError()); // dies before the first chunk
        FakeBackend b = TestData.fake("B");
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(
                Map.of("m", List.of(a, b)), lb, config(0, health, new ArrayList<>()), CircuitBreaker.disabled());
        try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
            assertThrows(BackendException.class, () -> routed.forEach(chunk -> {}));
        }
        assertEquals(1, a.streamCalls.size()); // the stream opened once, then died on tryAdvance
        assertFalse(health.healthy(List.of(a, b)).contains(a)); // zero-chunk death excluded A
        assertTrue(health.healthy(List.of(a, b)).contains(b));
    }

    @Test
    void connectThenDieStreamsAccumulateHealthFailuresPastAllowedFailsOne() {
        // The masking regression: health.recordSuccess used to fire at stream OPEN, so
        // every connect-then-die request reset the consecutive-failure counter before
        // the zero-chunk death incremented it — with allowed-fails > 1 (the default is 3)
        // such a backend NEVER flipped unhealthy, contradicting docs/routing.md's
        // "connect-path success is not allowed to mask a zero-chunk death" (the old test
        // only pinned allowedFails=1, where reset-then-fail still crossed the threshold).
        // The health success now fires on the first CONSUMED chunk, so zero-chunk deaths
        // accumulate like connect failures.
        MutableClock clock = new MutableClock(T0);
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(2, 60_000, clock); // allowed-fails 2
        StreamBackend a = new StreamBackend("A", 0, networkError()); // opens, then dies before the first chunk
        StreamBackend b = new StreamBackend("B", -1, null); // clean
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(
                Map.of("m", List.of(a, b)), lb, config(0, health, new ArrayList<>()), CircuitBreaker.disabled());
        for (int i = 0; i < 2; i++) {
            try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
                assertThrows(BackendException.class, () -> routed.forEach(chunk -> {}));
            }
        }
        assertEquals(2, a.streamCalls.size()); // both requests connected, then died zero-chunk
        assertFalse(health.healthy(List.of(a, b)).contains(a), "2 zero-chunk deaths ≥ allowedFails(2) → unhealthy");
        assertTrue(health.healthy(List.of(a, b)).contains(b));
    }

    @Test
    void midStreamFailureStaysHealthNeutral() {
        // A stream that yields >= 1 chunk then dies is transient — health stays
        // green (the backend demonstrably delivered bytes); the connect-path success is not
        // contradicted by a mid-stream failure.
        RecordingUpstreamHealth health = new RecordingUpstreamHealth();
        StreamBackend a = new StreamBackend("A", 1, networkError()); // yields 1 chunk then dies
        FirstCandidateLoadBalancer lb = new FirstCandidateLoadBalancer();
        Router router = Router.resilient(
                Map.of("m", List.of(a)), lb, config(0, health, new ArrayList<>()), CircuitBreaker.disabled());
        try (Stream<StreamChunk> routed = router.stream(TestData.request("m"))) {
            assertThrows(BackendException.class, () -> routed.forEach(chunk -> {}));
        }
        assertEquals(List.of("healthy", "success:A"), health.trace); // connect success only, no failure
    }

    // --- latency exploration vs breaker blocking -------------------------------------

    @Test
    void latencyExplorationProbesABreakerBlockedCandidateOnceItRecovers() {
        // A never-sampled candidate excluded by the breaker during the LB's
        // exploration attempts is not starved — once its cooldown elapses it becomes
        // visible to the LB again and the exploration rule probes it (fresh sample before
        // it would ever compete on EMA).
        MutableClock clock = new MutableClock(T0);
        CircuitBreaker breaker = breaker(1, 60_000, 1000, clock);
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        FakeBackend c = TestData.fake("C");
        breaker.recordConnectFailure(c); // C OPEN, cooldown pending → invisible to the LB
        LatencyBasedLoadBalancer lb = new LatencyBasedLoadBalancer(0.3);
        Router router = Router.resilient(Map.of("m", List.of(a, b, c)), lb, ResilienceConfig.none(), breaker);
        assertEquals("resp-A", router.complete(TestData.request("m")).id()); // exploration → A (first unsampled)
        assertEquals("resp-B", router.complete(TestData.request("m")).id()); // A sampled → B
        clock.advance(1000); // C's cooldown elapsed → eligible again
        assertEquals("resp-C", router.complete(TestData.request("m")).id()); // exploration probes recovered C
        assertEquals(1, c.completeCalls.size());
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(c)); // probe success recovered C
    }

    // --- disabled parity + validation -------------------------------------------------

    @Test
    void disabledBreakerReproducesTheThreeArgPath() {
        FailingBackend a = new FailingBackend(
                "A", TestData.response("m"), null, new ArrayList<>(List.of(networkError(), networkError())));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        RecordingUpstreamHealth health = new RecordingUpstreamHealth();
        List<Long> sleeps = new ArrayList<>();
        Router router =
                Router.resilient(Map.of("m", List.of(a)), lb, config(2, health, sleeps), CircuitBreaker.disabled());
        ChatResponse response = router.complete(TestData.request("m"));
        assertEquals("resp-m", response.id());
        assertEquals(3, a.completeCalls.size());
        assertEquals(List.of(10L, 20L), sleeps);
        assertEquals(List.of("healthy", "failure:A", "healthy", "failure:A", "healthy", "success:A"), health.trace);
        assertEquals(
                List.of(
                        "pick",
                        "start:A",
                        "end:A:false:null",
                        "pick",
                        "start:A",
                        "end:A:false:null",
                        "pick",
                        "start:A",
                        "sample:A",
                        "end:A:true:resp-m"),
                lb.trace);
    }

    @Test
    void rejectsNullBreaker() {
        assertThrows(
                NullPointerException.class,
                () -> Router.resilient(
                        Map.of("m", List.of(TestData.fake("A"))),
                        new RecordingLoadBalancer(TestData.fake("A")),
                        ResilienceConfig.none(),
                        null));
    }
}
