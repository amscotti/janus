package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.DecisionCodecException;
import io.amscotti.janus.core.model.DecisionAnswer;
import io.amscotti.janus.core.model.DecisionQuestion;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

/**
 * The complete-only decision router: alias → candidate list, round-robin
 * first pick, retry/fallback over retryable failures only, unknown-alias
 * typing, and the dispatch observer the gateway's call ledger reads.
 */
class DecisionRouterTest {

    private static DecisionRequest request(String model) {
        return new DecisionRequest(
                model, "state", null, Map.of("q", new DecisionQuestion.NoulQuestion("?", null)), Map.of(), Map.of());
    }

    private static DecisionRequest imageRequest(String model) {
        return new DecisionRequest(
                model,
                "state",
                List.of("aGVsbG8="),
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of());
    }

    private static DecisionResponse response(String model) {
        return new DecisionResponse(model, Map.of("q", new DecisionAnswer.NoulAnswer(0.5)), null, Map.of(), Map.of());
    }

    private static final class FakeBackend implements DecisionBackend {
        private final String name;
        private final boolean vision;
        private final AtomicInteger calls = new AtomicInteger();
        private RuntimeException failure;

        FakeBackend(String name) {
            this(name, false);
        }

        FakeBackend(String name, boolean vision) {
            this.name = name;
            this.vision = vision;
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
        public boolean supportsImages() {
            return vision;
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return response(request.model());
        }
    }

    private static RetryPolicy noSleepPolicy(int maxRetries) {
        return new RetryPolicy(maxRetries, 1, 1, 0.0, RandomGenerator.getDefault(), millis -> {});
    }

    @Test
    void roundRobinsAcrossCandidatesAndReportsDispatch() {
        FakeBackend a = new FakeBackend("a");
        FakeBackend b = new FakeBackend("b");
        DecisionRouter router = new DecisionRouter(Map.of("m", List.of(a, b)));
        AtomicReference<String> dispatched = new AtomicReference<>();

        router.decide(request("m"), backend -> dispatched.set(backend.name()));
        assertEquals("a", dispatched.get());
        router.decide(request("m"), backend -> dispatched.set(backend.name()));
        assertEquals("b", dispatched.get());
        router.decide(request("m"));
        assertEquals(2, a.calls.get());
        assertEquals(1, b.calls.get());
        assertEquals(java.util.Set.of("m"), router.models());
    }

    @Test
    void retryableFailureFallsBackToNextCandidate() {
        FakeBackend a = new FakeBackend("a");
        a.failure = new BackendException(BackendException.TYPE_UPSTREAM_5XX, "boom");
        FakeBackend b = new FakeBackend("b");
        DecisionRouter router =
                new DecisionRouter(Map.of("m", List.of(a, b)), noSleepPolicy(2), DefaultRetryClassifier.INSTANCE);

        DecisionResponse response = router.decide(request("m"));

        assertEquals("m", response.model());
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());
    }

    @Test
    void nonRetryableFailurePropagatesWithoutFallback() {
        FakeBackend a = new FakeBackend("a");
        BackendException failure = new BackendException(BackendException.TYPE_AUTH, "nope");
        a.failure = failure;
        FakeBackend b = new FakeBackend("b");
        DecisionRouter router =
                new DecisionRouter(Map.of("m", List.of(a, b)), noSleepPolicy(2), DefaultRetryClassifier.INSTANCE);

        BackendException thrown = assertThrows(BackendException.class, () -> router.decide(request("m")));
        assertSame(failure, thrown);
        assertEquals(0, b.calls.get());
    }

