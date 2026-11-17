package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Module-boundary enforcement: a mechanical source-scan test that janus-store main
 * sources never reference a Micronaut/Micrometer class (neither via {@code import} nor
 * via inline FQCNs) or another janus module's package (store depends on core only —
 * {@code io.amscotti.janus.core} + its own {@code io.amscotti.janus.store} root).
 */
class ModuleBoundaryTest {

    private static final List<String> FORBIDDEN_EXTERNAL = List.of("io.micronaut", "io.micrometer");
    /** store depends on core only — core + this module's own root are the janus references. */
    private static final List<String> ALLOWED_INTERNAL = List.of("io.amscotti.janus.core", "io.amscotti.janus.store");

    private static final Pattern QUALIFIED_IDENTIFIER =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    @Test
    void mainSourcesImportNoMicronautMicrometerOrOtherModuleInternals() throws Exception {
        try (Stream<Path> walk = Files.walk(Path.of(System.getProperty("user.dir"), "src", "main", "java"))) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                assertSourceRespectsBoundaries(file.getFileName().toString(), Files.readString(file));
            }
        }
    }

    @Test
    void scannerCatchesInlineFqcnsWhileIgnoringCommentsAndStrings() {
        assertThrows(
                AssertionError.class,
                () -> assertSourceRespectsBoundaries(
                        "Inline.java", "class Inline { Object x = io.micronaut.http.HttpStatus.OK; }"));
        assertThrows(
                AssertionError.class,
                () -> assertSourceRespectsBoundaries(
                        "Cross.java", "class Cross { void f() { io.amscotti.janus.router.Router r = null; } }"));
        assertSourceRespectsBoundaries(
                "Clean.java",
                "/* io.micronaut.http */ class Clean { String s = \"io.amscotti.janus.router.Router\"; // io.micrometer\n }");
    }

    private static void assertSourceRespectsBoundaries(String fileName, String content) {
        String code = stripCommentsAndLiterals(content);
        Matcher matcher = QUALIFIED_IDENTIFIER.matcher(code);
        while (matcher.find()) {
            String ref = matcher.group();
            for (String forbidden : FORBIDDEN_EXTERNAL) {
                assertTrue(
                        !ref.equals(forbidden) && !ref.startsWith(forbidden + "."),
                        fileName + " references forbidden " + forbidden + ": " + ref);
            }
            if (ref.startsWith("io.amscotti.janus.")) {
                assertTrue(
                        ALLOWED_INTERNAL.stream()
                                .anyMatch(allowed -> ref.equals(allowed) || ref.startsWith(allowed + ".")),
                        fileName + " references cross-module internals: " + ref);
            }
        }
    }

    private static String stripCommentsAndLiterals(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        for (int i = 0; i < n; ) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                i += 2;
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                i += 3;
                while (i + 2 < n && !(src.charAt(i) == '"' && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                    i += (src.charAt(i) == '\\') ? 2 : 1;
                }
                i = Math.min(n, i + 3);
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && src.charAt(i) != quote) {
                    i += (src.charAt(i) == '\\') ? 2 : 1;
                }
                i = Math.min(n, i + 1);
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
