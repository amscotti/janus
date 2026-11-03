package io.amscotti.janus.gateway;

import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.DecisionBackend;
import io.amscotti.janus.router.DecisionRouter;
import io.amscotti.janus.router.Router;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared gateway test composition root: replaces the real {@link RouterFactory} (whose
 * {@code Router} bean is excluded via {@code @Replaces(factory =...)}) so integration
 * tests wire to the {@link FakeBackend} instead of a real DeepSeek adapter. The final
 * {@code Router} ( immutability design) cannot be AOP-mocked, so the factory seam is
 * replaced instead.
 *
 * <p>The backend is shared across gateway test classes; tests configure it per test
 * (JUnit executes test methods sequentially within a class, and classes share this
 * singleton bean).
 */
@Factory
@Requires(property = "janus.test.production-factories", notEquals = "true")
final class TestRouterFactory {

    /** Shared fake backend, configured per test. */
    static final FakeBackend BACKEND = new FakeBackend("deepseek");

    /** Shared fake decision backend (aliases jev-latest + d3), configured per test. */
    static final FakeDecisionBackend DECISION_BACKEND = new FakeDecisionBackend("fake-decision");

    /** Shared image-capable fake (alias vision-model) for the vision routing legs. */
    static final FakeDecisionBackend DECISION_VISION_BACKEND = new FakeDecisionBackend("fake-decision-vision", true);

    @Singleton
    @Replaces(factory = RouterFactory.class)
    Router router() {
        // LinkedHashMap: Map.of's iteration order is unspecified; the models listing
        // must appear in config insertion order.
        Map<String, ChatBackend> routes = new LinkedHashMap<>();
        routes.put("deepseek-v4-flash", BACKEND);
        routes.put("deepseek-v4-pro", BACKEND);
        return new Router(Collections.unmodifiableMap(routes));
    }

    @Singleton
    @Replaces(factory = DecisionRouterFactory.class)
    DecisionRouter decisionRouter() {
        Map<String, List<DecisionBackend>> routes = new LinkedHashMap<>();
        routes.put("jev-latest", List.of(DECISION_BACKEND));
        routes.put("d3", List.of(DECISION_BACKEND));
        routes.put("vision-model", List.of(DECISION_VISION_BACKEND));
        routes.put("mixed-vision", List.of(DECISION_BACKEND, DECISION_VISION_BACKEND));
        return new DecisionRouter(Collections.unmodifiableMap(routes));
    }
}
