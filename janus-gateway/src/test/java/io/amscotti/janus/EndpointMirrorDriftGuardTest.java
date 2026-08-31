package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import io.micronaut.json.tree.JsonNode;
import io.micronaut.toml.Parser;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Drift guard for the {@code [micronaut.*]} / {@code [endpoints.*]} mirror between
 * the two boot configuration files:
 *
 * <ol>
 * <li>{@code config.toml} (the operator-facing external config),
 * <li>{@code janus-gateway/src/main/resources/application.toml} (the packaged defaults).
 * </ol>
 *
 * <p>The {@code config.toml} header documents that the blocks must stay in sync, but
 * nothing enforced it — a half-fix of the management-endpoint lockdown in only one
 * file would silently re-expose the other boot path ({@code config.toml} boots the
 * JVM CLI, {@code application.toml} boots the native image and every {@code
 * @MicronautTest}). Mirrors {@link VersionTriplicationTest} / {@code
 * ReflectConfigDriftGuardTest}: both files are read fresh from the repo tree (no
 * shared constant), and both <em>must define</em> each mirrored key with an equal
 * value — a missing key on either side is a drift (e.g. the lockdown applied to only
 * one file). Both files are parsed with the real TOML parser Micronaut uses, so a
 * future syntax change that breaks one file's loadability surfaces here too.
 */
class EndpointMirrorDriftGuardTest {

    /** Mirrored keys — config.toml's header documents exactly this set. */
    private static final List<String> MIRRORED_KEYS = List.of(
            "micronaut.application.name",
            "micronaut.server.port",
            "endpoints.all.enabled",
            "endpoints.all.sensitive",
            "endpoints.health.enabled",
            "endpoints.health.details-visible",
            "endpoints.health.sensitive",
            "endpoints.stop.enabled",
            "endpoints.refresh.enabled");

    @Test
    void endpointBlocksMatchThePackagedDefaults() throws Exception {
        Path repoRoot = findRepoRoot();
        String configToml = Files.readString(repoRoot.resolve("config.toml"));
        String applicationToml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.toml")) {
            assertNotNull(in, "application.toml must be on the test classpath");
            applicationToml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        JsonNode external = Parser.parse(configToml);
        JsonNode packaged = Parser.parse(applicationToml);

        for (String key : MIRRORED_KEYS) {
            String externalValue = valueAt(external, key, "config.toml");
            String packagedValue = valueAt(packaged, key, "application.toml");
            assertEquals(
                    packagedValue,
                    externalValue,
                    "key '" + key + "' must match across the two boot configs — a half-fix "
                            + "of the management-endpoint lockdown would silently re-expose the other boot path");
        }
    }

    private static String valueAt(JsonNode root, String dottedPath, String source) {
        JsonNode node = root;
        for (String segment : dottedPath.split("\\.")) {
            node = node.get(segment);
            if (node == null) {
                fail(source + " must define '" + dottedPath + "' (the mirrored key is missing)");
            }
        }
        return node.coerceStringValue();
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
