package io.amscotti.janus.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

/**
 * Canonical message role shared by the {@link Message} subtypes and {@link Delta}.
 *
 * <p>Serializes to its lowercase wire form ("system", "user", "assistant", "tool",
 * "developer") so the JSON {@code role} property doubles as the polymorphic type id for
 * {@link Message}.
 *
 * <p>{@code DEVELOPER} is the OpenAI-model developer-prompt role. It is a
 * <em>system-ish</em> role: LiteLLM translates it to a system prompt for non-OpenAI
 * providers ({@code map_developer_role_to_system_role}), and the Anthropic leg merges it
 * into the top-level {@code system} field. It is deliberately <em>not</em> in the
 * streaming delta-role whitelist (OpenAI chunks only carry transient
 * {@code assistant}/{@code user} deltas) and has no Anthropic wire home (see
 * {@code docs/compatibility.md}).
 */
public enum ChatRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    DEVELOPER("developer");

    private final String wire;

    ChatRole(String wire) {
        this.wire = wire;
    }

    /** Lowercase JSON form of this role. */
    @JsonValue
    public String wire() {
        return wire;
    }

    /** Inverse of {@link #wire()}; unknown values fail fast at the codec boundary. */
    @JsonCreator
    public static ChatRole fromWire(String value) {
        return Arrays.stream(values())
                .filter(role -> role.wire.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown chat role: " + value));
    }
}
