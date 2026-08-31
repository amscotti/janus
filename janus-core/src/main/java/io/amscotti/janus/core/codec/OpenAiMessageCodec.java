package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.ContentPart;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.DeveloperMessage;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.ImageSourceContent;
import io.amscotti.janus.core.model.ImageUrlContent;
import io.amscotti.janus.core.model.Message;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.SystemMessage;
import io.amscotti.janus.core.model.TextContent;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.ToolDefinition;
import io.amscotti.janus.core.model.ToolMessage;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Canonical ↔ OpenAI wire-format translator. Owns every OpenAI Chat Completions
 * wire shape: request, response and SSE delta chunk.
 *
 * <p><b>Mapper contract.</b> The constructor-injected {@link ObjectMapper} must be
 * configured for the wire format the DTOs assume: snake_case naming
 * ({@code PropertyNamingStrategies.SNAKE_CASE}), {@code FAIL_ON_UNKNOWN_PROPERTIES}
 * disabled (tolerant decode), {@code FAIL_ON_NULL_FOR_PRIMITIVES} disabled (absent
 * {@code stream}/{@code created} default instead of failing) and
 * {@code ACCEPT_SINGLE_VALUE_AS_ARRAY} enabled (OpenAI's {@code stop} accepts a bare
 * string). {@link #create} provides exactly that mapper; the gateway must pass
 * an equivalent one. DTOs additionally carry {@code @JsonInclude(NON_NULL)} so encode
 * output is skip-null regardless of the injected mapper's inclusion config.
 *
 * <p><b>State hazard discipline.</b> The codec never serializes canonical types — mapping
 * is explicit record-to-record, and every DTO list is declared-typed. The wire
 * discriminators ({@code role} per message, {@code index} per choice) therefore always
 * survive; {@code CodecWireShapeGuardTest} pins that contract.
 *
 * <p><b>Pass-through contract.</b> Unknown top-level fields (and unknown message,
 * choice and delta fields) ride the canonical {@code extras} maps on decode and are
 * merged back into the outbound payload at the top level on encode. On collision the
 * mapped (gateway) field wins — the merge_extras precedence
 * ({@code Map.merge(extras, base, gateway)}). Consequence (documented, pinned by test):
 * nested unknowns (choice/message-level) re-emerge as <em>top-level</em> fields —
 * values survive a round-trip, position does not. {@code meta} is never read and never
 * emitted.
 *
 * <p>Three scoped exceptions to the top-level hoist, each a real schema-legal wire field
 * with a canonical home:
 *
 * <ul>
 * <li><b>Message {@code name}</b> — a legal OpenAI per-message field ({@code system}/
 * {@code user}/{@code assistant}/{@code tool}). It has a per-message canonical home
 * ({@code SystemMessage.name}/{@code UserMessage.name}/
 * {@code AssistantMessage.name}/{@code ToolMessage.name}); the codec maps it from/to
 * {@code OpenAiMessage.name} and re-emits it inside the message. It is never folded
 * into the request {@code extras} (re-emitting it directly would produce a bogus
 * top-level field that strict OpenAI upstreams reject). The
 * Anthropic wire has no per-message name home — the value is dropped on the
 * Anthropic leg (documented non-idempotence).
 * <li><b>Delta-level unknowns</b> (first-class DeepSeek {@code delta.reasoning_content})
 * — captured into {@link io.amscotti.janus.core.model.Delta#reasoning} and
 * re-emitted <em>inside</em> the delta, so reasoning content stays visible to
 * DeepSeek SDK clients and never leaks as a top-level chunk field toward a strict
 * OpenAI upstream.
 * <li><b>{@link ToolChoiceMapper#EXTRAS_DISABLE_PARALLEL}</b> — an Anthropic-only
 * {@code tool_choice} knob with no OpenAI meaning; the OpenAI encode consumes
 * (drops) it from the extras copy instead of emitting it top-level.
 * </ul>
 *
 * <p>Scope note: DTOs without an extras capture — {@code OpenAiFunctionCall},
 * {@code OpenAiToolCall}, {@code OpenAiUsage} (whose cache fields {@code prompt_tokens_details}
 * / {@code prompt_cache_hit_tokens} are modeled), {@code OpenAiChunkChoice} — silently
 * drop unknown fields at their level (tool-call/usage/choice-level unknowns have no canonical
 * home); accepted.
 *
 * <p><b>Outbound format fields.</b> The OpenAI wire's {@code object} is a
 * constant on both the response and chunk shapes ({@code "chat.completion"} /
 * {@code "chat.completion.chunk"}): encode <em>always</em> emits the constant, so an
 * Anthropic-derived canonical (whose {@code object} is the wire type
 * {@code "message"} — the documented format mutation) never leaks the
 * Anthropic format field onto the OpenAI face (the pinned OpenAI SDK types
 * {@code object} as a Literal and rejects it). {@code created} passes through
 * deterministically (Anthropic-derived 0 is a valid epoch-second int).
 *
 * <p><b>Tool shapes.</b> Tool definitions map to/from the canonical
 * {@code ToolCall} with {@code FunctionCall.description} carrying the description (the
 * {@code extras["description"]} hack is deleted); {@code toolChoice} normalizes
 * through {@link ToolChoiceMapper} so Anthropic-idiomatic values that reach canonical
 * via an Anthropic-sourced request are re-expressed in OpenAI-idiomatic form on
 * encode/decode; stop reasons route through {@link StopReasonTable} (adds the OpenAI
 * {@code "function_call"} → {@code tool_calls} alias from the reference; unknown values pass
 * through verbatim).
 *
 * <p><b>System handling (pinned by tests).</b> The canonical home for the system prompt
 * is {@code ChatRequest.system}. On decode, every <em>unnamed</em> {@code role:"system"}
 * message is extracted, joined with {@code "\n\n"} and dropped from {@code messages}; a
 * system message that carries a {@code name} keeps its per-message canonical home
 * ({@code SystemMessage.name}) and stays in {@code messages}. On encode,
 * {@code system} and any unnamed {@code SystemMessage}s in {@code messages} merge into a
 * single leading system message (no duplication); named {@code SystemMessage}s keep
 * their position in the messages array with {@code name} inside the message object.
 * Consequence: a canonical request carrying <em>both</em> {@code system} and an unnamed
 * {@code SystemMessage} in {@code messages} is not round-trip idempotent — encode merges
 * them into one leading wire message and decode yields the joined text in {@code system}
 * with the {@code SystemMessage} gone (pinned by
 * {@code systemFieldPlusSystemMessageIsNotRoundTripIdempotent}). A named system message
 * is round-trip idempotent (decode → encode → decode preserves it).
 */
public final class OpenAiMessageCodec {

    private static final String SYSTEM_SEPARATOR = "\n\n";
    private static final String DEFAULT_CHUNK_ID = "chatcmpl-janus";
    private static final String DEFAULT_CHUNK_OBJECT = "chat.completion.chunk";
    private static final String DEFAULT_RESPONSE_OBJECT = "chat.completion";
    private static final String DEFAULT_CHUNK_MODEL = "unknown";

    /**
     * Wire keys the request DTO owns (mapped fields). {@code writeWithExtras} drops
     * extras entries colliding with these even when the DTO omits the member (e.g.
     * {@code stream:false} is emitted absent) so the mapped-field-wins contract holds
     * for absent-optional fields too.
     */
    private static final Set<String> REQUEST_MAPPED_KEYS = Set.of(
            "model",
            "messages",
            "tools",
            "tool_choice",
            "stream",
            "stream_options",
            "temperature",
            "top_p",
            "top_k",
            "max_completion_tokens",
            "max_tokens",
            "stop",
            "seed",
            "n",
            "frequency_penalty",
            "presence_penalty",
            "logit_bias",
            "response_format",
            "reasoning_effort",
            "web_search_options",
            "prompt_cache_breakpoint");

    /** Wire keys the response DTO owns. */
    private static final Set<String> RESPONSE_MAPPED_KEYS =
            Set.of("id", "object", "created", "model", "choices", "usage");

    /** Wire keys the chunk DTO owns. */
    private static final Set<String> CHUNK_MAPPED_KEYS = Set.of("id", "object", "created", "model", "choices", "usage");

    private final ObjectMapper mapper;

    public OpenAiMessageCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Default mapper per the codec's contract (snake_case, tolerant, single-value arrays). */
    public static OpenAiMessageCodec create() {
        return new OpenAiMessageCodec(JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .build());
    }

    // ------------------------------------------------------------------ request

    public ChatRequest decodeRequest(String json) throws OpenAiCodecException {
        if (json == null || json.isBlank()) {
            // An empty/missing body (Micronaut passes null for a body-less
            // POST) must be a client 400, never a mapper IllegalArgumentException → 500.
            throw codec(OpenAiCodecException.TYPE_INVALID_REQUEST, "request body must be a JSON object");
        }
        JsonNode root = readTree(json, OpenAiCodecException.TYPE_INVALID_REQUEST);
        rejectArrayFormContent(root, "request");
        OpenAiChatRequest dto;
        try {
            dto = mapper.treeToValue(root, OpenAiChatRequest.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(OpenAiCodecException.TYPE_INVALID_REQUEST, "invalid OpenAI request: " + e.getMessage(), e);
        }
        return toCanonicalRequest(dto);
    }

    public String encodeRequest(ChatRequest canonical) throws OpenAiCodecException {
        validateRequest(canonical);
        // Chat completions cannot HOST tools — real OpenAI rejects
        // the LiteLLM-bridge web_search_options param ("Unknown parameter", live-
        // verified 2026-08), so deriving it would only become that upstream 400.
        // Translate-or-throw: the named invalid-request error tells
        // the client where the tool IS served. The Anthropic egress translates it to
        // the web_search_20250305 server tool (the ra leg, live-verified).
        if (canonical.hostedTools() != null && !canonical.hostedTools().isEmpty()) {
            throw codec(
                    OpenAiCodecException.TYPE_INVALID_REQUEST,
                    "unsupported_hosted_tool: web_search — hosted tools are served on "
                            + "Anthropic-format upstreams only (chat-completions upstreams cannot host tools)");
        }
        List<String> systemParts = new ArrayList<>();
        if (nonBlank(canonical.system())) {
            systemParts.add(canonical.system());
        }
        boolean emitBreakpoints = PromptCache.supportsExplicitOpenAiBreakpoints(canonical.model());
        boolean emitCacheControl = PromptCache.supportsOpenAiWireCacheControl(canonical.model());
        List<OpenAiMessage> dtoMessages = new ArrayList<>();
        for (Message message : canonical.messages()) {
            if (message instanceof SystemMessage system) {
                // A named system message has a per-message canonical home and keeps
                // its position in the messages array (name emitted inside the message);
                // unnamed system messages merge into the single leading system message.
                if (system.name() != null) {
                    dtoMessages.add(toOpenAiMessage(system, emitBreakpoints, emitCacheControl));
                } else if (nonBlank(system.content())) {
                    systemParts.add(system.content());
                }
            } else {
                dtoMessages.add(toOpenAiMessage(message, emitBreakpoints, emitCacheControl));
            }
        }
        boolean systemGetsMarker = (emitBreakpoints || emitCacheControl)
                && PromptCache.isEphemeral(canonical.cacheControl())
                && !systemParts.isEmpty();
        if (!systemParts.isEmpty()) {
            String joined = String.join(SYSTEM_SEPARATOR, systemParts);
            Object systemContent;
            if (systemGetsMarker && emitBreakpoints) {
                systemContent = PromptCache.openAiTextWithBreakpoint(joined);
            } else if (systemGetsMarker) {
                systemContent = PromptCache.openAiTextWithCacheControl(joined, canonical.cacheControl());
            } else {
                systemContent = joined;
            }
            dtoMessages.add(0, new OpenAiMessage("system", systemContent, null, null, null, null));
        } else if ((emitBreakpoints || emitCacheControl)
                && PromptCache.isEphemeral(canonical.cacheControl())
                && !alreadyHasOpenAiBreakpoint(dtoMessages)) {
            placeFallbackCacheMarker(dtoMessages, emitBreakpoints);
        }
        OpenAiChatRequest dto = new OpenAiChatRequest(
                canonical.model(),
                dtoMessages,
                toOpenAiTools(canonical.tools()),
                ToolChoiceMapper.normalizeOpenAi(canonical.toolChoice()),
                canonical.stream() ? Boolean.TRUE : null,
                canonical.streamOptions(),
                canonical.temperature(),
                canonical.topP(),
                // top_k is intentionally NOT emitted on the OpenAI-compatible egress:
                // strict OpenAI upstreams 400 it ("Unknown parameter"), so an
                // Anthropic-face client's top_k (legal there) must not leak through
                // cross-format. "top_k" stays in REQUEST_MAPPED_KEYS so a client-sent
                // extras entry is suppressed too — the knob is honored only on
                // Anthropic legs (where it is native).
                null,
                canonical.maxTokens(),
                canonical.stop(),
                canonical.seed(),
                canonical.n(),
                canonical.frequencyPenalty(),
                canonical.presencePenalty(),
                canonical.logitBias(),
                canonical.responseFormat(),
                reasoningEffortOf(canonical.reasoning()),
                null);
        // The Anthropic-only disable_parallel_tool_use flag has no OpenAI
        // meaning — drop it from the extras copy so it never leaks as a top-level field on
        // the OpenAI wire (real Anthropic-sourced requests carry it in canonical extras).
        Map<String, Object> wireExtras = new HashMap<>(canonical.extras());
        wireExtras.remove(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL);
        // Replay-only reasoning payloads have no chat-completions home and 400
        // several OpenAI-compatible upstreams. Request-level `thinking` (DeepSeek
        // enable/disable) stays. chat_template_kwargs stays.
        wireExtras.remove("reasoning_content");
        wireExtras.remove("reasoning_details");
        wireExtras.remove("enable_thinking");
        PromptCache.stripUnsupportedOpenAiCacheFields(wireExtras, canonical.model());
        if (emitBreakpoints && PromptCache.isEphemeral(canonical.cacheControl())) {
            PromptCache.ensureExplicitOptions(wireExtras);
        }
        return writeWithExtras(dto, wireExtras, REQUEST_MAPPED_KEYS, OpenAiCodecException.TYPE_INVALID_REQUEST);
    }

    /**
     * The canonical reasoning map's {@code effort} entry as the chat-wire
     * {@code reasoning_effort} string — read <b>structurally</b> (only the literal
     * {@code "effort"} key, only a String value). An Anthropic-shaped map
     * ({@code {type, budget_tokens}}) has no oo home and is documented-dropped: it must
     * not fabricate a bogus reasoning_effort.
     */
    private static String reasoningEffortOf(Map<String, Object> reasoning) {
        if (reasoning == null) {
            return null;
        }
        Object effort = reasoning.get("effort");
        return effort instanceof String text && !text.isBlank() ? text : null;
    }

    // ----------------------------------------------------------------- response

    public ChatResponse decodeResponse(String json) throws OpenAiCodecException {
        JsonNode root = readTree(json, OpenAiCodecException.TYPE_API_ERROR);
        rejectArrayFormContent(root, "response");
        OpenAiChatResponse dto;
        try {
            dto = mapper.treeToValue(root, OpenAiChatResponse.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(OpenAiCodecException.TYPE_API_ERROR, "invalid OpenAI response: " + e.getMessage(), e);
        }
        return toCanonicalResponse(dto);
    }

    public String encodeResponse(ChatResponse canonical) throws OpenAiCodecException {
        validateResponse(canonical);
        List<OpenAiChoice> dtoChoices = new ArrayList<>();
        for (ChatChoice choice : canonical.choices()) {
            dtoChoices.add(new OpenAiChoice(
                    choice.index(),
                    toOpenAiResponseMessage(choice.message()),
                    finishReasonFor(choice.finishReason(), canonical.stopReason()),
                    null));
        }
        OpenAiChatResponse dto = new OpenAiChatResponse(
                canonical.id(),
                // D2 (blessed fix): the OpenAI wire's object is the constant
                // "chat.completion" — an Anthropic-derived canonical carries "message"
                // (the wire type) and the pinned OpenAI SDK rejects it
                // (Literal["chat.completion"]). The OpenAI face must not leak the
                // Anthropic format field; created stays deterministic (Anthropic-derived
                // 0 is a valid epoch-second int for the SDK — pinned by the matrix).
                DEFAULT_RESPONSE_OBJECT,
                canonical.created(),
                canonical.model(),
                dtoChoices,
                canonical.usage() == null ? null : toOpenAiUsage(canonical.usage()),
                null);
        return writeWithExtras(dto, canonical.extras(), RESPONSE_MAPPED_KEYS, OpenAiCodecException.TYPE_API_ERROR);
    }

    // -------------------------------------------------------------------- chunk

    public StreamChunk decodeChunk(String json) throws OpenAiCodecException {
        JsonNode root = readTree(json, OpenAiCodecException.TYPE_API_ERROR);
        rejectArrayFormContent(root, "chunk");
        OpenAiChunk dto;
        try {
            dto = mapper.treeToValue(root, OpenAiChunk.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(OpenAiCodecException.TYPE_API_ERROR, "invalid OpenAI chunk: " + e.getMessage(), e);
        }
        return toCanonicalChunk(dto);
    }

    public String encodeChunk(StreamChunk canonical) throws OpenAiCodecException {
        validateChunk(canonical);
        List<OpenAiChunkChoice> dtoChoices = new ArrayList<>();
        for (ChunkChoice choice : canonical.choices()) {
            Delta delta = choice.delta();
            OpenAiDelta dtoDelta = new OpenAiDelta(
                    delta == null || delta.role() == null ? null : delta.role().wire(),
                    delta == null ? null : delta.content(),
                    toOpenAiToolCalls(delta == null ? null : delta.toolCalls(), 0),
                    // Delta-level reasoning content (DeepSeek
                    // reasoning_content) re-emits inside the delta — never top-level.
                    delta == null ? null : delta.reasoning());
            dtoChoices.add(new OpenAiChunkChoice(
                    choice.index(),
                    dtoDelta,
                    // (mirrors finishReasonFor on the response path): the canonical
                    // finish reason maps through the OpenAI table so Anthropic-derived
                    // "refusal"/"pause_turn" never leak as invalid OpenAI finish_reason.
                    choice.finishReason() == null ? null : toOpenAiFinishReason(choice.finishReason())));
        }
        OpenAiChunk dto = new OpenAiChunk(
                nonBlank(canonical.id()) ? canonical.id() : DEFAULT_CHUNK_ID,
                // D2 (blessed fix): the OpenAI wire's chunk object is the constant
                // "chat.completion.chunk" — an Anthropic-derived canonical chunk carries
                // "message" (from message_start) and the pinned OpenAI SDK rejects it
                // (Literal["chat.completion.chunk"]); the OpenAI face must not leak the
                // Anthropic format field (mirrors the reference face's constant emission).
                DEFAULT_CHUNK_OBJECT,
                canonical.created(),
                nonBlank(canonical.model()) ? canonical.model() : DEFAULT_CHUNK_MODEL,
                dtoChoices,
                canonical.usage() == null ? null : toOpenAiUsage(canonical.usage()),
                null);
        return writeWithExtras(dto, canonical.extras(), CHUNK_MAPPED_KEYS, OpenAiCodecException.TYPE_API_ERROR);
    }

    // ------------------------------------------------------------- request decode

    private ChatRequest toCanonicalRequest(OpenAiChatRequest dto) {
        // A JSON literal `null` body binds the DTO to null — dereferencing
        // it below would NPE (a 500) for purely client-malformed input. Both OpenAI and
        // Anthropic 400 a null body, so the codec's typed invalid-request error is the
        // correct wire behavior.
        if (dto == null) {
            throw codec(OpenAiCodecException.TYPE_INVALID_REQUEST, "request body must be a JSON object");
        }
        String model = dto.model();
        if (!nonBlank(model)) {
            throw codec(OpenAiCodecException.TYPE_INVALID_REQUEST, "model is required and must be non-blank");
        }
        List<OpenAiMessage> dtoMessages = dto.messages();
        if (dtoMessages == null || dtoMessages.isEmpty()) {
            throw codec(OpenAiCodecException.TYPE_INVALID_REQUEST, "messages must be non-null and non-empty");
        }
        Map<String, Object> extras = new HashMap<>(dto.extras());
        List<String> systemParts = new ArrayList<>();
        List<Message> messages = new ArrayList<>();
        for (OpenAiMessage dtoMessage : dtoMessages) {
            ChatRole role = requireRole(dtoMessage.role(), "message", OpenAiCodecException.TYPE_INVALID_REQUEST);
            if (role == ChatRole.SYSTEM) {
                // A *named* system message keeps its per-message canonical home
                // (SystemMessage.name) and stays in `messages` — it is not flattened, and
                // its name is never folded into request extras (which would re-emit it as
                // a bogus top-level field). An unnamed system message flattens into
                // ChatRequest.system as documented.
                if (dtoMessage.name() != null) {
                    messages.add(toCanonicalMessage(role, dtoMessage));
                } else {
                    String systemText = PromptCache.flattenText(dtoMessage.content());
                    if (nonBlank(systemText)) {
                        systemParts.add(systemText);
                    }
                }
                foldMessageExtras(dtoMessage, extras);
                continue;
            }
            if (role == ChatRole.TOOL && !nonBlank(dtoMessage.toolCallId())) {
                throw codec(
                        OpenAiCodecException.TYPE_INVALID_REQUEST, "tool message requires a non-blank tool_call_id");
            }
            messages.add(toCanonicalMessage(role, dtoMessage));
            foldMessageExtras(dtoMessage, extras);
        }
        // A request whose messages are *only* system messages decodes to an empty
        // `messages` list — every provider encode then rejects it (invalid_request_error).
        // Decode and encode must agree on validity, so the system-only request is rejected
        // here with the same typed error instead of producing a footgun empty-messages
        // canonical.
        if (messages.isEmpty()) {
            throw codec(OpenAiCodecException.TYPE_INVALID_REQUEST, "messages must be non-null and non-empty");
        }
        String system = systemParts.isEmpty() ? null : String.join(SYSTEM_SEPARATOR, systemParts);
        return new ChatRequest(
                model,
                messages,
                system,
                toCanonicalTools(dto.tools()),
                ToolChoiceMapper.normalizeOpenAi(dto.toolChoice()),
                dto.temperature(),
                dto.topP(),
                dto.topK(),
                dto.maxTokens(),
                dto.stop(),
                dto.seed(),
                dto.n(),
                dto.frequencyPenalty(),
                dto.presencePenalty(),
                dto.logitBias(),
                dto.responseFormat(),
                dto.stream() != null && dto.stream(),
                dto.streamOptions(),
                // reasoning_effort (string) is the chat-wire spelling; the Responses-
                // shaped object {"reasoning":{"effort":…}} is accepted as an alias so
                // a client (or OpenRouter) sending that form still gets effort on the
                // outbound reasoning_effort field instead of a silent extras leak.
                reasoningFromChatWire(dto, extras),
                cacheControlFromOpenAi(dtoMessages),
                unmodifiable(extras),
                null);
    }

    /**
     * Chat-face reasoning: {@code reasoning_effort} wins when both spellings are
     * present. An effort-shaped object form is lifted out of extras so encode emits the
     * string field and does not re-emit a {@code reasoning} object; a NON-effort-shaped
     * {@code reasoning} value (e.g. {@code {"enabled":true}} or the string
     * {@code "high"}) was never translated, so it stays in extras and rides the
     * documented pass-through instead of being silently deleted.
     */
    private static Map<String, Object> reasoningFromChatWire(OpenAiChatRequest dto, Map<String, Object> extras) {
        Map<String, Object> fromObject = null;
        if (dto.extras().get("reasoning") instanceof Map<?, ?> map) {
            Object effort = map.get("effort");
            if (effort instanceof String text && !text.isBlank()) {
                fromObject = Map.of("effort", text);
                extras.remove("reasoning"); // consumed → lifted out
            }
        }
        if (dto.reasoningEffort() != null) {
            return Map.of("effort", dto.reasoningEffort());
        }
        return fromObject;
    }

    /** Wire-only message fields (unknown fields) fold into request extras. The wire
     * {@code name} is not folded — it has a per-message canonical home. */
    private static void foldMessageExtras(OpenAiMessage dtoMessage, Map<String, Object> extras) {
        extras.putAll(dtoMessage.extras());
    }

    private static Message toCanonicalMessage(ChatRole role, OpenAiMessage dto) {
        return switch (role) {
            case USER -> toCanonicalUserMessage(dto);
            case ASSISTANT ->
                new AssistantMessage(
                        requireStringContent(dto.content(), "assistant"),
                        toCanonicalToolCalls(dto.toolCalls()),
                        dto.name());
            case TOOL -> new ToolMessage(dto.toolCallId(), requireStringContent(dto.content(), "tool"), dto.name());
            // Reached only for *named* system messages (unnamed ones are flattened
            // into ChatRequest.system before mapping) — the name has a canonical home.
            case SYSTEM -> new SystemMessage(PromptCache.flattenText(dto.content()), dto.name());
            // The OpenAI developer-prompt role decodes to its own subtype (system-ish,
            // but with the "developer" wire spelling preserved for the OpenAI re-encode —
            // never flattened into ChatRequest.system, which would lose the role).
            case DEVELOPER -> new DeveloperMessage(PromptCache.flattenText(dto.content()), dto.name());
        };
    }

    private static UserMessage toCanonicalUserMessage(OpenAiMessage dto) {
        Object content = dto.content();
        if (content == null) {
            return new UserMessage(null, dto.name(), null);
        }
        if (content instanceof String text) {
            return new UserMessage(text, dto.name(), null);
        }
        if (content instanceof List<?> list) {
            List<ContentPart> parts = new ArrayList<>();
            for (Object item : list) {
                parts.add(openAiPartToCanonical(item));
            }
            if (parts.isEmpty()) {
                throw codec(
                        OpenAiCodecException.TYPE_INVALID_REQUEST,
                        "user message content array must contain at least one part");
            }
            return UserMessage.multimodal(dto.name(), parts);
        }
        throw codec(
                OpenAiCodecException.TYPE_INVALID_REQUEST,
                "user message content must be a string or an array of content parts");
    }

    @SuppressWarnings("unchecked")
    private static ContentPart openAiPartToCanonical(Object item) {
        Map<String, Object> map;
        if (item instanceof Map<?, ?> m) {
            map = (Map<String, Object>) m;
        } else {
            throw codec(
                    OpenAiCodecException.TYPE_INVALID_REQUEST,
                    "user content part must be an object (got "
                            + (item == null ? "null" : item.getClass().getSimpleName()) + ")");
        }
        Object typeObj = map.get("type");
        String type = typeObj == null ? null : String.valueOf(typeObj);
        Object partCache = PromptCache.cacheControlFromOpenAiPart(map);
        if ("text".equals(type)) {
            Object text = map.get("text");
            return new TextContent(text == null ? null : String.valueOf(text), partCache);
        }
        if ("image_url".equals(type)) {
            Object imageUrl = map.get("image_url");
            if (!(imageUrl instanceof Map<?, ?> imgMap)) {
                throw codec(
                        OpenAiCodecException.TYPE_INVALID_REQUEST, "image_url part requires an object image_url field");
            }
            Object url = imgMap.get("url");
            Object detail = imgMap.get("detail");
            if (url == null || String.valueOf(url).isBlank()) {
                throw codec(
                        OpenAiCodecException.TYPE_INVALID_REQUEST, "image_url.url is required and must be non-blank");
            }
            return new ImageUrlContent(String.valueOf(url), detail == null ? null : String.valueOf(detail), partCache);
        }
        throw codec(
                OpenAiCodecException.TYPE_INVALID_REQUEST,
                "unsupported user content part type: " + type + " (supported: text, image_url)");
    }

    private static String requireStringContent(Object content, String role) {
        if (content == null) {
            return null;
        }
        if (content instanceof String text) {
            return text;
        }
        throw codec(
                OpenAiCodecException.TYPE_INVALID_REQUEST,
                role + " message content must be a string (multimodal array is only legal on user messages)");
    }

    private static List<ToolCall> toCanonicalToolCalls(List<OpenAiToolCall> calls) {
        if (calls == null) {
            return null;
        }
        List<ToolCall> result = new ArrayList<>();
        for (OpenAiToolCall call : calls) {
            OpenAiFunctionCall function = call.function();
            // The streaming wire index round-trips through ToolCall.index so a
            // passthrough that decodes and re-encodes chunks does not renumber
            // multi-tool-call fragments; null outside streaming.
            result.add(new ToolCall(
                    call.id(),
                    call.type(),
                    new FunctionCall(
                            function == null ? null : function.name(), function == null ? null : function.arguments()),
                    call.index()));
        }
        return result;
    }

    private List<ToolDefinition> toCanonicalTools(List<OpenAiTool> tools) {
        if (tools == null) {
            return null;
        }
        List<ToolDefinition> result = new ArrayList<>();
        for (OpenAiTool tool : tools) {
            OpenAiFunction function = tool.function();
            // A wire tool with no `function` member keeps null name/description/schema so
            // the encode path omits the invalid OpenAI shape "function":{} (mirrors the
            // streaming fix) — decode → encode stays idempotent for function-less tools.
            result.add(new ToolDefinition(
                    tool.type(),
                    function == null ? null : function.name(),
                    function == null ? null : function.description(),
                    function == null || function.parameters() == null
                            ? null
                            : writeRawJson(function.parameters(), OpenAiCodecException.TYPE_INVALID_REQUEST),
                    function == null ? null : function.strict()));
        }
        return result;
    }

    // ------------------------------------------------------------ request encode

    /** Inbound {@code prompt_cache_breakpoint} on a content part becomes canonical cacheControl. */
    private static Object cacheControlFromOpenAi(List<OpenAiMessage> messages) {
        if (messages == null) {
            return null;
        }
        for (OpenAiMessage message : messages) {
            Object marker = PromptCache.cacheControlFromOpenAiContent(message.content());
            if (marker != null) {
                return marker;
            }
        }
        return null;
    }

    private static boolean alreadyHasOpenAiBreakpoint(List<OpenAiMessage> dtoMessages) {
        for (OpenAiMessage message : dtoMessages) {
            if (PromptCache.cacheControlFromOpenAiContent(message.content()) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * When the cache marker lives on ChatRequest.cacheControl but no system prefix
     * exists, put the OpenAI breakpoint (or Qwen {@code cache_control}) on the last
     * developer message (Responses {@code developer} role is the usual stable prefix)
     * or else the last user message. String content is promoted to a one-element
     * text-part array.
     */
    private static void placeFallbackCacheMarker(List<OpenAiMessage> dtoMessages, boolean emitBreakpoints) {
        int target = indexOfLastRole(dtoMessages, "developer");
        if (target < 0) {
            target = indexOfLastRole(dtoMessages, "user");
        }
        if (target < 0) {
            return;
        }
        OpenAiMessage msg = dtoMessages.get(target);
        Object content = msg.content();
        if (content instanceof String text) {
            Object marked = emitBreakpoints
                    ? PromptCache.openAiTextWithBreakpoint(text)
                    : PromptCache.openAiTextWithCacheControl(text, PromptCache.EPHEMERAL);
            dtoMessages.set(
                    target,
                    new OpenAiMessage(msg.role(), marked, msg.name(), msg.toolCallId(), msg.toolCalls(), msg.extras()));
            return;
        }
        if (content instanceof List<?> list && !list.isEmpty()) {
            List<Object> copy = new ArrayList<>(list);
            Object last = copy.get(copy.size() - 1);
            if (last instanceof Map<?, ?> map) {
                Map<String, Object> part = new HashMap<>();
                map.forEach((k, v) -> part.put(String.valueOf(k), v));
                if (emitBreakpoints) {
                    part.put("prompt_cache_breakpoint", PromptCache.EXPLICIT_BREAKPOINT);
                } else {
                    part.put("cache_control", PromptCache.EPHEMERAL);
                }
                copy.set(copy.size() - 1, part);
                dtoMessages.set(
                        target,
                        new OpenAiMessage(
                                msg.role(), copy, msg.name(), msg.toolCallId(), msg.toolCalls(), msg.extras()));
            }
        }
    }

    private static int indexOfLastRole(List<OpenAiMessage> dtoMessages, String role) {
        for (int i = dtoMessages.size() - 1; i >= 0; i--) {
            if (role.equals(dtoMessages.get(i).role())) {
                return i;
            }
        }
        return -1;
    }

    private static OpenAiMessage toOpenAiMessage(Message message, boolean emitBreakpoints, boolean emitCacheControl) {
        return switch (message) {
            case SystemMessage system -> new OpenAiMessage("system", system.content(), system.name(), null, null, null);
            case UserMessage user ->
                new OpenAiMessage(
                        "user",
                        toOpenAiUserContent(user, emitBreakpoints, emitCacheControl),
                        user.name(),
                        null,
                        null,
                        null);
            case AssistantMessage assistant ->
                new OpenAiMessage(
                        "assistant",
                        assistant.content(),
                        assistant.name(),
                        null,
                        toOpenAiToolCalls(assistant.toolCalls(), null),
                        null);
            case ToolMessage tool ->
                new OpenAiMessage("tool", tool.content(), tool.name(), tool.toolCallId(), null, null);
            // The OpenAI wire keeps "developer"; other faces fold it into system.
            case DeveloperMessage developer ->
                new OpenAiMessage("developer", developer.content(), developer.name(), null, null, null);
        };
    }

    private static Object toOpenAiUserContent(UserMessage user, boolean emitBreakpoints, boolean emitCacheControl) {
        if (!user.isMultimodal()) {
            return user.content();
        }
        List<Map<String, Object>> wire = new ArrayList<>();
        for (ContentPart part : user.parts()) {
            wire.add(contentPartToOpenAiMap(part, emitBreakpoints, emitCacheControl));
        }
        return wire;
    }

    private static void putOpenAiCacheMarker(
            Map<String, Object> part, Object cacheControl, boolean emitBreakpoints, boolean emitCacheControl) {
        if (!PromptCache.isEphemeral(cacheControl)) {
            return;
        }
        if (emitBreakpoints) {
            part.put("prompt_cache_breakpoint", PromptCache.EXPLICIT_BREAKPOINT);
        } else if (emitCacheControl) {
            part.put("cache_control", cacheControl);
        }
    }

    private static Map<String, Object> contentPartToOpenAiMap(
            ContentPart part, boolean emitBreakpoints, boolean emitCacheControl) {
        return switch (part) {
            case TextContent text -> {
                Map<String, Object> m = new HashMap<>();
                m.put("type", "text");
                m.put("text", text.text());
                putOpenAiCacheMarker(m, text.cacheControl(), emitBreakpoints, emitCacheControl);
                yield m;
            }
            case ImageUrlContent image -> {
                Map<String, Object> imageUrl = new HashMap<>();
                imageUrl.put("url", image.url());
                if (image.detail() != null) {
                    imageUrl.put("detail", image.detail());
                }
                Map<String, Object> m = new HashMap<>();
                m.put("type", "image_url");
                m.put("image_url", imageUrl);
                putOpenAiCacheMarker(m, image.cacheControl(), emitBreakpoints, emitCacheControl);
                yield m;
            }
            case ImageSourceContent source -> {
                // Anthropic-sourced image → OpenAI image_url form.
                String url;
                if ("base64".equals(source.type()) && source.data() != null) {
                    String mt = source.mediaType() == null ? "image/png" : source.mediaType();
                    url = "data:" + mt + ";base64," + source.data();
                } else {
                    url = source.url();
                }
                Map<String, Object> imageUrl = new HashMap<>();
                imageUrl.put("url", url);
                Map<String, Object> m = new HashMap<>();
                m.put("type", "image_url");
                m.put("image_url", imageUrl);
                putOpenAiCacheMarker(m, source.cacheControl(), emitBreakpoints, emitCacheControl);
                yield m;
            }
        };
    }

    private List<OpenAiTool> toOpenAiTools(List<ToolDefinition> tools) {
        if (tools == null) {
            return null;
        }
        List<OpenAiTool> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            // Definitions are ToolDefinition (never ToolCall), so no null-function
            // guard is needed for a definition-with-no-function: a tool whose fields are
            // all null must omit the function member (@JsonInclude(NON_NULL)) rather than
            // emit the invalid OpenAI shape "function":{} (strict upstreams reject it with
            // "function contains empty value"). Mirrors the streaming-path null-check in
            // toOpenAiToolCalls.
            OpenAiFunction dtoFunction = tool.name() == null
                            && tool.description() == null
                            && tool.inputSchema() == null
                            && tool.strict() == null
                    ? null
                    : new OpenAiFunction(
                            tool.name(),
                            tool.description(),
                            tool.inputSchema() == null
                                    ? null
                                    : readJsonObject(tool.inputSchema(), OpenAiCodecException.TYPE_INVALID_REQUEST),
                            tool.strict());
            result.add(new OpenAiTool(tool.type() == null ? "function" : tool.type(), dtoFunction));
        }
        return result;
    }

    // ----------------------------------------------------------- response mapping

    private ChatResponse toCanonicalResponse(OpenAiChatResponse dto) {
        Map<String, Object> extras = new HashMap<>(dto.extras());
        List<ChatChoice> choices = new ArrayList<>();
        String stopReason = null;
        if (dto.choices() != null) {
            for (OpenAiChoice choice : dto.choices()) {
                OpenAiResponseMessage dtoMessage = choice.message();
                // A choice with a null/missing message (malformed upstream)
                // must raise the codec's typed api_error, never a raw NPE (the codec
                // contract: every decode failure raises OpenAiCodecException). The chunk
                // path tolerates a null delta; a response choice without a message is a
                // broken 200 and is rejected.
                if (dtoMessage == null) {
                    throw codec(OpenAiCodecException.TYPE_API_ERROR, "response choice message is missing");
                }
                // The per-choice finish reason is normalized through the same
                // table as the response-level reason (mirrors the chunk path) so the
                // canonical carries the canonical value — legacy OpenAI "function_call"
                // becomes "tool_calls", and ChatChoice.finishReason always agrees with
                // ChatResponse.stopReason (the two spellings were previously inconsistent).
                String finishReason =
                        choice.finishReason() == null ? null : normalizeFinishReason(choice.finishReason());
                choices.add(new ChatChoice(choice.index(), toCanonicalResponseMessage(dtoMessage), finishReason));
                extras.putAll(choice.extras());
                // Response-message unknowns ride OpenAiResponseMessage.extras and
                // must fold into the response extras too (mirrors foldMessageExtras).
                extras.putAll(dtoMessage.extras());
            }
            if (!dto.choices().isEmpty()) {
                String firstFinish = dto.choices().get(0).finishReason();
                if (firstFinish != null) {
                    stopReason = normalizeFinishReason(firstFinish);
                }
            }
        }
        return new ChatResponse(
                dto.id(),
                dto.object(),
                dto.created(),
                dto.model(),
                choices,
                toCanonicalUsage(dto.usage()),
                stopReason,
                unmodifiable(extras),
                null);
    }

    private static Message toCanonicalResponseMessage(OpenAiResponseMessage dto) {
        ChatRole role = ChatRole.ASSISTANT;
        if (nonBlank(dto.role())) {
            role = requireRole(dto.role(), "response message", OpenAiCodecException.TYPE_API_ERROR);
        }
        return switch (role) {
            case ASSISTANT -> new AssistantMessage(dto.content(), toCanonicalToolCalls(dto.toolCalls()));
            case USER -> new UserMessage(dto.content());
            case SYSTEM -> new SystemMessage(dto.content());
            case TOOL -> new ToolMessage(null, dto.content());
            // A developer-role response message (rare, but the role is a legal
            // OpenAI chat role) keeps its subtype; the generic response encode emits the
            // wire spelling via role.wire.
            case DEVELOPER -> new DeveloperMessage(dto.content(), null);
        };
    }

    private static OpenAiResponseMessage toOpenAiResponseMessage(Message message) {
        if (message instanceof AssistantMessage assistant) {
            return new OpenAiResponseMessage(
                    "assistant", assistant.content(), toOpenAiToolCalls(assistant.toolCalls(), null), null);
        }
        return new OpenAiResponseMessage(message.role().wire(), contentOf(message), null, null);
    }

    private static String contentOf(Message message) {
        return switch (message) {
            case SystemMessage system -> system.content();
            case UserMessage user -> user.plainText();
            case AssistantMessage assistant -> assistant.content();
            case ToolMessage tool -> tool.content();
            case DeveloperMessage developer -> developer.content();
        };
    }

    /** Canonical per-choice finish reason, mapped through the OpenAI table (Anthropic-derived
     * {@code "refusal"}/{@code "pause_turn"} must not leak as invalid OpenAI {@code finish_reason}
     * values — they map to {@code "stop"}); the normalized response-level reason is the fallback. */
    private static String finishReasonFor(String choiceFinishReason, String stopReason) {
        if (choiceFinishReason != null) {
            return toOpenAiFinishReason(choiceFinishReason);
        }
        return stopReason == null ? null : toOpenAiFinishReason(stopReason);
    }

    private static String normalizeFinishReason(String raw) {
        return StopReasonTable.openAiToCanonical(raw);
    }

    private static String toOpenAiFinishReason(String canonical) {
        return StopReasonTable.canonicalToOpenAi(canonical);
    }

    // ------------------------------------------------------------- chunk mapping

    private static StreamChunk toCanonicalChunk(OpenAiChunk dto) {
        Map<String, Object> extras = new HashMap<>(dto.extras());
        List<ChunkChoice> choices = new ArrayList<>();
        if (dto.choices() != null) {
            for (OpenAiChunkChoice choice : dto.choices()) {
                OpenAiDelta dtoDelta = choice.delta();
                ChatRole role = null;
                if (dtoDelta != null && nonBlank(dtoDelta.role())) {
                    role = requireRole(dtoDelta.role(), "delta", OpenAiCodecException.TYPE_API_ERROR);
                    // A chat.completion.chunk delta carries only "assistant"
                    // (and transiently "user") — "system"/"tool" are known ChatRole values
                    // that are invalid in a delta; rejecting here prevents the re-encode
                    // path from emitting a provably invalid delta role verbatim.
                    if (role != ChatRole.ASSISTANT && role != ChatRole.USER) {
                        throw codec(OpenAiCodecException.TYPE_API_ERROR, "invalid delta role: " + dtoDelta.role());
                    }
                }
                List<ToolCall> toolCalls = dtoDelta == null ? null : toCanonicalToolCalls(dtoDelta.toolCalls());
                // The per-choice finish reason is normalized through the same
                // table as the response-level reason so the canonical carries the canonical
                // value — legacy OpenAI "function_call" becomes "tool_calls" and never leaks
                // verbatim to the Anthropic stream encoder as an invalid stop_reason (the
                // Anthropic encode maps canonical values through canonicalToAnthropic).
                String finishReason =
                        choice.finishReason() == null ? null : normalizeFinishReason(choice.finishReason());
                // Delta-level unknowns (first-class DeepSeek
                // reasoning_content) keep their position — they ride Delta.reasoning and
                // re-emerge inside the delta, never hoisted to the chunk top level.
                Map<String, Object> deltaReasoning =
                        dtoDelta == null || dtoDelta.extras().isEmpty()
                                ? null
                                : unmodifiable(new HashMap<>(dtoDelta.extras()));
                choices.add(new ChunkChoice(
                        choice.index(),
                        new Delta(role, dtoDelta == null ? null : dtoDelta.content(), toolCalls, deltaReasoning),
                        finishReason));
            }
        }
        return new StreamChunk(
                dto.id(),
                dto.object(),
                dto.created(),
                dto.model(),
                choices,
                toCanonicalUsage(dto.usage()),
                unmodifiable(extras));
    }

    /**
     * Map canonical tool calls to wire DTOs. Streaming deltas (non-null {@code startIndex})
     * carry an {@code index} — the canonical {@code ToolCall.index} when present
     * (round-trip preservation), else a position-based fallback offset by
     * {@code startIndex} (0 for delta lists). Non-streaming request/response
     * {@code tool_calls} never carry an index on the wire ({@code startIndex == null}).
     */
    private static List<OpenAiToolCall> toOpenAiToolCalls(List<ToolCall> calls, Integer startIndex) {
        if (calls == null) {
            return null;
        }
        List<OpenAiToolCall> result = new ArrayList<>();
        int position = startIndex == null ? -1 : startIndex;
        for (ToolCall call : calls) {
            Integer wireIndex = startIndex == null ? null : (call.index() != null ? call.index() : position);
            FunctionCall function = call.function();
            String name = function == null ? null : function.name();
            String arguments = function == null || function.arguments() == null ? "" : function.arguments();
            // A tool call whose function has neither name nor arguments must
            // omit the function member (@JsonInclude(NON_NULL)) rather than emit the invalid
            // OpenAI shape "function":{} (or an empty "arguments":"" for a nameless call).
            OpenAiFunctionCall dtoFunction = function == null || (name == null && function.arguments() == null)
                    ? null
                    : new OpenAiFunctionCall(name, arguments);
            result.add(new OpenAiToolCall(
                    wireIndex, call.id(), call.type() == null ? "function" : call.type(), dtoFunction));
            position++;
        }
        return result;
    }

    // ------------------------------------------------------------------- usage

    private static OpenAiUsage toOpenAiUsage(Usage usage) {
        // The OpenAI wire counts cached tokens INSIDE prompt_tokens. Re-emitting the
        // canonical's regular (cache-split) prompt_tokens verbatim breaks
        // prompt + completion == total. The full input is restored when the canonical
        // is a confirmed regular split — i.e. prompt + completion + cached == total,
        // the invariant BOTH decodes produce (the OpenAI decode stores the wire total,
        // which counts the full input; the Anthropic decode derives the total
        // additively — regular + cache-read + cache-creation + output — because
        // Anthropic's input_tokens excludes the cache counts). A hand-built canonical
        // whose promptTokens already includes the cached count keeps
        // prompt + completion == total, fails the equality, and is left verbatim
        // (never a double count). The arithmetic confirms which shape a canonical is.
        //
        // Cache splits re-emit as prompt_tokens_details (OpenAI / OpenRouter) and the
        // DeepSeek/Kimi prompt_cache_hit_tokens alias so clients can see a hit.
        Long cached = usage.cacheReadInputTokens();
        Long created = usage.cacheCreationInputTokens();
        long prompt = usage.promptTokens();
        long addBack = 0;
        if (cached != null && cached > 0) {
            addBack += cached;
        }
        if (created != null && created > 0) {
            addBack += created;
        }
        if (addBack > 0 && usage.promptTokens() + usage.completionTokens() + addBack == usage.totalTokens()) {
            prompt = usage.promptTokens() + addBack;
        }
        OpenAiCompletionTokensDetails reasoningDetails = null;
        if (usage.reasoningTokens() != null && usage.reasoningTokens() > 0) {
            reasoningDetails = new OpenAiCompletionTokensDetails(usage.reasoningTokens());
        }
        Long cachedOut = cached != null && cached > 0 ? cached : null;
        Long createdOut = created != null && created > 0 ? created : null;
        OpenAiPromptTokensDetails promptDetails =
                cachedOut == null && createdOut == null ? null : new OpenAiPromptTokensDetails(cachedOut, createdOut);
        return new OpenAiUsage(
                prompt,
                usage.completionTokens(),
                prompt + usage.completionTokens(),
                promptDetails,
                cachedOut,
                reasoningDetails);
    }

    private static Usage toCanonicalUsage(OpenAiUsage dto) {
        if (dto == null) {
            return null;
        }
        long prompt = dto.promptTokens() == null ? 0L : dto.promptTokens();
        long completion = dto.completionTokens() == null ? 0L : dto.completionTokens();
        // The OpenAI wire counts cache-read tokens INSIDE prompt_tokens (OpenAI
        // prompt_tokens_details.cached_tokens / DeepSeek/Kimi prompt_cache_hit_tokens);
        // the canonical Usage counts regular input only, cache separate — the reference
        // Pricing.normalize_tokens convention the Anthropic leg already uses. Cost is
        // therefore regular × input + cached × cache-read (never a double bill).
        // The cache claim is clamped to the prompt count so malformed upstream data
        // (cached > prompt) can never zero the input accounting or over-bill cache
        // beyond the real input.
        long cached = Math.min(cachedInputTokens(dto), prompt);
        long write = Math.min(cacheWriteInputTokens(dto), prompt - cached);
        long regular = prompt - cached - write;
        long total = dto.totalTokens() == null ? regular + completion + cached + write : dto.totalTokens();
        Long reasoning = null;
        if (dto.completionTokensDetails() != null
                && dto.completionTokensDetails().reasoningTokens() != null) {
            reasoning = Math.max(dto.completionTokensDetails().reasoningTokens(), 0L);
        }
        if (cached == 0 && write == 0) {
            return new Usage(prompt, completion, total, null, null, reasoning);
        }
        return new Usage(regular, completion, total, write > 0 ? write : null, cached > 0 ? cached : null, reasoning);
    }

    /**
     * Cache-read input tokens on the OpenAI wire: {@code cached_tokens} (the primary
     * {@code prompt_tokens_details} spelling) wins; the DeepSeek/Kimi top-level
     * {@code prompt_cache_hit_tokens} alias is the fallback. When an endpoint emits both
     * with different values the primary spelling is the deterministic choice
     * (taking {@code max} would silently credit the smaller to regular input).
     */
    private static long cachedInputTokens(OpenAiUsage dto) {
        OpenAiPromptTokensDetails details = dto.promptTokensDetails();
        if (details != null && details.cachedTokens() != null) {
            return Math.max(details.cachedTokens(), 0);
        }
        Long alias = dto.promptCacheHitTokens();
        return alias == null ? 0 : Math.max(alias, 0);
    }

    private static long cacheWriteInputTokens(OpenAiUsage dto) {
        OpenAiPromptTokensDetails details = dto.promptTokensDetails();
        if (details == null || details.cacheWriteTokens() == null) {
            return 0;
        }
        return Math.max(details.cacheWriteTokens(), 0);
    }

    // ------------------------------------------------------------ shared helpers

    /**
     * Serialize the DTO, then merge {@code extras} at the top level. Mapped (gateway)
     * fields win on collision — mirrors the merge_extras precedence
     * ({@code Map.merge(extras, base, fn _, _, gateway -> gateway end)}); extras keys the
     * DTO already emitted are skipped, and extras keys that collide with a declared DTO
     * component are dropped even when the component is absent (an
     * absent-optional mapped field like {@code stream:false} — omitted by
     * {@code @JsonInclude(NON_NULL)} — must never let an extras entry flip it, e.g.
     * {@code "stream":true} leaking onto the wire).
     */
    private String writeWithExtras(Object dto, Map<String, Object> extras, Set<String> mappedKeys, String failureType)
            throws OpenAiCodecException {
        try {
            ObjectNode node = (ObjectNode) mapper.valueToTree(dto);
            if (extras != null && !extras.isEmpty()) {
                for (Map.Entry<String, Object> entry : extras.entrySet()) {
                    if (!node.has(entry.getKey()) && !mappedKeys.contains(entry.getKey())) {
                        node.set(entry.getKey(), mapper.valueToTree(entry.getValue()));
                    }
                }
            }
            return mapper.writeValueAsString(node);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(failureType, "failed to encode OpenAI payload: " + e.getMessage(), e);
        }
    }

    private JsonNode readTree(String json, String failureType) throws OpenAiCodecException {
        try {
            return mapper.readTree(json);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(failureType, "invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Array content is legal on user messages (vision) and on system/developer
     * messages (GPT-5.6 prompt-cache breakpoints). Assistant / tool / delta array
     * content remains rejected (responses stay string-shaped).
     */
    private static void rejectArrayFormContent(JsonNode root, String what) throws OpenAiCodecException {
        JsonNode messages = root.path("messages");
        if (messages.isArray()) {
            int i = 0;
            for (JsonNode message : messages) {
                JsonNode content = message.get("content");
                String role = message.path("role").asString("");
                if (content != null
                        && content.isArray()
                        && !"user".equals(role)
                        && !"system".equals(role)
                        && !"developer".equals(role)) {
                    throw codec(
                            OpenAiCodecException.TYPE_INVALID_REQUEST,
                            what + " messages[" + i + "].content is array-form — only user, system, and developer"
                                    + " messages may carry content parts");
                }
                i++;
            }
        }
        JsonNode choices = root.path("choices");
        if (choices.isArray()) {
            int i = 0;
            for (JsonNode choice : choices) {
                JsonNode messageContent = choice.path("message").get("content");
                if (messageContent != null && messageContent.isArray()) {
                    throw codec(
                            OpenAiCodecException.TYPE_API_ERROR,
                            what + " choices[" + i + "].message.content is array-form (multimodal) — out of scope");
                }
                JsonNode deltaContent = choice.path("delta").get("content");
                if (deltaContent != null && deltaContent.isArray()) {
                    throw codec(
                            OpenAiCodecException.TYPE_API_ERROR,
                            what + " choices[" + i + "].delta.content is array-form (multimodal) — out of scope");
                }
                i++;
            }
        }
    }

    private static void validateRequest(ChatRequest request) {
        if (!nonBlank(request.model())) {
            throw codec(OpenAiCodecException.TYPE_INVALID_REQUEST, "model is required and must be non-blank");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw codec(OpenAiCodecException.TYPE_INVALID_REQUEST, "messages must be non-null and non-empty");
        }
        for (Message message : request.messages()) {
            if (message instanceof ToolMessage tool && !nonBlank(tool.toolCallId())) {
                throw codec(
                        OpenAiCodecException.TYPE_INVALID_REQUEST, "tool message requires a non-blank tool_call_id");
            }
            if (message instanceof UserMessage user) {
                boolean hasText = nonBlank(user.content());
                boolean hasParts = user.isMultimodal();
                if (!hasText && !hasParts) {
                    throw codec(
                            OpenAiCodecException.TYPE_INVALID_REQUEST,
                            "user message requires non-blank content or multimodal parts");
                }
            }
        }
    }

    private static void validateResponse(ChatResponse response) {
        if (!nonBlank(response.id())) {
            throw codec(OpenAiCodecException.TYPE_API_ERROR, "response id is required and must be non-blank");
        }
        if (!nonBlank(response.model())) {
            throw codec(OpenAiCodecException.TYPE_API_ERROR, "response model is required and must be non-blank");
        }
        if (response.choices() == null) {
            throw codec(OpenAiCodecException.TYPE_API_ERROR, "response choices must be non-null");
        }
    }

    /**
     * The chunk encode iterates {@code canonical.choices} with no null
     * guard (unlike {@link #validateResponse}) — a hand-built canonical with null
     * choices would escape the codec's typed-error contract as a raw NPE. An empty
     * list stays valid (the usage-only terminal chunk), so only null is rejected.
     */
    private static void validateChunk(StreamChunk chunk) {
        if (chunk.choices() == null) {
            throw codec(OpenAiCodecException.TYPE_API_ERROR, "chunk choices must be non-null");
        }
    }

    private static ChatRole requireRole(String role, String what, String failureType) {
        if (!nonBlank(role)) {
            throw codec(failureType, what + " is missing a role");
        }
        try {
            return ChatRole.fromWire(role);
        } catch (IllegalArgumentException e) {
            throw codec(failureType, "unknown " + what + " role: " + role, e);
        }
    }

    private Map<String, Object> readJsonObject(String raw, String failureType) {
        try {
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(
                    failureType,
                    "request tool arguments must be a JSON object (parameters schema): " + e.getMessage(),
                    e);
        }
    }

    private String writeRawJson(Object value, String failureType) {
        try {
            return mapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(failureType, "failed to serialize tool parameters: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> unmodifiable(Map<String, Object> map) {
        return Collections.unmodifiableMap(map);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static OpenAiCodecException codec(String type, String message) {
        return new OpenAiCodecException(type, message);
    }

    private static OpenAiCodecException codec(String type, String message, Throwable cause) {
        return new OpenAiCodecException(type, message, cause);
    }
}
