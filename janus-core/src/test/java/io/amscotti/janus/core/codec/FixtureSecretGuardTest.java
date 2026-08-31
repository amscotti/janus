package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The extended fixture secret guard: every credential presentation the
 * manifest guards must be flagged, while the benign texts the corpus legitimately
 * carries (an upstream error message mentioning a header name, e.g. the Anthropic 401
 * fixture's "invalid x-api-key") must pass. This is the negative-probe counterpart to
 * {@link FixtureManifestTest#noFixtureContainsSecretMaterial} /
 * {@link AnthropicFixtureManifestTest#noFixtureContainsSecretMaterial}: the bare
 * substring guard accepted the forms below; the extended scan rejects them.
 */
class FixtureSecretGuardTest {

    @Test
    void everyCredentialFormIsFlagged() {
        List.of(
                        "api-key: sk-ant-my-secret",
                        "x-api-key: sk-ant-my-secret",
                        "Authorization: Bearer sk-ant-my-secret",
                        "authorization: Bearer sk-ant-my-secret",
                        "bearer AKIAIOSFODNN7EXAMPLE",
                        "Bearer sk-janus-abcdefghijklmnopqrstuvwxyz",
                        "sk-ant-api03-secret-value-here",
                        "DEEPSEEK_API_KEY=sk-1234567890abcdef")
                .forEach(sample -> assertTrue(!FixtureSecrets.violations(sample).isEmpty(), "must flag: " + sample));
    }

    @Test
    void benignMentionsOfHeaderNamesAreNotFlagged() {
        List.of(
                        "invalid x-api-key",
                        "the api-key was rejected",
                        "request failed, check your credentials",
                        "no secret material here")
                .forEach(sample ->
                        assertEquals(List.of(), FixtureSecrets.violations(sample), "must not flag: " + sample));
    }
}