    @Test
    void exhaustedRetriesPropagateLastFailureWithEarlierSuppressed() {
        FakeBackend a = new FakeBackend("a");
        a.failure = new BackendException(BackendException.TYPE_NETWORK, "down-a");
        FakeBackend b = new FakeBackend("b");
        b.failure = new BackendException(BackendException.TYPE_NETWORK, "down-b");
        DecisionRouter router =
                new DecisionRouter(Map.of("m", List.of(a, b)), noSleepPolicy(1), DefaultRetryClassifier.INSTANCE);

        BackendException thrown = assertThrows(BackendException.class, () -> router.decide(request("m")));
        // Attempt 0 round-robins to a, the retry walks to b, b's failure is the
        // survivor with a's suppressed — pin the exact walk, not just call counts.
        assertEquals("down-b", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("down-a", thrown.getSuppressed()[0].getMessage());
        assertEquals(1, a.calls.get());
        assertEquals(1, b.calls.get());
    }

    @Test
    void imageRequestsRouteToCapableCandidatesDeterministically() {
        // A mixed pool (one vision entry, one without) must not turn image
        // requests into a round-robin coin flip between 200 and 400 — every
        // image request goes to the capable candidate, text still spreads.
        FakeBackend textOnly = new FakeBackend("text-only");
        FakeBackend vision = new FakeBackend("vision", true);
        DecisionRouter router = new DecisionRouter(Map.of("m", List.of(textOnly, vision)));

        for (int i = 0; i < 4; i++) {
            router.decide(imageRequest("m"));
        }
        assertEquals(4, vision.calls.get());
        assertEquals(0, textOnly.calls.get());

        router.decide(request("m"));
        router.decide(request("m"));
        assertEquals(1, textOnly.calls.get(), "text requests still round-robin the whole pool");
        assertEquals(5, vision.calls.get());
    }

    @Test
    void imageRequestWithNoCapableCandidateIsATypedErrorBeforeDispatch() {
        FakeBackend textOnly = new FakeBackend("text-only");
        DecisionRouter router = new DecisionRouter(Map.of("m", List.of(textOnly)));

        DecisionCodecException e = assertThrows(DecisionCodecException.class, () -> router.decide(imageRequest("m")));
        assertEquals(DecisionCodecException.TYPE_INVALID_REQUEST, e.type());
        assertEquals(0, textOnly.calls.get(), "fail-closed before any dispatch");
    }

    @Test
    void constructorGuardsRejectEmptyAndDuplicatePools() {
        IllegalArgumentException empty =
                assertThrows(IllegalArgumentException.class, () -> new DecisionRouter(Map.of("m", List.of())));
        assertTrue(empty.getMessage().contains("\"m\""), empty.getMessage());

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> new DecisionRouter(Map.of("m", List.of(new FakeBackend("same"), new FakeBackend("same")))));
        assertTrue(
                duplicate.getMessage().contains("duplicate backend name \"same\"")
                        && duplicate.getMessage().contains("\"m\""),
                duplicate.getMessage());
    }

    @Test
    void throwingClassifierMustNotTurnAFailureRetryable() {
        FakeBackend a = new FakeBackend("a");
        a.failure = new BackendException(BackendException.TYPE_UPSTREAM_5XX, "boom");
        FakeBackend b = new FakeBackend("b");
        DecisionRouter router = new DecisionRouter(Map.of("m", List.of(a, b)), noSleepPolicy(2), error -> {
            throw new IllegalStateException("classifier blew up");
        });

        BackendException thrown = assertThrows(BackendException.class, () -> router.decide(request("m")));
        assertEquals("boom", thrown.getMessage());
        assertEquals(0, b.calls.get(), "a throwing classifier never widens the walk");
    }

    @Test
    void interruptedBackoffAbortsTheChainWithEarlierFailuresSuppressed() {
        FakeBackend a = new FakeBackend("a");
        a.failure = new BackendException(BackendException.TYPE_NETWORK, "down-a");
        FakeBackend b = new FakeBackend("b");
        RetryPolicy interrupting = new RetryPolicy(2, 1, 1, 0.0, RandomGenerator.getDefault(), millis -> {
            throw new IllegalStateException("interrupted");
        });
        DecisionRouter router =
                new DecisionRouter(Map.of("m", List.of(a, b)), interrupting, DefaultRetryClassifier.INSTANCE);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> router.decide(request("m")));
        assertEquals("interrupted", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("down-a", thrown.getSuppressed()[0].getMessage());
        assertEquals(0, b.calls.get(), "interrupted backoff must abort before trying the next candidate");
    }

