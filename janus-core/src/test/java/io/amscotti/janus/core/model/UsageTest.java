package io.amscotti.janus.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
