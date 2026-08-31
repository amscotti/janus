package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Fail-fast pins for {@link PostgresStoreConfig}: a blank JDBC URL or a
 * non-positive pool size must refuse construction (the factory must not build a
 * Hikari pool that would fail later, on first request).
 */
class PostgresStoreConfigTest {

    @Test
    void validConfigKeepsNullableCredentials() {
        PostgresStoreConfig config = new PostgresStoreConfig("jdbc:postgresql://localhost/janus", null, null, 10);
        assertEquals("jdbc:postgresql://localhost/janus", config.jdbcUrl());
        assertNull(config.username());
        assertNull(config.password());
        assertEquals(10, config.maxPoolSize());
    }

    @Test
    void blankOrNullJdbcUrlIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PostgresStoreConfig(null, "u", "p", 4));
        IllegalArgumentException blank =
                assertThrows(IllegalArgumentException.class, () -> new PostgresStoreConfig("  ", "u", "p", 4));
        assertTrue(blank.getMessage().contains("jdbcUrl"));
    }

    @Test
    void nonPositivePoolSizeIsRejected() {
        IllegalArgumentException zero =
                assertThrows(IllegalArgumentException.class, () -> new PostgresStoreConfig("jdbc:x", "u", "p", 0));
        assertTrue(zero.getMessage().contains("maxPoolSize"));
        assertThrows(IllegalArgumentException.class, () -> new PostgresStoreConfig("jdbc:x", "u", "p", -1));
    }
}
