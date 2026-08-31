package io.amscotti.janus.core.codec;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fixture secret-material scan — the credential patterns the fixture
 * manifest guards reject, beyond the original bare {@code sk-}/{@code Authorization}
 * substring checks. Header-name patterns require the colon (or a value) so an upstream
 * error message that merely <em>mentions</em> a header name ("invalid x-api-key", a
 * literal Anthropic 401 text) is not a false positive; the committed corpus is clean
 * under every pattern here.
 */
final class FixtureSecrets {

    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("sk-"), // sk-janus-… / sk-ant-… / sk-… keys (the original guard)
            Pattern.compile("(?i)authorization"), // Authorization header name, any case
            Pattern.compile("(?i)\\bx-api-key\\s*:"), // Anthropic-style credential header
            Pattern.compile("(?i)\\bapi-key\\s*:"), // generic credential header
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]+"), // Bearer <token>
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")); // AWS access-key id

    private FixtureSecrets() {}

    /** The patterns {@code content} matches, in declaration order (empty when clean). */
    static List<String> violations(String content) {
        List<String> found = new ArrayList<>();
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(content).find()) {
                found.add(pattern.pattern());
            }
        }
        return found;
    }
}
