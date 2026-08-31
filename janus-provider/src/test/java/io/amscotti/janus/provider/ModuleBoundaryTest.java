package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Module-boundary enforcement (previously convention
 * only, via the Gradle dependency DAG + a manual grep): a mechanical source-scan test
 * that janus-provider main sources never import a Micronaut/Micrometer class (only
 * the gateway gets Micrometer; provider stays Micronaut-free) or another janus module's
 * package (provider depends on core only — {@code io.amscotti.janus.core} + its own
 * {@code io.amscotti.janus.provider} root are the only janus imports allowed). A future
 * {@code io.micronaut} or cross-module {@code io.amscotti.janus.router/...} import fails
 * here at test time instead of at a slow nativeCompile gate.
 */
class ModuleBoundaryTest {

    private static final List<String> FORBIDDEN_EXTERNAL = List.of("io.micronaut", "io.micrometer");
    /** provider depends on core only — core + this module's own root are the janus imports. */
    private static final List<String> ALLOWED_INTERNAL =
            List.of("io.amscotti.janus.core", "io.amscotti.janus.provider");

    @Test
    void mainSourcesImportNoMicronautMicrometerOrOtherModuleInternals() throws Exception {
        try (Stream<Path> walk = Files.walk(Path.of(System.getProperty("user.dir"), "src", "main", "java"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String content = Files.readString(file);
                for (String line : content.split("\n")) {
                    String trimmed = line.strip();
                    if (!trimmed.startsWith("import ")) {
                        continue;
                    }
                    String imported = trimmed.substring("import ".length())
                            .replaceFirst("static\\s+", "")
                            .strip();
                    for (String forbidden : FORBIDDEN_EXTERNAL) {
                        assertTrue(
                                !imported.startsWith(forbidden),
                                file.getFileName() + " imports forbidden " + forbidden);
                    }
                    if (imported.startsWith("io.amscotti.janus.")) {
                        assertTrue(
                                ALLOWED_INTERNAL.stream().anyMatch(imported::startsWith),
                                file.getFileName() + " imports cross-module internals: " + imported);
                    }
                }
            }
        }
    }
}
