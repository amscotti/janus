package io.amscotti.janus.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AccessPolicy} model scope, the Janus.Keys.Policy
 * precedence rules 2–3 trimmed to the model axis:
 *
 * <ol>
 * <li><b>Empty allowlist = allow all.</b> An unscoped key "just works" — but never
 * for a null/blank model (no scope can permit "no model").
 * <li><b>Deny beats allow.</b> {@code deniedModels} always blocks, even when the
 * allowlist would permit the target.
 * </ol>
 *
 * <p><b>Scope is the client alias, not a resolved backend (documented divergence
 * from the reference implementation semantics).</b> Janus has no team-level alias
 * remapping: {@code [[janus.model-list]]} aliases <em>are</em> the model names LiteLLM
 * scopes by, so {@link #authorize} checks the alias the client sent
 * ({@code ChatRequest.model}) — the LiteLLM-aligned choice. If a future alias→backend
 * remap layer lands, re-check: a key scoped to alias A must not reach a
 * forbidden backend through a different alias pointing at it).
 *
 * <p>Immutable: the constructor defensively copies both lists; mutating the caller's
 * lists after construction has no effect, and the accessors return unmodifiable views.
 */
class AccessPolicyTest {

    @Test
    void emptyAllowlistAllowsAll() {
        AccessPolicy policy = new AccessPolicy(List.of(), List.of());
        assertTrue(policy.authorize("deepseek-v4-flash"));
        assertTrue(policy.authorize("anything-at-all"));
    }

    @Test
    void allowlistAllowsListedAliasesAndDeniesUnlisted() {
        AccessPolicy policy = new AccessPolicy(List.of("deepseek-v4-flash", "deepseek-v4-pro"), List.of());
        assertTrue(policy.authorize("deepseek-v4-flash"));
        assertTrue(policy.authorize("deepseek-v4-pro"));
        assertFalse(policy.authorize("gpt-4"));
    }

    @Test
    void deniedModelsBeatAllowlist() {
        AccessPolicy policy = new AccessPolicy(List.of("deepseek-v4-flash", "gpt-4"), List.of("gpt-4"));
        assertTrue(policy.authorize("deepseek-v4-flash"), "allowlisted and not denied → allowed");
        assertFalse(policy.authorize("gpt-4"), "deny beats allow");
    }

    @Test
    void deniedModelsBlockEvenWithEmptyAllowlist() {
        AccessPolicy policy = new AccessPolicy(List.of(), List.of("gpt-4"));
        assertTrue(policy.authorize("deepseek-v4-flash"));
        assertFalse(policy.authorize("gpt-4"));
    }

    @Test
    void nullAndBlankModelsAreRejected() {
        AccessPolicy allowAll = AccessPolicy.ALLOW_ALL;
        assertFalse(allowAll.authorize(null), "a null model cannot be permitted by any scope");
        assertFalse(allowAll.authorize(""), "a blank model cannot be permitted by any scope");
        assertFalse(allowAll.authorize("   "), "a blank model cannot be permitted by any scope");
    }

    @Test
    void constructorCopiesCallerLists() {
        List<String> mutable = new ArrayList<>(List.of("deepseek-v4-flash"));
        AccessPolicy policy = new AccessPolicy(mutable, mutable);
        mutable.add("gpt-4");
        assertFalse(policy.authorize("gpt-4"), "mutating the caller's list after construction must not leak");
    }

    @Test
    void accessorsAreUnmodifiable() {
        AccessPolicy policy = new AccessPolicy(List.of("deepseek-v4-flash"), List.of());
        assertThrows(
                UnsupportedOperationException.class,
                () -> policy.allowedModels().add("x"));
        assertThrows(
                UnsupportedOperationException.class, () -> policy.deniedModels().add("x"));
    }

    @Test
    void duplicateModelsEntriesAreHarmless() {
        // A duplicated allowlist entry is just a redundant element — authorize is
        // membership-based, so the duplicate must neither deny nor change the outcome.
        AccessPolicy policy = new AccessPolicy(List.of("gpt-4", "gpt-4", "gpt-4o"), List.of());
        assertTrue(policy.authorize("gpt-4"), "a duplicated allowed entry stays allowed");
        assertTrue(policy.authorize("gpt-4o"));
        assertFalse(policy.authorize("gpt-4-turbo"), "unlisted models stay denied");
    }

    @Test
    void matchingIsExactCaseSensitive() {
        // Scope matching is exact-case (a model string is an opaque alias, not a regex):
        // a case variant of an allowlisted alias is a different model name.
        AccessPolicy policy = new AccessPolicy(List.of("gpt-4"), List.of());
        assertTrue(policy.authorize("gpt-4"));
        assertFalse(policy.authorize("Gpt-4"), "case variants are different model names");
        assertFalse(policy.authorize("GPT-4"));
        assertFalse(policy.authorize("GPT-4 "));
    }

    @Test
    void keyRecordAccessPolicyAlwaysCarriesAnEmptyDenyList() {
        // The admin API scopes by `models` only, so every KeyRecord-derived policy has an
        // empty deny list (deny-beats-allow is carried structurally by AccessPolicy for
        // future scope surfaces) — pinned so the documented shape cannot drift.
        KeyRecord record = new KeyRecord(
                "id",
                "prefix",
                new byte[16],
                new byte[32],
                "owner",
                List.of("gpt-4"),
                KeyStatus.ACTIVE,
                0L,
                null,
                null,
                null,
                null,
                null,
                null);
        AccessPolicy policy = record.accessPolicy();
        assertTrue(policy.deniedModels().isEmpty(), "KeyRecord.accessPolicy() deny list is structurally empty");
        assertEquals(List.of("gpt-4"), policy.allowedModels());
        assertTrue(policy.authorize("gpt-4"));
        assertFalse(policy.authorize("gpt-4o"));
    }
}
