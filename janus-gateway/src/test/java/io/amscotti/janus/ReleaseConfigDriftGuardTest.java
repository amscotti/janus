package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drift guard for the operator-facing model/pricing refresh. The root config,
 * production example, and provider cookbook are copied far more often than they
 * are read in code, so a retired DeepSeek alias or an off-by-one GPT long-context
 * threshold must fail a test rather than ship in a release.
 */
class ReleaseConfigDriftGuardTest {

    @Test
    void currentDeepSeekAliasAndRatesStayInOperatorExamples() throws Exception {
        Path root = findRepoRoot();
        String config = Files.readString(root.resolve("config.toml"));
        String production = Files.readString(root.resolve("config.production.example.toml"));
        String providers = Files.readString(root.resolve("docs/providers.md"));

        assertTrue(config.contains("name = \"deepseek-flash\""), "root config must use the current DeepSeek id");
        assertTrue(
                production.contains("name = \"deepseek-flash\""),
                "production example must use the current DeepSeek id");
        assertFalse(
                production.contains("name = \"deepseek-v4-flash\""),
                "production example must not use the retired DeepSeek id");

        assertTrue(production.contains("input-per-1k = 0.0003"), production);
        assertTrue(production.contains("output-per-1k = 0.0012"), production);
        assertTrue(production.contains("cache-read-per-1k = 0.000006"), production);
        assertFalse(production.contains("input-per-1k = 0.00044"), production);
        assertTrue(providers.contains("V4.1-Flash-1017"), providers);

        String k8s = Files.readString(root.resolve("deploy/k8s/configmap.yaml"));
        assertTrue(k8s.contains("name = \"deepseek-flash\""), k8s);
        assertTrue(k8s.contains("input-per-1k = 0.0003"), k8s);
        assertFalse(k8s.contains("input-per-1k = 0.00014"), k8s);

        String nativeLive = Files.readString(root.resolve("scripts/live-provider/config.native-live.toml"));
        assertTrue(nativeLive.contains("name = \"deepseek-flash\""), nativeLive);
        assertTrue(nativeLive.contains("input-per-1k = 0.0003"), nativeLive);
        assertFalse(nativeLive.contains("input-per-1k = 0.00014"), nativeLive);

        String clusterLive = Files.readString(root.resolve("deploy/config/config.cluster.live.toml"));
        assertTrue(clusterLive.contains("name = \"deepseek-flash\""), clusterLive);
        assertTrue(clusterLive.contains("long-context-threshold = 272001"), clusterLive);
    }

    @Test
    void realUpstreamSmokeConfigsUseCurrentDeepSeekAliasAndRates() throws Exception {
        Path root = findRepoRoot();
        for (String relative : List.of(
                "scripts/smoke/openai-face/config.real.toml",
                "scripts/smoke/cross-format/config.real.toml",
                "scripts/smoke/governance/config.real.toml")) {
            String real = Files.readString(root.resolve(relative));
            assertTrue(real.contains("name = \"deepseek-flash\""), relative);
            assertFalse(real.contains("name = \"deepseek-v4-flash\""), relative);
            assertTrue(real.contains("input-per-1k = 0.0003"), relative);
            assertTrue(real.contains("output-per-1k = 0.0012"), relative);
            assertTrue(real.contains("cache-read-per-1k = 0.000006"), relative);
            assertFalse(real.contains("input-per-1k = 0.00014"), relative);
        }

        String openAiReal = Files.readString(root.resolve("scripts/smoke/openai-face/config.real.toml"));
        assertTrue(openAiReal.contains("name = \"deepseek-v4-pro\""), openAiReal);
        assertTrue(openAiReal.contains("input-per-1k = 0.00132"), openAiReal);
        assertTrue(openAiReal.contains("output-per-1k = 0.00396"), openAiReal);
        assertTrue(openAiReal.contains("cache-read-per-1k = 0.000044"), openAiReal);

        String live = Files.readString(
                root.resolve("janus-gateway/src/test/java/io/amscotti/janus/gateway/live/LiveProviderIT.java"));
        assertTrue(
                live.contains("janus.pricing.models[1].cache-read-per-1k\", value = \"0.000044\""),
                "live Pro cache reads must use the official Pro rate");
        assertTrue(
                live.contains("private static final String DEEPSEEK_VISION = DEEPSEEK;"),
                "live vision must exercise native deepseek-flash, not only the retired alias");

        String clusterDrill = Files.readString(root.resolve("scripts/smoke/cluster/drill_live.py"));
        assertTrue(
                clusterDrill.contains("\"vision\": \"deepseek-flash\""),
                "cluster live vision must exercise native deepseek-flash");

        String openAiRunner = Files.readString(root.resolve("scripts/smoke/openai-face/run.sh"));
        assertTrue(openAiRunner.contains("--model deepseek-flash"), openAiRunner);
        String crossRunner = Files.readString(root.resolve("scripts/smoke/cross-format/run.sh"));
        assertTrue(crossRunner.contains("--model deepseek-flash"), crossRunner);
    }

    @Test
    void gptLongContextUsesTheInclusiveShortBoundary() throws Exception {
        Path root = findRepoRoot();
        String config = Files.readString(root.resolve("config.toml"));
        String production = Files.readString(root.resolve("config.production.example.toml"));

        assertTrue(production.contains("long-context-threshold = 272001"), production);
        assertFalse(production.contains("long-context-threshold = 272000"), production);
        assertTrue(config.contains("long-context-threshold = 272001"), config);
        assertFalse(config.contains("long-context-threshold = 272000"), config);
    }

    @Test
    void gptExamplesCarryBothCacheWriteLifetimes() throws Exception {
        Path root = findRepoRoot();
        String config = Files.readString(root.resolve("config.toml"));
        String production = Files.readString(root.resolve("config.production.example.toml"));

        assertTrue(config.contains("cache-creation-12h-per-1k"), config);
        assertTrue(config.contains("long-cache-creation-12h-per-1k"), config);
        assertTrue(production.contains("cache-creation-12h-per-1k"), production);
        assertTrue(production.contains("long-cache-creation-12h-per-1k"), production);
    }

    @Test
    void releaseVersionScriptAcceptsMatchingTagAndRejectsMismatch(@TempDir Path fixture) throws Exception {
        Path root = findRepoRoot();
        String version = Files.readAllLines(root.resolve("gradle.properties")).stream()
                .filter(line -> line.startsWith("version="))
                .map(line -> line.substring("version=".length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("gradle.properties has no version= line"));
        Files.writeString(fixture.resolve("gradle.properties"), "version=" + version + "\n");
        Files.createDirectories(fixture.resolve("janus-gateway/src/main/resources"));
        Files.writeString(
                fixture.resolve("janus-gateway/src/main/resources/application.toml"),
                "[janus]\nname = \"janus\"\nversion = \"" + version + "\"\n");
        Files.writeString(fixture.resolve("config.toml"), "[janus]\nname = \"janus\"\nversion = \"" + version + "\"\n");
        Path script = root.resolve("scripts/verify-release-version.sh");

        assertEquals(0, runVersionScript(script, fixture, "v" + version), "matching version sources must pass");
        assertEquals(
                1,
                runVersionScript(script, fixture, "v" + version + "-mismatch"),
                "version mismatch must fail the release gate");
    }

    private static int runVersionScript(Path script, Path fixture, String tag) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("bash", script.toString(), tag)
                .directory(fixture.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("JANUS_RELEASE_ROOT", fixture.toString());
        return builder.start().waitFor();
    }

    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null && !Files.isRegularFile(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir, "could not locate the repo root (settings.gradle) from user.dir");
        return dir;
    }
}
