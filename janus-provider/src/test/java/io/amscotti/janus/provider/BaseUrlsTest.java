package io.amscotti.janus.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link BaseUrls} is the single home for
 * the provider base-URL rule both adapters apply, and the only gate between operator
 * TOML config and the {@code java.net.http.HttpClient} dispatch. The re-pass pins two
 * properties here: the / normalization contract (trailing slashes and a single
 * trailing {@code /v1} stripped) and the scheme guard — {@code base-url} may only
 * name {@code http} or {@code https} endpoints, so a misconfigured (or compromised)
 * config file cannot turn the gateway into an SSRF relay to {@code file:}, {@code
 * ftp:}, {@code gopher:}, intranet {@code http://} hosts are operator-owned (the
 * documented trust boundary), but scheme-less and non-http(s) URLs fail fast at boot.
 */
class BaseUrlsTest {

    @Test
    void normalizesTrailingSlashesAndV1Suffix() {
        assertEquals("https://api.deepseek.com", BaseUrls.normalize("https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com", BaseUrls.normalize("https://api.deepseek.com/"));
        assertEquals("https://api.deepseek.com", BaseUrls.normalize("https://api.deepseek.com/v1"));
        assertEquals("https://api.deepseek.com", BaseUrls.normalize("https://api.deepseek.com/v1/"));
    }

    @Test
    void keepsHttpLocalhostAndPaths() {
        assertEquals("http://127.0.0.1:9877", BaseUrls.normalize("http://127.0.0.1:9877"));
        assertEquals("http://127.0.0.1:9877/base", BaseUrls.normalize("http://127.0.0.1:9877/base"));
    }

    @Test
    void preservesDeepSeekAnthropicPrefix() {
        // DeepSeek's Anthropic-format base is /anthropic, not /v1. The adapter
        // appends /v1/messages, so this prefix must survive normalize.
        assertEquals("https://api.deepseek.com/anthropic", BaseUrls.normalize("https://api.deepseek.com/anthropic"));
        assertEquals("https://api.deepseek.com/anthropic", BaseUrls.normalize("https://api.deepseek.com/anthropic/"));
    }

    @Test
    void stripsV1CaseInsensitivelyAndRepeatedly() {
        // The /v1 suffix strip is case-insensitive and repeats, so /V1 and /v1/v1
        // both collapse to the host — each adapter appends its own versioned path.
        assertEquals("https://api.deepseek.com", BaseUrls.normalize("https://api.deepseek.com/V1"));
        assertEquals("https://api.deepseek.com", BaseUrls.normalize("https://api.deepseek.com/v1/v1"));
        assertEquals("https://api.deepseek.com", BaseUrls.normalize("https://api.deepseek.com/v1/V1/"));
        assertEquals("http://127.0.0.1:9877/base", BaseUrls.normalize("http://127.0.0.1:9877/base/V1"));
    }

    @Test
    void acceptsSchemeCaseInsensitively() {
        assertEquals("HTTP://api.deepseek.com", BaseUrls.normalize("HTTP://api.deepseek.com"));
        assertEquals("HtTpS://api.deepseek.com", BaseUrls.normalize("HtTpS://api.deepseek.com"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("ftp://files.example.com/pub"));
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("gopher://example.com/1"));
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("mailto:admin@example.com"));
    }

    @Test
    void rejectsOpaqueAndHostlessHttpUris() {
        // The scheme guard alone passes these — no routable host, would only
        // fail at first dispatch with an unhelpful connect error.
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("http:example.com"));
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("https:///path"));
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("http://"));
    }

    @Test
    void rejectsSchemeLessUrls() {
        // "api.deepseek.com" is a valid URI with no scheme — a bare hostname would
        // silently resolve against the process's default (SSRF-adjacent); fail fast.
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("api.deepseek.com"));
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("//internal.example.com/chat"));
    }

    @Test
    void rejectsBlankAndMalformed() {
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize(""));
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("   "));
        assertThrows(IllegalArgumentException.class, () -> BaseUrls.normalize("http://exa mple.com"));
    }
}
