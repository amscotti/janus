package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.ChatRequest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionAffinityLoadBalancer} — the rendezvous (HRW) hashing pin: same
 * session id → one backend, distinct sessions spread, removing a backend moves
 * only its sessions, absent/blank session falls back to the inner round-robin,
 * and the pinned deterministic sequence (FNV-1a over {@code sessionId + "|" +
 * backend.name}) is a regression pin — any change to the hash or the key shape
 * must be a deliberate one.
 */
class SessionAffinityLoadBalancerTest {

    private static ChatRequest session(String model, String sessionId) {
        return TestData.request(model).withMetaEntry(SessionAffinityLoadBalancer.META_SESSION_ID, sessionId);
    }

    private static List<String> picks(SessionAffinityLoadBalancer lb, List<ChatBackend> candidates, String sessionId) {
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < candidates.size() * 2; i++) {
            ChatRequest request = session("m", sessionId);
            picked.add(lb.pick("m", candidates, request).name());
        }
        return picked;
    }

    @Test
    void emptyCandidateListFailsWithAClearContractMessage() {
        // Same contract shape as RoundRobinLoadBalancer: pick is a public method —
        // an empty list must fail with the contract message, not return null.
        assertThrows(IllegalArgumentException.class, () -> {
            ChatRequest request = session("m", "s1");
            new SessionAffinityLoadBalancer().pick("m", List.of(), request);
        });
    }

    @Test
    void sameSessionSticksToOneBackendAcrossManyPicks() {
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        Set<String> picked = new HashSet<>(picks(lb, candidates, "conv-42"));
        assertEquals(1, picked.size(), "50 picks of one session id must all land on one backend: " + picked);
    }

    @Test
    void distinctSessionsSpreadAcrossCandidates() {
        // HRW over enough distinct ids behaves like a uniform hash: every backend
        // gets sessions, no single-backend collapse (the spread is deterministic —
        // s1..s30 over {A,B,C} hashes 14/8/8 with the pinned FNV-1a key shape).
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        Set<String> picked = new HashSet<>();
        for (int i = 1; i <= 30; i++) {
            ChatRequest request = session("m", "s" + i);
            picked.add(lb.pick("m", candidates, request).name());
        }
        assertEquals(Set.of("A", "B", "C"), picked, "30 distinct sessions must cover every candidate");
    }

    @Test
    void absentSessionFallsBackToRoundRobin() {
        // A request carrying no meta entry at all → the hardcoded inner
        // round-robin: an exact cycle across the candidates (the reference
        // simple_shuffle-fallback precedent).
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            picked.add(lb.pick("m", candidates, TestData.request("m")).name());
        }
        assertEquals(List.of("A", "B", "C", "A", "B", "C"), picked);
    }

    @Test
    void blankOrWhitespaceSessionIdCountsAsAbsent() {
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        // Blank after trim counts as absent → round-robin cycle, never a hash of "".
        assertEquals("A", lb.pick("m", candidates, session("m", "   ")).name());
        assertEquals("B", lb.pick("m", candidates, session("m", "\t\n")).name());
        // A non-String meta value is treated as absent too (defensive — the gateway
        // fold only ever writes String values).
        assertEquals(
                "C",
                lb.pick(
                                "m",
                                candidates,
                                TestData.request("m").withMetaEntry(SessionAffinityLoadBalancer.META_SESSION_ID, 7))
                        .name());
        // Surrounding whitespace is trimmed before hashing: " s1 " hashes as "s1".
        assertEquals(
                lb.pick("m", candidates, session("m", "s1")).name(),
                lb.pick("m", candidates, session("m", " s1 ")).name());
    }

    @Test
    void removingOneBackendMovesOnlyItsSessions() {
        // THE consistency pin (why HRW, not session-sticky mod N): with candidates
        // {A,B,C}, dropping B re-hashes only B's sessions onto the survivors —
        // every session that picked A or C keeps its pick. A mod-N stickiness would
        // reshuffle every session on any membership change.
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        List<ChatBackend> abc = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        List<ChatBackend> ac = List.of(TestData.fake("A"), TestData.fake("C"));
        int keptPick = 0;
        int movedPick = 0;
        for (int i = 1; i <= 30; i++) {
            String sessionId = "s" + i;
            String before = lb.pick("m", abc, session("m", sessionId)).name();
            String after = lb.pick("m", ac, session("m", sessionId)).name();
            if ("B".equals(before)) {
                // B is gone from the pool: its sessions must move to a survivor.
                assertTrue(
                        "A".equals(after) || "C".equals(after), sessionId + " must move to a survivor, got " + after);
                movedPick++;
            } else {
                assertEquals(before, after, sessionId + " hashed to a surviving backend and must keep its pick");
                keptPick++;
            }
        }
        // Deterministic with the pinned hash: s1..s30 spread 14/8/8 → exactly 8 moved.
        assertEquals(8, movedPick, "exactly B's sessions move (pinned spread 14/8/8)");
        assertEquals(22, keptPick);
    }

    @Test
    void stickyPickRespectsCandidateOrderTies() {
        // Regression pin, seededRandomProducesPinnedSequence style: the exact pick
        // sequence for fixed session ids is part of the routing behavior — sessions
        // are pinned to backends by the FNV-1a score, so an unintentional change to
        // the hash, the key shape (sessionId + "|" + name) or the tie handling
        // (config order via strictly-greater) shows up here. These s1..s8 scores
        // never straddle 0x80000000 within one session, so a signed > would pass
        // this sequence unchanged — the UNSIGNED half of the comparison is pinned
        // by signedCompareWouldPickTheWrongBackend below.
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        List<String> picked = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            ChatRequest request = session("m", "s" + i);
            picked.add(lb.pick("m", candidates, request).name());
        }
        assertEquals(List.of("A", "C", "B", "A", "A", "C", "B", "A"), picked);
        // HRW is order-independent: permuting the candidate list does not change the
        // winner (the scores are distinct; on a true score tie the strictly-greater
        // comparison keeps the config-order-first candidate — tie → config order).
        List<List<ChatBackend>> permutations = List.of(
                List.of(TestData.fake("A"), TestData.fake("C"), TestData.fake("B")),
                List.of(TestData.fake("B"), TestData.fake("A"), TestData.fake("C")),
                List.of(TestData.fake("C"), TestData.fake("B"), TestData.fake("A")));
        for (List<ChatBackend> permuted : permutations) {
            assertEquals("A", lb.pick("m", permuted, session("m", "s1")).name());
            assertEquals("C", lb.pick("m", permuted, session("m", "s2")).name());
            assertEquals("B", lb.pick("m", permuted, session("m", "s3")).name());
        }
    }

    @Test
    void signedCompareWouldPickTheWrongBackend() {
        // The unsigned half of Integer.compareUnsigned is load-bearing, and the
        // pinned sequence above cannot see it: within each of s1..s8 all three
        // scores sit on the SAME side of 0x80000000, so signed and unsigned
        // orderings agree and a signed > regression stays green. This test
        // searches — deterministically, in id order — for the first session id
        // that genuinely discriminates: the unsigned winner's score has the sign
        // bit set while a signed compare crowns a DIFFERENT backend. With the
        // pinned hash and {A,B,C} the search finds s60 first (A=0x7f72360b,
        // B=0x8072379e, C=0x81723931 — unsigned winner C, signed would pick A).
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"), TestData.fake("C"));
        String discriminator = null;
        String unsignedWinner = null;
        for (int i = 1; i <= 10_000 && discriminator == null; i++) {
            String sessionId = "s" + i;
            ChatBackend unsignedPick = null;
            ChatBackend signedPick = null;
            int bestUnsigned = 0;
            int bestSigned = 0;
            boolean first = true;
            for (ChatBackend candidate : candidates) {
                int score = SessionAffinityLoadBalancer.fnv1a32(sessionId + "|" + candidate.name());
                if (first || Integer.compareUnsigned(score, bestUnsigned) > 0) {
                    unsignedPick = candidate;
                    bestUnsigned = score;
                }
                if (first || score > bestSigned) {
                    signedPick = candidate;
                    bestSigned = score;
                }
                first = false;
            }
            if (unsignedPick != signedPick) {
                discriminator = sessionId;
                unsignedWinner = unsignedPick.name();
            }
        }
        // Fail loudly if the FNV behavior changes and no discriminator exists anymore.
        assertNotNull(discriminator, "s1..s10000 contain no signed/unsigned discriminator — did fnv1a32 change?");
        // The sticky pick must be the UNSIGNED winner (s60: C — a signed > would
        // route this session to A): Integer.compareUnsigned semantics asserted
        // indirectly via the pick.
        assertEquals(
                unsignedWinner,
                lb.pick("m", candidates, session("m", discriminator)).name(),
                discriminator + " must pick the unsigned-compare winner");
    }

    @Test
    void twoArgPickFallsBackToRoundRobin() {
        // The 2-arg form has no request → no session id: it documents as the
        // direct/test-use form and behaves as the fallback (round-robin).
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        List<ChatBackend> candidates = List.of(TestData.fake("A"), TestData.fake("B"));
        assertEquals("A", lb.pick("m", candidates).name());
        assertEquals("B", lb.pick("m", candidates).name());
        assertEquals("A", lb.pick("m", candidates).name());
    }

    @Test
    void integratesWithBalancedRouter() {
        // End with the Router.balanced + fake-backends pattern every strategy test
        // uses: the session id rides ChatRequest.meta through the router's 3-arg
        // pick; one session → one backend, no session → the round-robin cycle.
        SessionAffinityLoadBalancer lb = new SessionAffinityLoadBalancer();
        FakeBackend a = TestData.fake("A", TestData.response("m"));
        FakeBackend b = TestData.fake("B", TestData.response("m"));
        FakeBackend c = TestData.fake("C", TestData.response("m"));
        Router router = Router.balanced(Map.of("m", List.of(a, b, c)), lb);
        for (int i = 0; i < 5; i++) {
            router.complete(session("m", "conv-7"));
        }
        Map<String, Integer> served = new LinkedHashMap<>();
        for (FakeBackend backend : List.of(a, b, c)) {
            served.put(backend.name(), backend.completeCalls.size());
        }
        assertEquals(5, a.completeCalls.size() + b.completeCalls.size() + c.completeCalls.size());
        assertEquals(
                1,
                served.values().stream().filter(count -> count > 0).count(),
                "one session is served by exactly one backend: " + served);
        // Absent session id through the router → the inner round-robin cycle: each
        // backend gains exactly one of the next three no-session requests.
        int aBefore = a.completeCalls.size();
        int bBefore = b.completeCalls.size();
        int cBefore = c.completeCalls.size();
        router.complete(TestData.request("m"));
        router.complete(TestData.request("m"));
        router.complete(TestData.request("m"));
        assertEquals(aBefore + 1, a.completeCalls.size());
        assertEquals(bBefore + 1, b.completeCalls.size());
        assertEquals(cBefore + 1, c.completeCalls.size());
    }
}
