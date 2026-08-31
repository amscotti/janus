package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The native-image boot asymmetry pre-check (mirror of {@code
 * JanusCliTest}): {@link JanusApplication} is also the native binary's {@code
 * mainClass}, and the native image reads config from {@code MICRONAUT_CONFIG_FILES},
 * never {@code --config}. An operator following the README's JVM-only {@code janus
 * --config config.toml} wording against the native binary would otherwise boot
 * unauthenticated on the packaged defaults — so any config flag seen here fails fast
 * with a usage line naming {@code MICRONAUT_CONFIG_FILES}. The JVM CLI never triggers
 * this: {@code JanusCli} strips the consumed flag before delegating (pinned by
 * {@code JanusCliTest#withoutConfigFlagStripsTheConsumedFlag}).
 */
class JanusApplicationTest {

    @Test
    void anyConfigFlagIsRejectedNamingMicronautConfigFiles() {
        List<String[]> flagged = List.of(
                new String[] {"--config", "/tmp/x.toml"},
                new String[] {"-c", "/tmp/x.toml"},
                new String[] {"--config=/tmp/x.toml"},
                new String[] {"-c=/tmp/x.toml"});
        for (String[] args : flagged) {
            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> JanusApplication.rejectNativeConfigFlag(args));
            assertTrue(
                    ex.getMessage().contains("MICRONAUT_CONFIG_FILES"),
                    "the rejection must name MICRONAUT_CONFIG_FILES: " + ex.getMessage());
        }
    }

    @Test
    void nonConfigArgsPassThrough() {
        assertDoesNotThrow(() -> JanusApplication.rejectNativeConfigFlag(new String[] {}));
        assertDoesNotThrow(() -> JanusApplication.rejectNativeConfigFlag(new String[] {"--port", "8080"}));
    }
}
