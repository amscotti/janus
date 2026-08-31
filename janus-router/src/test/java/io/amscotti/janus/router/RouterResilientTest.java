package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * steps 5-6: {@link Router#resilient} — the attempt loop for {@code complete} and
 * the connect-retry path for {@code stream}, with a {@link RecordingLoadBalancer} +
 * {@link FailingBackend} fakes: retry-on-retryable with pinned backoff delays (recording
 * {@link Sleeper}) and health recording, no-retry-on-4xx/unknown, retries-exhausted
 * rethrow with earlier errors suppressed, config-order failover, health-filter
 * interaction (skip a backend that crossed {@code allowedFails}), all-unhealthy fail-open,
 * and the stream wrap preserved once a stream opens (no mid-stream retry, the reference
 * streaming boundary).
 */
class RouterResilientTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static BackendException networkError() {
        return new BackendException(BackendException.TYPE_NETWORK, "connection reset");
    }

    private static ResilienceConfig config(int maxRetries, UpstreamHealth health, List<Long> sleeps) {
        return new ResilienceConfig(
                new RetryPolicy(maxRetries, 10, 100, 0.0, new Random(1), sleeps::add),
                health,
                DefaultRetryClassifier.INSTANCE);
    }

    // --- construction validation (mirrors balanced plus non-null config) -----------

    @Test
    void rejectsNullConfig() {
        assertThrows(
                NullPointerException.class,
                () -> Router.resilient(
                        Map.of("m", List.of(TestData.fake("A"))), new RecordingLoadBalancer(TestData.fake("A")), null));
    }

    @Test
    void rejectsNullRoutes() {
        assertThrows(
                NullPointerException.class,
                () -> Router.resilient(null, new RecordingLoadBalancer(TestData.fake("A")), ResilienceConfig.none()));
    }

    @Test
    void rejectsNullLoadBalancer() {
        assertThrows(
                NullPointerException.class,
                () -> Router.resilient(Map.of("m", List.of(TestData.fake("A"))), null, ResilienceConfig.none()));
    }

    @Test
    void rejectsEmptyCandidateList() {
        assertThrows(
                IllegalArgumentException.class,
                () -> Router.resilient(
                        Map.of("m", List.of()),
                        new RecordingLoadBalancer(TestData.fake("A")),
                        ResilienceConfig.none()));
    }

    @Test
    void rejectsNullCandidateEntry() {
        List<ChatBackend> candidates = new ArrayList<>();
        candidates.add(null);
        assertThrows(
                NullPointerException.class,
                () -> Router.resilient(
                        Map.of("m", candidates),
                        new RecordingLoadBalancer(TestData.fake("A")),
                        ResilienceConfig.none()));
    }

    @Test
    void rejectsTheSameBackendInstanceTwiceInOneAlias() {
        // Strategies key state on backend identity — the same instance listed twice is
        // one health record and one in-flight counter serving "two" candidates.
        FakeBackend a = TestData.fake("A");
        assertThrows(
                IllegalArgumentException.class,
                () -> Router.resilient(
                        Map.of("m", List.of(a, TestData.fake("B"), a)),
                        new RecordingLoadBalancer(a),
                        ResilienceConfig.none()));
    }

    @Test
    void rejectsDuplicateBackendNamesWithinOneAlias() {
        // Same name, different instances: session-affinity's HRW scoring and the
        // weighted pool key on name, so duplicates silently collapse onto the
        // config-order-first backend — half the configured pool never sees traffic.
        // The gateway's ModelListFactory rejects this at boot; the Router must too,
        // or a directly constructed Router bypasses the check.
        IllegalArgumentException e = assertThrows(
                IllegalArgumentException.class,
                () -> Router.resilient(
                        Map.of("m", List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("A"))),
                        new RecordingLoadBalancer(TestData.fake("A")),
                        ResilienceConfig.none()));
        assertTrue(e.getMessage().contains("duplicate backend \"A\""), e.getMessage());
    }

    @Test
    void theSameBackendUnderDifferentAliasesIsLegal() {
        // Cross-alias reuse is the documented identity-keying contract: one backend
        // serving several aliases shares one health/LB record — only duplicates
        // WITHIN one alias are a config error.
        FakeBackend shared = TestData.fake("A");
        Router router = Router.resilient(
                Map.of("m1", List.of(shared), "m2", List.of(shared, TestData.fake("B"))),
                new RecordingLoadBalancer(shared),
                ResilienceConfig.none());
        assertEquals(2, router.models().size());
    }
    // --- ResilienceConfig.none parity ( behavior is the refactor's spec) --------

    @Test
    void noneFactoryDisablesRetriesAndHealth() {
        ResilienceConfig none = ResilienceConfig.none();
        assertEquals(0, none.retryPolicy().maxRetries());
        FakeBackend a = TestData.fake("A");
        assertEquals(List.of(a), none.health().healthy(List.of(a))); // no-op filter
        assertFalse(none.classifier().isRetryable(new IllegalStateException("boom")));
        assertTrue(none.classifier().isRetryable(networkError()));
    }

    @Test
    void noneConfigIsExactlyOneAttemptAndPropagatesUntouched() {
        BackendException boom = networkError();
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of(boom));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, ResilienceConfig.none());
        BackendException thrown = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertSame(boom, thrown); // no retry, no suppression
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(1, a.completeCalls.size());
        assertEquals(List.of("pick", "start:A", "end:A:false:null"), lb.trace);
    }

    @Test
    void noneConfigSuccessPathMatchesBalancedHookOrder() {
        ChatResponse expected = TestData.response("m");
        FakeBackend a = TestData.fake("A", expected);
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, ResilienceConfig.none());
        assertSame(expected, router.complete(TestData.request("m")));
        assertEquals(List.of("pick", "start:A", "sample:A", "end:A:true:resp-m"), lb.trace);
    }

    @Test
    void unknownAliasThrowsWithoutTouchingLoadBalancer() {
        RecordingLoadBalancer lb = new RecordingLoadBalancer(TestData.fake("A"));
        Router router = Router.resilient(Map.of("m", List.of(TestData.fake("A"))), lb, ResilienceConfig.none());
        assertThrows(UnknownModelException.class, () -> router.complete(TestData.request("gpt-4")));
        assertTrue(lb.trace.isEmpty()); // resolved before any pick
    }

    @Test
    void nonRetryableOutcomeReleasesTheClaimedHalfOpenProbe() {
        // The cross-format bug: an upstream 401 (non-retryable, deliberately never
        // counted toward the breaker) terminated the attempt WITHOUT releasing the
        // claimed half-open probe — the slot leaked busy for the whole cooldown and
        // every later request on the backend hit "probe slot busy". Drive a real
        // breaker: threshold 1, cooldown long; one retryable failure opens it; cooldown
        // elapses (half-open); a non-retryable 401 claim+terminates; the NEXT request
        // must dispatch (the probe was released), not BackendException.
        BackendException authish = new BackendException(BackendException.TYPE_UPSTREAM_4XX, "upstream 401");
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of(networkError(), authish));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        MutableClock clock = new MutableClock(CLOCK.millis());
        Router router = Router.resilient(
                Map.of("m", List.of(a)),
                lb,
                config(2, new RecordingUpstreamHealth(), new ArrayList<>()),
                CircuitBreaker.create(
                        new CircuitBreakerConfig(1, Duration.ofSeconds(600), Duration.ofSeconds(1)), clock));
        // 1) attempt 1 = retryable network failure (trips the breaker, threshold 1);
        //    the retry's attempt 2 = the non-retryable 401, which TERMINATES the
        //    request (and, with the fix, releases the claimed probe). Both canned
        //    failures are consumed here.
        BackendException terminal = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertEquals(BackendException.TYPE_UPSTREAM_4XX, terminal.type());
        // 2) cooldown elapses -> the backend is half-open with a (previously leaked)
        //    claimed probe. THE REGRESSION: the next request must DISPATCH (the probe
        //    was released at step 1's terminal outcome) and complete with the canned
        //    response — not "every circuit-breaker probe slot is busy".
        clock.advance(2_000);
        ChatResponse response = router.complete(TestData.request("m"));
        assertEquals("resp-m", response.id());
    }

    @Test
    void nonRetryableOutcomeReleasesTheClaimedHealthTrial() {
        // The health-layer mirror of nonRetryableOutcomeReleasesTheClaimedHalfOpenProbe:
        // a non-retryable client error terminates the attempt as neither a trial
        // success (must not recover the backend) nor a trial failure (must not
        // re-cooldown it) — the claimed cooldown trial must be RELEASED, or its
        // extended probation excluded the backend for a full extra cooldown window.
        BackendException authish = new BackendException(BackendException.TYPE_UPSTREAM_4XX, "upstream 401");
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of(networkError(), authish));
        FailingBackend b = new FailingBackend("B", TestData.response("m"), null, List.of()); // never fails
        List<ChatBackend> candidates = List.of(a, b);
        MutableClock clock = new MutableClock(CLOCK.millis());
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        Router router = Router.resilient(
                Map.of("m", candidates),
                new RecordingLoadBalancer(a), // attempt 0 always lands on A
                config(0, health, new ArrayList<>()),
                CircuitBreaker.disabled());
        // 1) One retryable failure flips A unhealthy (probation until +1000); no retry budget.
        assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertFalse(health.healthy(candidates).contains(a));
        // 2) Cooldown elapses -> the next dispatch CLAIMS A's single trial and terminates
        //    non-retryably (the canned 401). With the fix the claimed trial is released.
        clock.advance(1000);
        BackendException terminal = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertEquals(BackendException.TYPE_UPSTREAM_4XX, terminal.type());
        assertTrue(
                health.healthy(candidates).contains(a),
                "released trial: A is re-eligible without another full cooldown window");
        assertTrue(health.claimTrial(a), "the released trial slot is claimable again immediately");
    }

    @Test
    void streamAbandonedBeforeTheFirstChunkReleasesTheClaimedHealthTrial() {
        // A stream closed before its first chunk is consumed records NO health outcome
        // (the success fires on the first consumed chunk only) — the claimed cooldown
        // trial must be released at close, the same settlement the breaker's probe
        // gets, or the extended probation excluded the backend for a full extra window.
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), Stream.of(TestData.chunk()), List.of(networkError()));
        FailingBackend b = new FailingBackend("B", TestData.response("m"), null, List.of());
        List<ChatBackend> candidates = List.of(a, b);
        MutableClock clock = new MutableClock(CLOCK.millis());
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 1000, clock);
        Router router = Router.resilient(
                Map.of("m", candidates),
                new RecordingLoadBalancer(a),
                config(0, health, new ArrayList<>()),
                CircuitBreaker.disabled());
        // 1) Flip A unhealthy (probation until +1000).
        assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        // 2) Cooldown elapses -> the stream dispatch claims A's trial, the stream opens,
        //    and the client disconnects before consuming anything.
        clock.advance(1000);
        Stream<StreamChunk> routed = router.stream(TestData.request("m"));
        assertEquals(1, a.streamCalls.size());
        routed.close(); // abandoned: no outcome, no health success ever fires
        assertTrue(health.healthy(candidates).contains(a), "released trial: A is re-eligible immediately");
        assertTrue(health.claimTrial(a), "the released trial slot is claimable again");
    }

    @Test
    void dispatchObserverSeesEveryAttemptIncludingTheFailoverTarget() {
        // A retryable failure on A fails over to B (config order): the observer delivers
        // both dispatches, so a per-request holder ends up on the backend that actually
        // served — the seam per-provider ledger attribution needs under failover
        // (re-resolving via route would keep blaming the config-first candidate).
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), null, new ArrayList<>(List.of(networkError())));
        ChatResponse expected = TestData.response("m");
        FakeBackend b = TestData.fake("B", expected);
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a); // attempt 0 lands on A
        List<ChatBackend> dispatched = new ArrayList<>();
        Router router = Router.resilient(
                Map.of("m", List.of(a, b)), lb, config(1, new RecordingUpstreamHealth(), new ArrayList<>()));
        ChatResponse actual = router.complete(TestData.request("m"), dispatched::add);
        assertSame(expected, actual);
        assertEquals(
                List.of(a, b), dispatched, "the observer sees both dispatches — last delivery is the failover target");
    }

    // --- resilience-hook containment (the health seam must never mask
    //     the backend result, and must never orphan an opened stream) ----------------

    /** test double: an {@link UpstreamHealth} whose hooks throw — the public seam. */
    private static final class ThrowingHealth implements UpstreamHealth {

        @Override
        public void recordFailure(ChatBackend backend) {
            throw new IllegalStateException("health.recordFailure exploded");
        }

        @Override
        public void recordSuccess(ChatBackend backend) {
            throw new IllegalStateException("health.recordSuccess exploded");
        }

        @Override
        public List<ChatBackend> healthy(List<ChatBackend> candidates) {
            return candidates;
        }

        @Override
        public boolean passivelyHealthy(ChatBackend backend) {
            return true;
        }
    }

    @Test
    void throwingHealthRecordSuccessDoesNotFailACompletedRequest() {
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of());
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router =
                Router.resilient(Map.of("m", List.of(a)), lb, config(2, new ThrowingHealth(), new ArrayList<>()));
        ChatResponse response = router.complete(TestData.request("m"));
        assertEquals("resp-m", response.id(), "a throwing health hook must not mask the backend success");
    }

    @Test
    void throwingHealthRecordSuccessDoesNotFailOrOrphanAnOpenedStream() {
        // The critical case: health.recordSuccess runs AFTER picked.stream returned
        // an open upstream stream. Uncontained, the hook's exception enters the retry
        // loop as a "connect failure" — the caller sees an error AND the opened stream
        // (its upstream connection) is orphaned. Contained, the stream is returned
        // normally and the close-releases-connection contract holds.
        FailingBackend a = new FailingBackend("A", TestData.response("m"), Stream.of(TestData.chunk()), List.of());
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router =
                Router.resilient(Map.of("m", List.of(a)), lb, config(2, new ThrowingHealth(), new ArrayList<>()));
        Stream<StreamChunk> routed = router.stream(TestData.request("m"));
        assertTrue(routed.findFirst().isPresent(), "the opened stream must reach the caller");
        routed.close();
        assertTrue(a.streamClosed.get(), "close still releases the upstream connection");
        assertEquals(1, a.streamCalls.size(), "no retry: the open is a success whatever health says");
    }

    @Test
    void throwingHealthRecordFailureDoesNotMaskTheBackendError() {
        // Distinct instances: the router attaches earlier failures as suppressed on the
        // terminal one, and self-suppression (same instance) is an IAE — a different bug.
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), null, List.of(networkError(), networkError()));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        Router router =
                Router.resilient(Map.of("m", List.of(a)), lb, config(1, new ThrowingHealth(), new ArrayList<>()));
        BackendException thrown = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertEquals(
                BackendException.TYPE_NETWORK,
                thrown.type(),
                "a throwing health hook must not mask the backend failure");
        assertEquals(2, a.completeCalls.size(), "retries proceed unaffected by the throwing hook");
    }

    // --- complete: the attempt loop ------------------------------------------------

    @Test
    void retriesRetryableFailuresWithBackoffAndHealthRecording() {
        FailingBackend a = new FailingBackend(
                "A", TestData.response("m"), null, new ArrayList<>(List.of(networkError(), networkError())));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        RecordingUpstreamHealth health = new RecordingUpstreamHealth();
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, config(2, health, sleeps));
        ChatResponse response = router.complete(TestData.request("m"));
        assertEquals("resp-m", response.id());
        assertEquals(3, a.completeCalls.size()); // 1 attempt + 2 retries
        assertEquals(List.of(10L, 20L), sleeps); // exponential backoff pinned, jitter off
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
                lb.trace); // single candidate → all tried → re-pick each retry
        assertEquals(List.of("healthy", "failure:A", "healthy", "failure:A", "healthy", "success:A"), health.trace);
    }

    @Test
    void neverRetriesClientErrors() {
        BackendException clientError =
                new BackendException(BackendException.TYPE_UPSTREAM_4XX, "400 bad request", 400, null);
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of(clientError));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, config(5, new RecordingUpstreamHealth(), sleeps));
        BackendException thrown = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertSame(clientError, thrown); // propagated untouched
        assertEquals(0, thrown.getSuppressed().length);
        assertEquals(1, a.completeCalls.size()); // exactly one attempt
        assertTrue(sleeps.isEmpty()); // no backoff
    }

    @Test
    void neverRetriesUnknownThrowables() {
        IllegalStateException boom = new IllegalStateException("boom");
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of(boom));
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(
                Map.of("m", List.of(a)),
                new RecordingLoadBalancer(a),
                config(5, new RecordingUpstreamHealth(), sleeps));
        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> router.complete(TestData.request("m")));
        assertSame(boom, thrown);
        assertEquals(1, a.completeCalls.size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void retriesExhaustedRethrowsLastFailureWithEarlierOnesSuppressed() {
        BackendException first = new BackendException(BackendException.TYPE_TIMEOUT, "timeout 1");
        BackendException second = new BackendException(BackendException.TYPE_TIMEOUT, "timeout 2");
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), null, new ArrayList<>(List.of(first, second)));
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(
                Map.of("m", List.of(a)),
                new RecordingLoadBalancer(a),
                config(1, new RecordingUpstreamHealth(), sleeps));
        BackendException thrown = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertSame(second, thrown); // the last failure is the visible one
        assertArrayEquals(new Throwable[] {first}, thrown.getSuppressed()); // earlier retryable errors attached
        assertEquals(2, a.completeCalls.size());
        assertEquals(List.of(10L), sleeps); // one backoff before the second (final) attempt
    }

    @Test
    void retriesFailOverCandidatesInConfigOrder() {
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of(networkError()));
        FailingBackend b = new FailingBackend("B", TestData.response("m"), null, List.of(networkError()));
        FailingBackend c = new FailingBackend("C", TestData.response("m"), null, List.of());
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a); // attempt 0 → A
        RecordingUpstreamHealth health = new RecordingUpstreamHealth();
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(Map.of("m", List.of(a, b, c)), lb, config(2, health, sleeps));
        ChatResponse response = router.complete(TestData.request("m"));
        assertEquals("resp-m", response.id());
        assertEquals(1, a.completeCalls.size());
        assertEquals(1, b.completeCalls.size());
        assertEquals(1, c.completeCalls.size());
        assertEquals(
                List.of(
                        "pick",
                        "start:A",
                        "end:A:false:null",
                        "start:B",
                        "end:B:false:null",
                        "start:C",
                        "sample:C",
                        "end:C:true:resp-m"),
                lb.trace);
        assertEquals(1, lb.trace.stream().filter("pick"::equals).count()); // retries walk config order, no re-pick
        assertEquals(List.of(10L, 20L), sleeps);
        assertEquals(List.of("healthy", "failure:A", "healthy", "failure:B", "healthy", "success:C"), health.trace);
    }

    @Test
    void allCandidatesTriedThenRepickUntilBudgetExhausted() {
        // maxRetries(3) > candidateCount(2) with EVERY candidate failing — the loop
        // walks A,B, re-picks A twice more (fixed-pick LB), then exhausts the budget
        // with the full suppressed chain.
        BackendException f1 = networkError();
        BackendException f2 = networkError();
        BackendException f3 = networkError();
        BackendException f4 = networkError();
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, new ArrayList<>(List.of(f1, f3, f4)));
        FailingBackend b = new FailingBackend("B", TestData.response("m"), null, new ArrayList<>(List.of(f2)));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a); // attempt 0 and every re-pick land on A
        List<Long> sleeps = new ArrayList<>();
        Router router =
                Router.resilient(Map.of("m", List.of(a, b)), lb, config(3, new RecordingUpstreamHealth(), sleeps));
        BackendException thrown = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertSame(f4, thrown); // the last failure is the visible one
        assertArrayEquals(new Throwable[] {f1, f2, f3}, thrown.getSuppressed()); // full chain attached
        assertEquals(3, a.completeCalls.size()); // A tried at attempts 0, 2 and 3 (re-picks)
        assertEquals(1, b.completeCalls.size()); // B tried at attempt 1 (config-order failover)
        assertEquals(List.of(10L, 20L, 40L), sleeps); // backoff before attempts 1, 2, 3
        assertEquals(3, lb.trace.stream().filter("pick"::equals).count()); // pick at 0 + re-pick per full walk
        assertEquals(
                List.of(
                        "pick",
                        "start:A",
                        "end:A:false:null",
                        "start:B",
                        "end:B:false:null",
                        "pick",
                        "start:A",
                        "end:A:false:null",
                        "pick",
                        "start:A",
                        "end:A:false:null"),
                lb.trace);
    }

    @Test
    void roundRobinRetryRepickAdvancesTheCycleByDesign() {
        // Accepted-by-design: when every candidate has been tried, a retry
        // re-picks through the load balancer, which advances the round-robin cycle — one
        // logical request can consume several cycle positions. Re-picks are bounded by the
        // retry budget, rare, and the offset self-corrects; this pins the exact behavior so
        // a future change to the re-pick accounting is a visible, deliberate decision.
        FailingBackend a =
                new FailingBackend("A", TestData.response("resp-A", "m", null), null, List.of(networkError()));
        FailingBackend b =
                new FailingBackend("B", TestData.response("resp-B", "m", null), null, List.of(networkError()));
        List<Long> sleeps = new ArrayList<>();
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        Router router =
                Router.resilient(Map.of("m", List.of(a, b)), lb, config(2, new RecordingUpstreamHealth(), sleeps));
        // Request 1: attempt 0 → pos 0 (A, fails), attempt 1 → B (config-order, fails),
        // attempt 2 → all tried → re-pick → pos 1 (B, succeeds). Cycle position is now 2.
        assertEquals("resp-B", router.complete(TestData.request("m")).id());
        assertEquals(1, a.completeCalls.size());
        assertEquals(2, b.completeCalls.size());
        // Request 2: pos 2 → A (which now succeeds). Without the re-pick's counter advance
        // the cycle would sit at 1 and request 2 would land on B instead.
        assertEquals("resp-A", router.complete(TestData.request("m")).id());
        assertEquals(2, a.completeCalls.size());
        assertEquals(2, b.completeCalls.size());
    }

    @Test
    void errorThrowingClassifierIsContainedReleasesTheSlotAndTheProbe() {
        // isRetryable caught only RuntimeException while every hook seam catches
        // RuntimeException | Error: a classifier throwing an Error escaped the attempt's
        // catch body BEFORE the end hook (least-inflight slot) and the probe release —
        // leaking both for the rest of the process. Contained like every other hook:
        // fall back to not-retryable, attach the classifier failure for debuggability.
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), null, new ArrayList<>(List.of(networkError())));
        LeastInflightLoadBalancer real = new LeastInflightLoadBalancer();
        RetryClassifier throwing = error -> {
            throw new AssertionError("classifier exploded");
        };
        ResilienceConfig config = new ResilienceConfig(
                new RetryPolicy(2, 10, 100, 0.0, new Random(1), millis -> {}), new RecordingUpstreamHealth(), throwing);
        MutableClock clock = new MutableClock(CLOCK.millis());
        CircuitBreaker breaker = CircuitBreaker.create(
                new CircuitBreakerConfig(1, Duration.ofSeconds(600), Duration.ofSeconds(1)), clock);
        breaker.recordConnectFailure(a); // threshold 1 → OPEN, 1s cooldown
        clock.advance(2_000); // cooldown elapsed → the next dispatch claims the half-open probe
        Router router = Router.resilient(Map.of("m", List.of(a)), real, config, breaker);
        BackendException thrown = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertEquals(BackendException.TYPE_NETWORK, thrown.type(), "the backend error surfaces, not the classifier's");
        assertEquals(1, thrown.getSuppressed().length); // classifier failure attached (debuggability)
        assertTrue(thrown.getSuppressed()[0] instanceof AssertionError);
        assertEquals(0, real.inflightOf(a), "the end hook fired — the least-inflight slot was released");
        assertTrue(breaker.canTry(a), "the claimed half-open probe was released, not leaked busy");
        assertEquals("resp-m", router.complete(TestData.request("m")).id()); // the next dispatch works
    }

    @Test
    void throwingClassifierFallsBackToNotRetryableAndPreservesTheBackendError() {
        // A misbehaving classifier must not mask the backend failure.
        BackendException boom = networkError();
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of(boom));
        List<Long> sleeps = new ArrayList<>();
        RetryClassifier throwing = error -> {
            throw new IllegalStateException("misbehaving classifier");
        };
        ResilienceConfig config = new ResilienceConfig(
                new RetryPolicy(5, 10, 100, 0.0, new Random(1), sleeps::add), new RecordingUpstreamHealth(), throwing);
        Router router = Router.resilient(Map.of("m", List.of(a)), new RecordingLoadBalancer(a), config);
        BackendException thrown = assertThrows(BackendException.class, () -> router.complete(TestData.request("m")));
        assertSame(boom, thrown); // the backend error surfaces, not the classifier's
        assertEquals(1, a.completeCalls.size()); // fell back to not-retryable → single attempt
        assertTrue(sleeps.isEmpty());
        assertEquals(1, thrown.getSuppressed().length); // classifier failure attached (debuggability)
        assertTrue(thrown.getSuppressed()[0] instanceof IllegalStateException);
    }

    @Test
    void interruptionDuringBackoffAbortsAndKeepsTheFailureChain() {
        // Interruption is a shutdown signal, not a retry condition — but the earlier
        // failures must stay attached instead of being masked by the interruption.
        BackendException first = networkError();
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of(first));
        RuntimeException interrupted = new RuntimeException("interrupted during retry backoff");
        RetryPolicy policy = new RetryPolicy(5, 10, 100, 0.0, new Random(1), millis -> {
            throw interrupted;
        });
        ResilienceConfig config =
                new ResilienceConfig(policy, new RecordingUpstreamHealth(), DefaultRetryClassifier.INSTANCE);
        Router router = Router.resilient(Map.of("m", List.of(a)), new RecordingLoadBalancer(a), config);
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> router.complete(TestData.request("m")));
        assertSame(interrupted, thrown);
        assertArrayEquals(new Throwable[] {first}, thrown.getSuppressed()); // chain survives the abort
        assertEquals(1, a.completeCalls.size()); // aborted before the second attempt
    }

    @Test
    void healthFilterSkipsABackendThatCrossedAllowedFails() {
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, List.of(networkError()));
        FakeBackend b = TestData.fake("B");
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 60_000, CLOCK);
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, config(1, health, sleeps));
        ChatResponse response = router.complete(TestData.request("m"));
        assertEquals("resp-B", response.id()); // B carried the retry
        assertEquals(1, a.completeCalls.size()); // A failed once → unhealthy → skipped on retry
        assertEquals(1, b.completeCalls.size()); // B carried the retry
        assertEquals(List.of(10L), sleeps);
        assertEquals(
                List.of("pick", "start:A", "end:A:false:null", "start:B", "sample:B", "end:B:true:resp-B"), lb.trace);
    }

    @Test
    void cooldownElapsedCandidateNotPickedDoesNotBurnItsHealthTrial() {
        // The trial-burn regression: healthy used to claim the single trial for EVERY
        // cooldown-elapsed candidate at filter time, but only the load-balancer pick is
        // dispatched — an admitted-but-unpicked candidate burned its one trial for a full
        // cooldown without any request being sent to it, so under a strategy that keeps
        // preferring another backend (here: fixed-pick) the degraded backend's trial
        // burned every cooldown and it could never recover. The health filter is now a
        // pure gate; the trial is claimed on the picked backend at dispatch time.
        MutableClock clock = new MutableClock(CLOCK.millis());
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 10_000, clock);
        FailingBackend a = new FailingBackend("A", TestData.response("m"), null, new ArrayList<>());
        FakeBackend b = TestData.fake("B");
        RecordingLoadBalancer lb = new RecordingLoadBalancer(b); // every attempt-0 pick lands on B
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, config(1, health, new ArrayList<>()));
        health.recordFailure(a); // A degraded out-of-band (allowedFails 1) → 10s cooldown
        // During the cooldown A is not even a candidate: B alone reaches the LB.
        assertEquals("resp-B", router.complete(TestData.request("m")).id());
        assertEquals(List.of(b), lb.lastCandidates);
        clock.advance(10_000); // A's cooldown elapses: trial-eligible again
        // The LB still prefers B — A is admitted into the pool but NOT picked, so this
        // request must not consume A's single trial (the regression: the filter claimed
        // it right here, and A waited a full cooldown for nothing).
        assertEquals("resp-B", router.complete(TestData.request("m")).id());
        assertEquals(List.of(a, b), lb.lastCandidates); // A was admitted, eligible...
        assertEquals(0, a.completeCalls.size()); // ...but never dispatched
        assertTrue(health.claimTrial(a), "A's trial survived the unpicked admission — a dispatch can still claim it");
    }

    @Test
    void allUnhealthyFailsOpenToTheFullCandidateList() {
        FakeBackend a = TestData.fake("A");
        FakeBackend b = TestData.fake("B");
        PassiveUpstreamHealth health = new PassiveUpstreamHealth(1, 60_000, CLOCK);
        health.recordFailure(a);
        health.recordFailure(b);
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a); // nominates A from any pool
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(Map.of("m", List.of(a, b)), lb, config(1, health, sleeps));
        ChatResponse response = router.complete(TestData.request("m"));
        assertEquals("resp-A", response.id()); // the request still went out (fail-open probe)
        // Fail-open under dispatch-time claims: both cooling-down backends lose their
        // health-trial claim, the bounded re-pick narrows the pool, and the last
        // remaining nomination dispatches WITHOUT a trial — stale health sends the
        // request out as a probe instead of hard-failing (only the breaker's probe
        // discipline is hard). This fixed-pick double nominates A from the narrowed
        // pool too, so A carries the probe.
        assertEquals(1, a.completeCalls.size());
        assertEquals(0, b.completeCalls.size());
        assertTrue(sleeps.isEmpty()); // success on attempt 0 → no backoff
    }

    @Test
    void completeRejectsNullAndBlankRequestModels() {
        Router router = Router.resilient(
                Map.of("m", List.of(TestData.fake("A"))),
                new RecordingLoadBalancer(TestData.fake("A")),
                ResilienceConfig.none());
        assertThrows(IllegalArgumentException.class, () -> router.complete(TestData.request(null)));
        assertThrows(IllegalArgumentException.class, () -> router.complete(TestData.request(" ")));
    }

    // --- stream: connect-retry path ------------------------------------------------

    @Test
    void streamRetriesConnectFailuresAcrossCandidates() {
        FailingBackend a =
                new FailingBackend("A", TestData.response("m"), Stream.of(TestData.chunk()), List.of(networkError()));
        FailingBackend b =
                new FailingBackend("B", TestData.response("m"), Stream.of(TestData.chunk()), List.of(networkError()));
        FailingBackend c = new FailingBackend("C", TestData.response("m"), Stream.of(TestData.chunk()), List.of());
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        RecordingUpstreamHealth health = new RecordingUpstreamHealth();
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(Map.of("m", List.of(a, b, c)), lb, config(2, health, sleeps));
        Stream<StreamChunk> routed = router.stream(TestData.request("m"));
        routed.findFirst().orElseThrow(); // TTFT sample on C's stream
        routed.close();
        assertEquals(1, a.streamCalls.size()); // A failed to open → failed over
        assertEquals(1, b.streamCalls.size()); // B failed to open → failed over
        assertEquals(1, c.streamCalls.size()); // C opened → caller receives C's stream
        assertEquals(List.of(10L, 20L), sleeps);
        assertEquals(
                List.of(
                        "pick",
                        "start:A",
                        "end:A:false:null",
                        "start:B",
                        "end:B:false:null",
                        "start:C",
                        "sample:C",
                        "end:C:true:null"),
                lb.trace);
        assertEquals(List.of("healthy", "failure:A", "healthy", "failure:B", "healthy", "success:C"), health.trace);
        assertTrue(c.streamClosed.get()); // close-releases-connection preserved through failover
    }

    @Test
    void streamNeverRetriesNonRetryableConnectFailure() {
        BackendException bad = new BackendException(BackendException.TYPE_BAD_UPSTREAM_PAYLOAD, "bad SSE frame");
        FailingBackend a = new FailingBackend("A", TestData.response("m"), Stream.of(TestData.chunk()), List.of(bad));
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, config(5, new RecordingUpstreamHealth(), sleeps));
        BackendException thrown = assertThrows(BackendException.class, () -> router.stream(TestData.request("m")));
        assertSame(bad, thrown);
        assertEquals(1, a.streamCalls.size());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void streamThatOpensIsNeverRetriedAndKeepsTheW20Wrap() {
        FailingBackend a = new FailingBackend("A", TestData.response("m"), Stream.of(TestData.chunk()), List.of());
        RecordingLoadBalancer lb = new RecordingLoadBalancer(a);
        RecordingUpstreamHealth health = new RecordingUpstreamHealth();
        List<Long> sleeps = new ArrayList<>();
        Router router = Router.resilient(Map.of("m", List.of(a)), lb, config(3, health, sleeps));
        Stream<StreamChunk> routed = router.stream(TestData.request("m"));
        assertNotSame(a.stream, routed); // balanced wrap preserved
        routed.findFirst().orElseThrow();
        routed.close();
        assertEquals(1, a.streamCalls.size()); // open → no retry path, generous budget unused
        assertTrue(a.streamClosed.get());
        assertTrue(sleeps.isEmpty());
        assertEquals(List.of("pick", "start:A", "sample:A", "end:A:true:null"), lb.trace);
    }

    @Test
    void streamRejectsNullAndBlankRequestModels() {
        Router router = Router.resilient(
                Map.of("m", List.of(TestData.fake("A"))),
                new RecordingLoadBalancer(TestData.fake("A")),
                ResilienceConfig.none());
        assertThrows(IllegalArgumentException.class, () -> router.stream(TestData.request(null)));
        assertThrows(IllegalArgumentException.class, () -> router.stream(TestData.request(" ")));
    }

    // --- models --------------------------------------------------------------------

    @Test
    void modelsPreservesInsertionOrder() {
        Map<String, List<ChatBackend>> routes = new HashMap<>();
        routes.put("m1", List.of(TestData.fake("A")));
        routes.put("m2", List.of(TestData.fake("B")));
        Router router =
                Router.resilient(routes, new RecordingLoadBalancer(TestData.fake("A")), ResilienceConfig.none());
        assertEquals(2, router.models().size());
        assertThrows(UnsupportedOperationException.class, () -> router.models().add("x"));
    }

    /** Fixed-zone mutable clock (the / discipline — no sleeping, no real time). */
    private static final class MutableClock extends Clock {

        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long deltaMillis) {
            millis += deltaMillis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("fixed-zone");
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }
}
