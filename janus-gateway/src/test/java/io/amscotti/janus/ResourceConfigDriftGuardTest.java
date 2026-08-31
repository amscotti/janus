package io.amscotti.janus;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drift guard for the gateway's native-image {@code resource-config.json}: the
 * two reflect configs are drift-guarded, but this one was not. It pins:
 *
 * <ol>
 * <li>the {@code db/migration/.*\\.sql} include — every committed Flyway migration must
 * match it (migrations ship from {@code janus-store}, whose resources are folded into the
 * gateway image),
 * <li>the {@code META-INF/services/java.sql.Driver} include — the JDBC {@code ServiceLoader}
 * resource the image needs in postgres mode (shipped inside the postgres driver jar, not
 * from any module's {@code src/main/resources}), and
 * <li>the {@code micronaut-banner.txt} include — the custom boot banner (Micronaut 5
 * loads this classpath resource in place of the default {@code MicronautBanner}; the
 * native image would otherwise keep Micronaut's mark or print nothing).
 * </ol>
 *
 * <p>The patterns are wildcards, so new migrations are auto-included and the drift surface
 * is small; this guard closes the remaining gap cheaply — a new resource type (a classpath
 * cert, a truststore, …) added without a resource-config entry fails here rather than only
 * at the slow native gate.
 */
class ResourceConfigDriftGuardTest {

    private static final String CONFIG_PATH =
            "META-INF/native-image/io.amscotti.janus/janus-gateway/resource-config.json";
    private static final String MIGRATION_PATTERN = "db/migration/.*\\.sql";
    private static final String DRIVER_PATTERN = "META-INF/services/java.sql.Driver";
    private static final String BANNER_PATTERN = "micronaut-banner\\.txt";
    private static final String BANNER_RESOURCE = "micronaut-banner.txt";

    @Test
    void resourceConfigPinsTheMigrationJdbcDriverAndBannerPatterns() throws Exception {
        JsonNode includes = readConfig().get("resources").get("includes");
        assertNotNull(includes, "resource-config.json must declare resources.includes");
        boolean hasMigration = false;
        boolean hasDriver = false;
        boolean hasBanner = false;
        for (JsonNode include : includes) {
            String pattern = include.get("pattern").asString();
            hasMigration |= MIGRATION_PATTERN.equals(pattern);
            hasDriver |= DRIVER_PATTERN.equals(pattern);
            hasBanner |= BANNER_PATTERN.equals(pattern);
        }
        assertTrue(hasMigration, "resource-config.json must include the " + MIGRATION_PATTERN + " pattern");
        assertTrue(hasDriver, "resource-config.json must include the " + DRIVER_PATTERN + " pattern");
        assertTrue(hasBanner, "resource-config.json must include the " + BANNER_PATTERN + " pattern");
    }

    @Test
    void customBannerIsOnTheClasspathAndSpellsJanus() throws Exception {
        try (InputStream in =
                ResourceConfigDriftGuardTest.class.getClassLoader().getResourceAsStream(BANNER_RESOURCE)) {
            assertNotNull(in, "micronaut-banner.txt must be on the classpath (Micronaut 5 override)");
            String banner = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(
                    banner.contains("| | __ _ _ __"),
                    "micronaut-banner.txt must be the Janus wordmark, not the default Micronaut banner");
        }
    }

    /** Every committed migration must match the {@code db/migration/.*\\.sql} include. */
    @Test
    void everyCommittedMigrationMatchesTheIncludedPattern() throws Exception {
        Path repoRoot = findRepoRoot();
        Path resources = repoRoot.resolve("janus-store/src/main/resources");
        try (Stream<Path> files = Files.walk(resources)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .forEach(sql -> {
                        String resourceName =
                                resources.relativize(sql).toString().replace('\\', '/');
                        assertTrue(
                                resourceName.matches(MIGRATION_PATTERN),
                                "migration '" + resourceName + "' is not covered by the included pattern '"
                                        + MIGRATION_PATTERN + "' — the native image would miss it");
                    });
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

    private static JsonNode readConfig() throws Exception {
        try (InputStream in =
                ResourceConfigDriftGuardTest.class.getClassLoader().getResourceAsStream(CONFIG_PATH)) {
            assertNotNull(in, "resource-config.json must be on the test classpath");
            return JsonMapper.builder().build().readTree(in);
        }
    }
}
