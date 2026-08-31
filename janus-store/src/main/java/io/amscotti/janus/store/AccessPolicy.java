package io.amscotti.janus.store;

import java.util.List;

/**
 * Per-key model scope (Janus.Keys.Policy, trimmed to the model axis —
 * the ask). Precedence, frozen:
 *
 * <ol>
 * <li><b>Empty allowlist = allow all.</b> {@code allowedModels} empty ⇒ every model
 * is permitted — except a null/blank model, which no scope can permit.
 * <li><b>Deny beats allow.</b> {@code deniedModels} always blocks, even when the
 * allowlist would permit the target.
 * </ol>
 *
 * <p><b>Scope is the client alias, not a resolved backend (documented divergence
 * from the reference implementation semantics).</b> the reference checks the <em>resolved upstream</em>
 * model+provider after route resolution; Janus has no team-level alias
 * remapping — {@code [[janus.model-list]]} aliases <em>are</em> the model names LiteLLM
 * scopes by (LiteLLM virtual-key {@code models} list = proxy model names) — so
 * {@link #authorize} checks {@code ChatRequest.model} (the alias) and a scope entry
 * on alias {@code A} permits exactly the config's {@code A}.
 * future alias→backend remap layer lands (a key scoped to alias {@code A} must not
 * reach a forbidden backend through a different alias pointing at it).
 *
 * <p>Immutable: the constructor defensively copies both lists; accessors return
 * unmodifiable views.
 *
 * @param allowedModels models this key may call; empty = allow all
 * @param deniedModels models this key may never call; deny beats allow
 */
public record AccessPolicy(List<String> allowedModels, List<String> deniedModels) {

    /** The unscoped policy: every (non-blank) model allowed, nothing denied. */
    public static final AccessPolicy ALLOW_ALL = new AccessPolicy(List.of(), List.of());

    public AccessPolicy {
        allowedModels = allowedModels == null ? List.of() : List.copyOf(allowedModels);
        deniedModels = deniedModels == null ? List.of() : List.copyOf(deniedModels);
    }

    /**
     * Is {@code model} permitted? Applies the frozen precedence: denied beats allow,
     * empty allowlist allows all, and a null/blank model is always denied (mirrors the
     * allowed?(_, nil) → false).
     */
    public boolean authorize(String model) {
        if (model == null || model.isBlank()) {
            return false;
        }
        if (deniedModels.contains(model)) {
            return false;
        }
        return allowedModels.isEmpty() || allowedModels.contains(model);
    }
}
