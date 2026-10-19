package io.amscotti.janus.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.UserMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Router token counting: first token-counting candidate serves; none → typed. */
class RouterCountTokensTest {

    private static ChatRequest request(String model) {
        return new ChatRequest(
                model,
                List.of(new UserMessage("hi")),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void singleBackendCounts() {
        FakeBackend backend = new FakeBackend("anthropic", null, null);
        backend.tokenCounting = true;
        backend.tokenCount = 42;
        Router router = new Router(Map.of("claude-x", backend));
        assertEquals(42, router.countTokens(request("claude-x")));
        assertEquals(1, backend.countTokensCalls.size());
    }

    @Test
    void unsupportedSingleBackendThrowsTyped() {
        FakeBackend backend = new FakeBackend("openai", null, null);
        Router router = new Router(Map.of("gpt-x", backend));
        UnsupportedTokenCountingException error =
                assertThrows(UnsupportedTokenCountingException.class, () -> router.countTokens(request("gpt-x")));
        assertEquals("gpt-x", error.model());
    }

    @Test
    void balancedSkipsUnsupportedCandidatesInConfigOrder() {
        FakeBackend first = new FakeBackend("openai", null, null);
        FakeBackend second = new FakeBackend("anthropic", null, null);
        second.tokenCounting = true;
        second.tokenCount = 7;
        Router router = Router.balanced(Map.of("alias", List.of(first, second)), new RoundRobinLoadBalancer());
        assertEquals(7, router.countTokens(request("alias")));
        assertEquals(0, first.countTokensCalls.size());
        assertEquals(1, second.countTokensCalls.size());
    }

    @Test
    void unknownModelThrowsUnknownModel() {
        Router router = new Router(Map.of());
        assertThrows(UnknownModelException.class, () -> router.countTokens(request("nope")));
    }
}
