package io.amscotti.janus.gateway.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LiveProviderSupport#virtualKey} caches minted keys in a static map.
 * Each {@code @MicronautTest} boots its own EmbeddedServer and in-memory store,
 * so the cache key must include the client identity — otherwise a second live
 * IT class reuses a secret the new store has never seen (401 invalid credentials).
 */
class LiveProviderSupportCacheTest {

    @Test
    void cacheKeyIncludesClientIdentity() {
        Object serverA = new Object();
        Object serverB = new Object();
        List<String> flash = List.of("deepseek-v4-flash");
        assertNotEquals(
                LiveProviderSupport.virtualKeyCacheKey(serverA, flash),
                LiveProviderSupport.virtualKeyCacheKey(serverB, flash),
                "two EmbeddedServer clients must not share a cached virtual key");
        assertEquals(
                LiveProviderSupport.virtualKeyCacheKey(serverA, flash),
                LiveProviderSupport.virtualKeyCacheKey(serverA, flash),
                "the same client + model list is a cache hit");
        assertNotEquals(
                LiveProviderSupport.virtualKeyCacheKey(serverA, flash),
                LiveProviderSupport.virtualKeyCacheKey(serverA, List.of("claude-sonnet-5")));
    }
}
