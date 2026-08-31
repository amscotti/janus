package io.amscotti.janus.core.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Canonical chat message. Sealed so the compiler (and GraalVM) sees a closed set of
 * subtypes; polymorphic JSON via the {@code role} property. The type id is emitted as a
 * regular property ({@code As.PROPERTY}) — the derived {@link #role} accessor is not a
 * record component, so Jackson 3 (tools.jackson) will not serialize it under
 * {@code EXISTING_PROPERTY} (a documented design decision).
 *
 * <p><b>Serialization constraint (hard):</b> on Jackson 3 (tools.jackson 3.1.x),
 * interface-level {@code @JsonTypeInfo} is honored only when the static/declared element
 * type is {@link Message} or a subtype — i.e. when serializing through a declared-typed
 * container such as {@link ChatRequest#messages}, {@link ChatResponse#choices}, or
 * {@code writerFor(new TypeReference<List<Message>> {})}. Serializing a <em>bare</em>
 * {@code List<Message>} (or a map of them) by runtime type silently drops the
 * {@code role} discriminator, producing wire output that cannot round-trip (decode fails
 * with {@code InvalidTypeIdException}). Codecs (+) must therefore never serialize
 * bare message lists — always use the declared-typed model containers. Pinned by
 * {@code ModelJsonTest.bareListOfMessagesDropsRoleDiscriminator}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "role")
@JsonSubTypes({
    @JsonSubTypes.Type(value = SystemMessage.class, name = "system"),
    @JsonSubTypes.Type(value = UserMessage.class, name = "user"),
    @JsonSubTypes.Type(value = AssistantMessage.class, name = "assistant"),
    @JsonSubTypes.Type(value = ToolMessage.class, name = "tool"),
    @JsonSubTypes.Type(value = DeveloperMessage.class, name = "developer"),
})
public sealed interface Message permits SystemMessage, UserMessage, AssistantMessage, ToolMessage, DeveloperMessage {

    /** Derived per subtype — constant, never stored. */
    ChatRole role();
}
