package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Drift guard for the {@code [janus] version} triplication (the release
 * gate documented the "bump all three" rule in {@code config.toml} but nothing enforced
 * it, so the three sources silently drifted). The version string lives in exactly three
 * places, and they must agree:
 *
 * <ol>
 * <li>{@code gradle.properties} ({@code version=…}, the Gradle project version),
 * <li>{@code config.toml} (the operator-facing {@code [janus] version = "…"}),
 * <li>{@code janus-gateway/src/main/resources/application.toml} (the packaged
 * {@code [janus] version = "…"}).
 * </ol>
 *
 * <p>Mirrors the {@code ReflectConfigDriftGuardTest} pattern: the three sources are read
 * fresh from the repo tree (no shared constant — that would merely re-point the drift),
 * so a future gate bump that touches only one of them fails here at test time instead
 * of shipping a diverged version string.
 */
class VersionTriplicationTest {

    /** The {@code [janus]} section header (exact — not {@code [janus.router]} etc.). */
    private static final String JANUS_SECTION = "[janus]";

    private static final Pattern VERSION_ASSIGNMENT = Pattern.compile("^version\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern GRADLE_VERSION = Pattern.compile("^version=([^\\s]+)$", Pattern.MULTILINE);

    @Test
    void theThreeVersionSourcesAgree() throws Exception {
        Path repoRoot = findRepoRoot();
        String gradleProperties = Files.readString(repoRoot.resolve("gradle.properties"));
        String configToml = Files.readString(repoRoot.resolve("config.toml"));
        String applicationToml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application.toml")) {
            assertNotNull(in, "application.toml must be on the test classpath");
            applicationToml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        String gradleVersion = gradlePropertiesVersion(gradleProperties);
        String configVersion = janusSectionVersion(configToml, "config.toml");
        String applicationVersion = janusSectionVersion(applicationToml, "application.toml");

        assertEquals(
                gradleVersion,
                configVersion,
                "gradle.properties and config.toml must carry the same [janus] version (triplication)");
        assertEquals(
                configVersion,
                applicationVersion,
                "config.toml and application.toml must carry the same [janus] version (triplication)");
    }

    private static String gradlePropertiesVersion(String content) {
        Matcher m = GRADLE_VERSION.matcher(content);
        if (!m.find()) {
            throw new AssertionError("gradle.properties must declare version=…");
        }
        return m.group(1);
    }

    /** The {@code version = "…"} value of the top-level {@code [janus]} section. */
    private static String janusSectionVersion(String toml, String source) {
        List<String> lines = toml.lines().toList();
        boolean inJanus = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("[") && line.endsWith("]")) {
                // A nested section ([janus.router], [janus.pricing] …) exits the top-level
                // [janus] section; a later [janus] cannot re-appear.
                inJanus = line.equals(JANUS_SECTION);
                continue;
            }
            if (!inJanus) {
                continue;
            }
            Matcher m = VERSION_ASSIGNMENT.matcher(line);
            if (m.find()) {
                return m.group(1);
            }
        }
        throw new AssertionError(source + " must carry a top-level [janus] version = \"…\"");
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
