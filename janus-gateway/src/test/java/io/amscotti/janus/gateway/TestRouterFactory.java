package io.amscotti.janus.gateway;

import io.amscotti.janus.router.ChatBackend;
import io.amscotti.janus.router.Router;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import java.util.Collections;
import java.util.LinkedHashMap;
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
}
