package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link Usage#billedPromptTokens} is the vendor prompt size: regular input plus
 * cache-read and cache-creation. Long-context tiers key off this total, not the
 * regular-only {@code promptTokens} field (codecs subtract cached tokens).
 */
class UsageTest {

    @Test
    void billedPromptTokensSumsRegularAndCache() {
        assertEquals(10, new Usage(10, 5, 15).billedPromptTokens());
        assertEquals(18, new Usage(10, 5, 15, 3L, 5L).billedPromptTokens());
        assertEquals(13, new Usage(10, 5, 15, 3L, null).billedPromptTokens());
        assertEquals(15, new Usage(10, 5, 15, null, 5L).billedPromptTokens());
    }

    @Test
    void rejectsNegativeTokenCounts() {
        assertThrows(IllegalArgumentException.class, () -> new Usage(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Usage(0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Usage(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new Usage(0, 0, 0, -1L, null));
        assertThrows(IllegalArgumentException.class, () -> new Usage(0, 0, 0, null, -1L));
        assertThrows(IllegalArgumentException.class, () -> new Usage(0, 0, 0, null, null, -1L));
    }

    @Test
    void billedPromptTokensThrowsOnOverflowInsteadOfWrappingNegative() {
        Usage maxPlusCache = new Usage(Long.MAX_VALUE, 0, Long.MAX_VALUE, 1L, null);
        assertThrows(ArithmeticException.class, maxPlusCache::billedPromptTokens);
    }
}