    @Test
    void unknownModelIsTyped() {
        DecisionRouter router = new DecisionRouter(Map.of());
        UnknownModelException e = assertThrows(UnknownModelException.class, () -> router.decide(request("nope")));
        assertEquals("nope", e.model());
    }

    @Test
    void assetsAndBoxQuestionsRouteToVisionCapableCandidates() {
        FakeBackend text = new FakeBackend("text");
        FakeBackend vision = new FakeBackend("vision", true);
        DecisionRouter router = new DecisionRouter(Map.of("m", List.of(text, vision)));
        DecisionRequest withAssets = new DecisionRequest(
                "m",
                Map.of("photo", "p1"),
                null,
                Map.of("locate", new DecisionQuestion.BoxQuestion("Find the mug.")),
                Map.of("assets", Map.of("p1", Map.of("type", "image"))),
                Map.of());

        router.decide(withAssets);

        assertEquals(0, text.calls.get(), "text-only backend must not receive TypeSafe assets/Box evidence");
        assertEquals(1, vision.calls.get());
    }

    @Test
    void stateImagePartsRouteToVisionCapableCandidates() {
        FakeBackend text = new FakeBackend("text");
        FakeBackend vision = new FakeBackend("vision", true);
        DecisionRouter router = new DecisionRouter(Map.of("m", List.of(text, vision)));
        DecisionRequest withStateImage = new DecisionRequest(
                "m",
                List.of(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AAAA"))),
                null,
                Map.of("q", new DecisionQuestion.NoulQuestion("?", null)),
                Map.of(),
                Map.of());

        router.decide(withStateImage);

        assertEquals(0, text.calls.get(), "text-only backend must not receive a state image part");
        assertEquals(1, vision.calls.get());
    }

    @Test
    void stopsAfterEveryCandidateHasBeenTriedInsteadOfRePickingCandidateZero() {
        FakeBackend a = new FakeBackend("a");
        a.failure = new BackendException(BackendException.TYPE_NETWORK, "down-a");
        FakeBackend b = new FakeBackend("b");
        b.failure = new BackendException(BackendException.TYPE_NETWORK, "down-b");
        DecisionRouter router =
                new DecisionRouter(Map.of("m", List.of(a, b)), noSleepPolicy(3), DefaultRetryClassifier.INSTANCE);

        BackendException thrown = assertThrows(BackendException.class, () -> router.decide(request("m")));

        assertEquals(1, a.calls.get(), "one shot per candidate");
        assertEquals(1, b.calls.get(), "no re-pick of candidate 0 after exhaustion");
        assertEquals(1, thrown.getSuppressed().length);
    }

    @Test
    void aSelfSuppressedCachedFailureDoesNotMaskTheRealDispatchFailure() {
        BackendException shared = new BackendException(BackendException.TYPE_NETWORK, "shared");
        FakeBackend a = new FakeBackend("a");
        a.failure = shared;
        FakeBackend b = new FakeBackend("b");
        b.failure = shared;
        DecisionRouter router =
                new DecisionRouter(Map.of("m", List.of(a, b)), noSleepPolicy(3), DefaultRetryClassifier.INSTANCE);

        BackendException thrown = assertThrows(BackendException.class, () -> router.decide(request("m")));
        assertSame(shared, thrown, "the backend failure, never IllegalArgumentException(self-suppression)");
    }

    @Test
    void aThrowingObserverCannotAbortDispatch() {
        FakeBackend a = new FakeBackend("a");
        DecisionRouter router = new DecisionRouter(Map.of("m", List.of(a)));

        DecisionResponse response = router.decide(request("m"), backend -> {
            throw new IllegalStateException("observer boom");
        });

        assertEquals("m", response.model());
        assertEquals(1, a.calls.get(), "accounting never masks the dispatch");
    }

    @Test
    void blankAliasesAreRejectedAtConstruction() {
        assertThrows(
                IllegalArgumentException.class, () -> new DecisionRouter(Map.of("", List.of(new FakeBackend("a")))));
    }

    @Test
    void doesNotSleepBackoffAfterLastCandidateFails() {
        AtomicInteger sleeps = new AtomicInteger();
        RetryPolicy countingPolicy =
                new RetryPolicy(3, 50, 100, 0.0, RandomGenerator.getDefault(), millis -> sleeps.incrementAndGet());

        FakeBackend single = new FakeBackend("single");
        single.failure = new BackendException(BackendException.TYPE_UPSTREAM_5XX, "boom");
        DecisionRouter singleRouter =
                new DecisionRouter(Map.of("m", List.of(single)), countingPolicy, DefaultRetryClassifier.INSTANCE);
        assertThrows(BackendException.class, () -> singleRouter.decide(request("m")));
        assertEquals(0, sleeps.get(), "single candidate failure must not sleep backoff");

        sleeps.set(0);
        FakeBackend a = new FakeBackend("a");
        a.failure = new BackendException(BackendException.TYPE_NETWORK, "down-a");
        FakeBackend b = new FakeBackend("b");
        b.failure = new BackendException(BackendException.TYPE_NETWORK, "down-b");
        DecisionRouter twoRouter =
                new DecisionRouter(Map.of("m", List.of(a, b)), countingPolicy, DefaultRetryClassifier.INSTANCE);
        assertThrows(BackendException.class, () -> twoRouter.decide(request("m")));
        assertEquals(1, sleeps.get(), "only sleep between a->b, never after last candidate b fails");
    }

    @Test
    void imageCapabilityErrorSanitizesModelNameAgainstLogForgery() {
        FakeBackend textOnly = new FakeBackend("text-only");
        String maliciousAlias = "model\ninjected\r\nline";
        DecisionRouter router = new DecisionRouter(Map.of(maliciousAlias, List.of(textOnly)));

        DecisionCodecException e =
                assertThrows(DecisionCodecException.class, () -> router.decide(imageRequest(maliciousAlias)));
        assertTrue(!e.getMessage().contains("\n") && !e.getMessage().contains("\r"), e.getMessage());
    }

    @Test
    void knowsReturnsFalseForNullAndUnknownAndTrueForConfiguredAlias() {
        DecisionRouter router = new DecisionRouter(Map.of("m", List.of(new FakeBackend("a"))));

        assertFalse(router.knows(null));
        assertFalse(router.knows("missing"));
        assertTrue(router.knows("m"));
    }

    @Test
    void observerThrowingErrorIsDroppedAndCannotAbortDispatch() {
        FakeBackend a = new FakeBackend("a");
        DecisionRouter router = new DecisionRouter(Map.of("m", List.of(a)));

        DecisionResponse response = router.decide(request("m"), backend -> {
            throw new AssertionError("observer error");
        });

        assertEquals("m", response.model());
        assertEquals(1, a.calls.get());
    }

    @Test
    void backendThrowingErrorPropagatesWithoutRetryAndAttachesSuppressedFailures() {
        AtomicInteger classifierCalls = new AtomicInteger();
        RetryClassifier counting = error -> {
            classifierCalls.incrementAndGet();
            return DefaultRetryClassifier.INSTANCE.isRetryable(error);
        };
        DecisionBackend errorBackend = new DecisionBackend() {
            @Override
            public String name() {
                return "err";
            }

            @Override
            public String baseUrl() {
                return "http://fake/err";
            }

            @Override
            public boolean supportsImages() {
                return false;
            }

            @Override
            public DecisionResponse decide(DecisionRequest request) {
                throw new AssertionError("fatal error");
            }
        };
        FakeBackend b = new FakeBackend("b");
        DecisionRouter single = new DecisionRouter(Map.of("m", List.of(errorBackend, b)), noSleepPolicy(2), counting);
        AssertionError firstErr = assertThrows(AssertionError.class, () -> single.decide(request("m")));
        assertEquals("fatal error", firstErr.getMessage());
        assertEquals(0, classifierCalls.get(), "Errors bypass retry classification");
        assertEquals(0, b.calls.get(), "Errors do not fail over to the next candidate");

        FakeBackend a = new FakeBackend("a");
        a.failure = new BackendException(BackendException.TYPE_NETWORK, "down-a");
        DecisionRouter second = new DecisionRouter(Map.of("m", List.of(a, errorBackend)), noSleepPolicy(2), counting);
        AssertionError secondErr = assertThrows(AssertionError.class, () -> second.decide(request("m")));
        assertEquals(1, secondErr.getSuppressed().length);
        assertEquals("down-a", secondErr.getSuppressed()[0].getMessage());
    }

    @Test
    void classifierThrowingRuntimeErrorOrSameFailureInstanceDoesNotRetryOrSelfSuppress() {
        FakeBackend a = new FakeBackend("a");
        a.failure = new BackendException(BackendException.TYPE_UPSTREAM_5XX, "boom-runtime");
        FakeBackend b = new FakeBackend("b");

        DecisionRouter runtimeRouter = new DecisionRouter(Map.of("m", List.of(a, b)), noSleepPolicy(2), error -> {
            throw new IllegalStateException("classifier runtime");
        });
        BackendException rt = assertThrows(BackendException.class, () -> runtimeRouter.decide(request("m")));
        assertEquals(1, rt.getSuppressed().length);
        assertEquals("classifier runtime", rt.getSuppressed()[0].getMessage());
        assertEquals(0, b.calls.get());

        a.failure = new BackendException(BackendException.TYPE_UPSTREAM_5XX, "boom-error");
        DecisionRouter errorRouter = new DecisionRouter(Map.of("m", List.of(a, b)), noSleepPolicy(2), error -> {
            throw new AssertionError("classifier error");
        });
        BackendException err = assertThrows(BackendException.class, () -> errorRouter.decide(request("m")));
        assertEquals(1, err.getSuppressed().length);
        assertEquals("classifier error", err.getSuppressed()[0].getMessage());
        assertEquals(0, b.calls.get());

        BackendException shared = new BackendException(BackendException.TYPE_UPSTREAM_5XX, "same-instance");
        a.failure = shared;
        DecisionRouter sameRouter = new DecisionRouter(Map.of("m", List.of(a, b)), noSleepPolicy(2), error -> {
            throw (RuntimeException) error;
        });
        BackendException same = assertThrows(BackendException.class, () -> sameRouter.decide(request("m")));
        assertSame(shared, same);
        assertEquals(0, same.getSuppressed().length, "must never self-suppress");
        assertEquals(0, b.calls.get());
    }

    @Test
    void sleepBackoffRealInterruptAndSameExceptionInstanceAvoidSelfSuppression() {
        FakeBackend a = new FakeBackend("a");
        BackendException shared = new BackendException(BackendException.TYPE_NETWORK, "down-a");
        a.failure = shared;
        FakeBackend b = new FakeBackend("b");
        RetryPolicy sameSleeper = new RetryPolicy(2, 1, 1, 0.0, RandomGenerator.getDefault(), millis -> {
            throw shared;
        });
        DecisionRouter sameRouter =
                new DecisionRouter(Map.of("m", List.of(a, b)), sameSleeper, DefaultRetryClassifier.INSTANCE);
        BackendException sameThrown = assertThrows(BackendException.class, () -> sameRouter.decide(request("m")));
        assertSame(shared, sameThrown);
        assertEquals(0, sameThrown.getSuppressed().length, "must never self-suppress during sleepBackoff");

        RetryPolicy realSleep = new RetryPolicy(2, 50, 100, 0.0);
        DecisionRouter interruptRouter =
                new DecisionRouter(Map.of("m", List.of(a, b)), realSleep, DefaultRetryClassifier.INSTANCE);
        Thread.currentThread().interrupt();
        try {
            RuntimeException interrupted =
                    assertThrows(RuntimeException.class, () -> interruptRouter.decide(request("m")));
            assertTrue(Thread.currentThread().isInterrupted(), "interrupt flag must be preserved");
            assertEquals(1, interrupted.getSuppressed().length);
            assertSame(shared, interrupted.getSuppressed()[0]);
            assertEquals(0, b.calls.get());
        } finally {
            Thread.interrupted();
        }
    }
}
