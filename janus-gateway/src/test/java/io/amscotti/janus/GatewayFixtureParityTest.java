package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parity drift guard for the gateway's verbatim fixture copies: the gateway test
 * classpath cannot see core *test* resources, so {@code ErrorFixtureTest} and
 * {@code SseFixtureReplayTest} consume copies committed under this module's fixture tree.
 * Each copy is pinned independently (frame counts, terminal usage, envelope vocabulary),
 * but nothing verified the copies equal the core originals — a re-capture that changes
 * core's corpus would leave the stale gateway copies green and the two suites silently
 * disagreeing.
 *
 * <p>This guard reads core's committed fixtures straight off the repo tree (the same
 * {@code findRepoRoot} walk as {@link EndpointMirrorDriftGuardTest}) — no core test
 * resource visibility needed — and asserts byte-for-byte equality with the local copies.
 */
class GatewayFixtureParityTest {

    /** One gateway copy → its core original (paths relative to the module fixture trees). */
    private record Copy(String gatewayPath, String corePath) {}

    private static final List<Copy> COPIES = List.of(
            new Copy("errors/deepseek.400.json", "openai/errors/deepseek.400.json"),
            new Copy("errors/deepseek.401.json", "openai/errors/deepseek.401.json"),
            new Copy("errors/deepseek.429.json", "openai/errors/deepseek.429.json"),
            new Copy("errors/anthropic.400.json", "anthropic/errors/anthropic.400.json"),
            new Copy("stream/chat.stream.sse", "openai/chat.stream.sse"));

    @Test
    void gatewayFixtureCopiesEqualTheCoreOriginalsByteForByte() throws Exception {
        Path repoRoot = findRepoRoot();
        Path coreFixtures = repoRoot.resolve("janus-core/src/test/resources/fixtures");
        Path gatewayFixtures = repoRoot.resolve("janus-gateway/src/test/resources/fixtures");
        for (Copy copy : COPIES) {
            Path core = coreFixtures.resolve(copy.corePath());
            Path gateway = gatewayFixtures.resolve(copy.gatewayPath());
            assertTrue(Files.isRegularFile(core), "core original missing: " + copy.corePath());
            assertTrue(Files.isRegularFile(gateway), "gateway copy missing: " + copy.gatewayPath());
            assertArrayEquals(
                    Files.readAllBytes(core),
                    Files.readAllBytes(gateway),
                    "gateway fixture copy must equal the core original: " + copy.gatewayPath());
        }
    }

    /** Walk up from the test working dir until the directory containing settings.gradle. */
    private static Path findRepoRoot() throws Exception {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir, "could not locate the repo root (settings.gradle) from user.dir");
        return dir;
    }
}
