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
import io.amscotti.janus.core.model.HostedToolCall;
import io.amscotti.janus.core.model.HostedToolDefinition;
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
import java.util.LinkedHashMap;
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
 * Canonical ↔ Anthropic Messages wire-format translator. Owns
 * every Anthropic wire shape: the {@code /v1/messages} request and response plus the SSE
 * event family ({@code message_start}, {@code content_block_start},
 * {@code content_block_delta}, {@code message_delta},...).
 *
 * <p><b>Mapper contract.</b> The constructor-injected {@link ObjectMapper} must be
 * configured for the wire format the DTOs assume — the same contract as the OpenAI
 * codec: snake_case naming ({@code PropertyNamingStrategies.SNAKE_CASE}),
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled (tolerant decode),
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES} disabled and {@code ACCEPT_SINGLE_VALUE_AS_ARRAY}
 * enabled. {@link #create} provides exactly that mapper; the gateway must pass
 * an equivalent one. DTOs additionally carry {@code @JsonInclude(NON_NULL)} so encode
 * output is skip-null regardless of the injected mapper's inclusion config.
 *
 * <p><b>State hazard discipline.</b> The codec never serializes canonical
 * types — mapping is explicit record-to-record, and every DTO list is declared-typed
 * ({@code List<AnthropicContentBlock>}, {@code List<AnthropicSseEvent>},...). The wire
 * discriminators ({@code type} per content block / SSE event / delta, {@code role} per
 * message) therefore always survive; {@code AnthropicWireShapeGuardTest} pins that
 * contract.
 *
 * <p><b>Pass-through contract.</b> Unknown top-level fields (and unknown message fields)
 * ride the canonical {@code extras} maps on decode and are merged back into the outbound
 * payload at the top level on encode. On collision the mapped (gateway) field wins — the
 * merge_extras precedence ({@code Map.merge(extras, base, gateway)}).
 * Consequence (documented, pinned by test): nested unknowns (message-level) re-emerge as
 * <em>top-level</em> fields — values survive a round-trip, position does not. {@code meta}
 * is never read and never emitted. Content blocks, usage, tools and SSE event payloads
 * carry no extras capture — unknown block fields are dropped by design. <b>Anthropic-request-face caveat
 * (documented):</b> the generic extras merge re-emits unknown <em>top-level</em>
 * fields verbatim, but real Anthropic rejects unknown request fields (the reason
 * {@code stream_options} is dropped, see (8)) — so a message-derived unknown that
 * re-emerges top-level (e.g. a user-message {@code name}) can turn a valid inbound
 * request into a 400 outbound. This is the pass-through trade-off, deliberately kept
 * (no PLAN-adjacent decision to drop message-derived extras); system-message names are
 * the one exception and are dropped (pinned by
 * {@code namedSystemMessageContentFlattensAndNameNeverLeaksTopLevel}).
 *
 * <p><b>System handling (pinned by tests).</b> The canonical home for the system prompt
 * is {@code ChatRequest.system}. On encode, {@code system} and any {@code SystemMessage}s
 * in {@code messages} merge (joined {@code "\n\n"}) into the top-level Anthropic
 * {@code system} and are dropped from the {@code messages} array. On decode, the
 * top-level {@code system} (string or text-block array, joined) becomes
 * {@code ChatRequest.system}. Consequence: a canonical request carrying <em>both</em>
 * {@code system} and a {@code SystemMessage} in {@code messages} is not round-trip
 * idempotent (pinned by test). A {@code SystemMessage} {@code name} (OpenAI-legal)
 * has no Anthropic wire home and is dropped here — it never leaks as a top-level
 * {@code name} (pinned by test). Anthropic system accepts text blocks only — any other
 * block type is rejected (multimodal/thinking system content is out of scope).
 *
 * <p><b>Tool shapes.</b> The full tool matrix translates in both directions:
 * wire {@code tools}/{@code tool_choice} ↔ canonical {@code tools}/{@code toolChoice}
 * (via {@link ToolChoiceMapper}, translation/tools.ex precedent), canonical
 * {@code ToolMessage} ↔ user-role {@code tool_result} blocks, assistant
 * {@code ToolCall}s ↔ {@code tool_use} content blocks (canonical raw-JSON
 * {@code arguments} ↔ decoded {@code input} object), and {@code input_json_delta}
 * streaming fragments (verbatim partial JSON, keyed by the Anthropic content-block index
 * — see {@link AnthropicStreamEncoder}). {@code tool_use.id} ↔ {@code ToolCall.id} ↔
 * {@code tool_result.tool_use_id}; canonical {@code ToolCall.type} normalizes to
 * {@code "function"} on decode from Anthropic (its wire has no type field — documented,
 * pinned). Tool-result {@code is_error} has no canonical slot and is dropped on decode
 * (OpenAI has no equivalent either); encode never sets it. Anthropic's
 * {@code disable_parallel_tool_use} (no canonical/OpenAI home) rides the request
 * {@code extras} key {@value ToolChoiceMapper#EXTRAS_DISABLE_PARALLEL} and is re-emitted
 *  inside {@code tool_choice} on Anthropic encode. {@code image} blocks are a
 *  request-face feature: user-message image blocks (base64 or url sources, with
 *  {@code cache_control}) decode into canonical multimodal {@code UserMessage} parts
 *  and encode re-emits them from canonical image parts (data URLs convert to base64
 *  sources, https URLs to url sources — pinned by {@code AnthropicRequestCodecTest},
 *  docs/providers.md "Vision"); an image block on a non-user request message is a typed
 *  invalid-request error, and response/stream decode drops image blocks (assistant
 *  output has no canonical multimodal home — the streaming and non-streaming decode
 *  paths agree).
 *
 * <p><b>Known non-idempotences (documented + pinned).</b> (1) {@code system} +
 * {@code SystemMessage} merge (above). (2) Anthropic has no {@code created} timestamp:
 * decode emits {@code 0} (deterministic). (3) The canonical
 * {@code object} maps from the wire {@code type} ({@code "message"}). (4) Multi-choice
 * canonical responses collapse into one joined choice on Anthropic decode. (5) OpenAI-only
 * canonical fields ({@code n}, {@code seed}, {@code frequencyPenalty},
 * {@code presencePenalty}, {@code logitBias}, {@code responseFormat}) have no Anthropic
 * wire home and are dropped on encode unless the caller put them in {@code extras}.
 * (6) {@code stop_sequence} is dropped on decode; thinking blocks are dropped (extended
 * thinking content pipeline deferred); unknown content-block/delta types
 * ({@code thinking_delta}, {@code signature_delta}, {@code server_tool_use}, …) are
 * tolerated and dropped instead of aborting the decode — on <em>both</em> the streaming
 * and the non-streaming response path (the paths must agree on Anthropic's
 * versioning contract; a payload that streams must also decode). The request face is
 * the exception: an unknown block type inside a request message is a typed
 * invalid-request error — client-supplied content is never silently dropped. (7) Streaming index spaces differ: Anthropic
 * indexes <em>content blocks</em> (text and tools share one counter); OpenAI indexes
 * <em>tool calls only</em>. The stateless decode passes the block index through verbatim
 * (a per-event mapping with no cross-event state); the stateful per-stream decoder
 * ({@link #newStreamDecoder}) renumbers tool-using blocks into a tool-only 0-based
 * space so an ao passthrough emits contiguous OpenAI {@code tool_calls} indices. Canonical→Anthropic encode assigns block indices in arrival order (0, 1, 2,
 * …) as blocks open (documented, pinned). (8) The canonical {@code streamOptions} has
 * no Anthropic wire home (real Anthropic rejects {@code stream_options} — D1): the
 * codec drops it on Anthropic-outbound encode; decode still tolerates the field so the
 * canonical home survives (pinned by
 * {@code CanonicalRoundTripPropertyTest.streamOptionsAreDroppedOnTheAnthropicWireAsDocumented}).
 * (9) <b>Cache-marker placement is single-slot:</b> an ephemeral {@code cache_control}
 * on an assistant text/{@code tool_use} block has no per-block canonical home
 * ({@code AssistantMessage} carries no parts), so decode captures it into the
 * request-level {@code cacheControl} and encode re-emits it on the <em>system</em> block
 * (when a system prompt exists) or the <em>last user</em> message — the marker survives
 * the aa round trip (never a silent cache miss), but its block position may move
 * (documented, pinned by {@code PromptCacheCodecTest}).
 *
 * <p><b>Stop reasons.</b> Both directions delegate to {@link StopReasonTable} (single
 * home, translation/stop_reason.ex precedent): decode
 * ({@code end_turn|stop_sequence|stop} → {@code STOP_REASON_STOP}, {@code max_tokens} →
 * {@code LENGTH}, {@code tool_use} → {@code TOOL_CALLS}, {@code content_filter} →
 * {@code CONTENT_FILTER}) and encode (reverse; {@code error} → {@code end_turn});
 * <em>unknown values pass through verbatim</em> (documented divergence from the reference implementation's
 * {@code :stop} fallback — canonical → wire → canonical idempotence requires it).
 */
public final class AnthropicMessageCodec {

    private static final String SYSTEM_SEPARATOR = "\n\n";
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final String DEFAULT_RESPONSE_OBJECT = "message";

    /**
     * Wire keys the request DTO owns (mapped fields, snake_case as on the wire).
     * {@code writeWithExtras} drops extras entries colliding with these even when the
     * DTO omits the member, so the mapped-field-wins contract holds for
     * absent-optional fields too (the same hardening the OpenAI codec applies).
     */
    private static final java.util.Set<String> REQUEST_MAPPED_KEYS = java.util.Set.of(
            "model",
            "messages",
            "system",
            "max_tokens",
            "temperature",
            "top_p",
            "top_k",
            "stop_sequences",
            "stream",
            "stream_options",
            "tools",
            "tool_choice",
            "thinking",
            "cache_control",
            "output_config");

    /** Wire keys the response DTO owns (see {@link #REQUEST_MAPPED_KEYS}). */
    private static final java.util.Set<String> RESPONSE_MAPPED_KEYS =
            java.util.Set.of("id", "type", "role", "model", "content", "stop_reason", "stop_sequence", "usage");

    /**
     * OpenAI-only chat-wire fields with NO Anthropic home.
     * These previously merged top-level into Anthropic requests via the extras
     * pass-through — real Anthropic rejects unknown request fields with a 400, so a
     * chat-face request carrying any SDK-default field (service_tier,
     * parallel_tool_calls, …) broke on the ao leg. Dropped from the wire extras copy on
     * the Anthropic egress; genuinely unknown fields keep their documented tolerance.
     * The OpenAI {@code metadata} object is a different shape from Anthropic's
     * {@code metadata.user_id} and is dropped with them (the chat-wire {@code user} is
     * the one field with a remap — see {@link #remapUserIntoMetadata}).
     */
    private static final java.util.Set<String> OPENAI_ONLY_REQUEST_EXTRAS = java.util.Set.of(
            "service_tier",
            "parallel_tool_calls",
            "prompt_cache_key",
            "prompt_cache_options",
            "prompt_cache_retention",
            "safety_identifier",
            "prediction",
            "store",
            "logprobs",
            "top_logprobs",
            // The chat-wire object spelling of reasoning has no Anthropic home: the
            // effort value already travels in the canonical reasoning slot (→ thinking
            // + output_config), and a non-effort-shaped object must not leak onto the
            // Anthropic wire as an unknown field (real Anthropic 400s those).
            "reasoning");

    /**
     * Shape the extras {@code metadata} entry for the Anthropic wire, merging the
     * chat-wire {@code user} remap. Anthropic's metadata accepts <b>only</b>
     * {@code user_id}; the OpenAI metadata shape (arbitrary key/value) has no home and
     * is dropped. Deterministic precedence: an explicit {@code user_id} (the
     * Anthropic-originated spelling, round-tripping through this codec on its own wire)
     * wins over the remapped chat-wire {@code user}. Mutates {@code extras} in place:
     * {@code user} is always removed; {@code metadata} is replaced with the filtered
     * map, or removed entirely when nothing Anthropic-legal remains.
     */
    private static void remapUserIntoMetadata(Map<String, Object> extras) {
        Object user = extras.remove("user");
        Object existing = extras.get("metadata");
        String existingUserId = null;
        if (existing instanceof Map<?, ?> map && map.get("user_id") != null) {
            existingUserId = String.valueOf(map.get("user_id"));
        }
        if (user == null && existingUserId == null) {
            extras.remove("metadata"); // OpenAI-shaped metadata (or junk) has no home
            return;
        }
        extras.put("metadata", Map.of("user_id", existingUserId != null ? existingUserId : user));
    }

    private static final String DEFAULT_RESPONSE_ID = "unknown";
    private static final String DEFAULT_RESPONSE_MODEL = "unknown";
    private static final String TOOL_SCHEMA_DEFAULT_TYPE = "object";
    private static final String TOOL_SCHEMA_DEFAULT_PROPERTIES = "properties";

    private final ObjectMapper mapper;

    public AnthropicMessageCodec(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Default mapper per the codec's contract (snake_case, tolerant, single-value arrays). */
    public static AnthropicMessageCodec create() {
        return new AnthropicMessageCodec(JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .build());
    }

    // ------------------------------------------------------------------ request

    public ChatRequest decodeRequest(String json) throws AnthropicCodecException {
        if (json == null || json.isBlank()) {
            // An empty/missing body (Micronaut passes null for a body-less
            // POST) must be a client 400, never a mapper IllegalArgumentException → 500.
            throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "request body must be a JSON object");
        }
        JsonNode root = readTree(json, AnthropicCodecException.TYPE_INVALID_REQUEST);
        AnthropicMessageRequest dto;
        try {
            dto = mapper.treeToValue(root, AnthropicMessageRequest.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(
                    AnthropicCodecException.TYPE_INVALID_REQUEST, "invalid Anthropic request: " + e.getMessage(), e);
        }
        return toCanonicalRequest(dto);
    }

    public String encodeRequest(ChatRequest canonical) throws AnthropicCodecException {
        validateRequest(canonical);
        List<String> systemParts = new ArrayList<>();
        if (nonBlank(canonical.system())) {
            systemParts.add(canonical.system());
        }
        int lastUserIndex = -1;
        for (int i = 0; i < canonical.messages().size(); i++) {
            Message message = canonical.messages().get(i);
            if (message instanceof SystemMessage system) {
                if (nonBlank(system.content())) {
                    systemParts.add(system.content());
                }
            } else if (message instanceof DeveloperMessage developer) {
                // The Anthropic wire has no developer role — LiteLLM maps it to a
                // system prompt for non-OpenAI providers (map_developer_role_to_system_role),
                // so developer content merges into the top-level system field (documented
                // non-idempotence on the Anthropic leg).
                if (nonBlank(developer.content())) {
                    systemParts.add(developer.content());
                }
            } else if (message instanceof UserMessage) {
                lastUserIndex = i;
            }
        }
        String joinedSystem = systemParts.isEmpty() ? null : String.join(SYSTEM_SEPARATOR, systemParts);
        boolean systemCached = nonBlank(joinedSystem) && PromptCache.isEphemeral(canonical.cacheControl());
        Object userFallbackCache = systemCached ? null : canonical.cacheControl();
        List<AnthropicMessage> dtoMessages = new ArrayList<>();
        for (int i = 0; i < canonical.messages().size(); i++) {
            Message message = canonical.messages().get(i);
            if (message instanceof SystemMessage || message instanceof DeveloperMessage) {
                continue;
            }
            Object fallback =
                    i == lastUserIndex && PromptCache.isEphemeral(userFallbackCache) ? userFallbackCache : null;
            dtoMessages.add(toAnthropicMessage(message, fallback));
        }
        Map<String, Object> extras = new HashMap<>(canonical.extras());
        Object systemWire = systemCached
                ? List.of(new AnthropicTextBlock("text", joinedSystem, canonical.cacheControl()))
                : joinedSystem;
        AnthropicMessageRequest dto = new AnthropicMessageRequest(
                canonical.model(),
                dtoMessages,
                systemWire,
                canonical.maxTokens() == null ? DEFAULT_MAX_TOKENS : canonical.maxTokens(),
                canonical.temperature(),
                canonical.topP(),
                canonical.topK(),
                canonical.stop(),
                canonical.stream() ? Boolean.TRUE : null,
                // D1 (blessed fix): Anthropic has no stream_options — real Anthropic
                // rejects unknown request fields (400 invalid_request_error), so the
                // canonical's streamOptions (OpenAI-idiomatic) must NOT reach the
                // Anthropic wire. Decode still tolerates the field (the canonical home
                // survives); encode drops it — a documented non-idempotence, see the
                // class javadoc (8).
                null,
                toAnthropicToolsWithHosted(canonical.tools(), canonical.hostedTools()),
                toolChoiceForAnthropic(canonical.toolChoice(), extras),
                thinkingForAnthropic(canonical.reasoning()),
                canonical.cacheControl(),
                outputConfigForAnthropic(canonical.reasoning()),
                null);
        // Shape the wire extras for the Anthropic egress — remap the one field
        // with a legal home, drop the OpenAI-only fields real Anthropic rejects.
        remapUserIntoMetadata(extras);
        extras.keySet().removeAll(OPENAI_ONLY_REQUEST_EXTRAS);
        return writeWithExtras(dto, extras, REQUEST_MAPPED_KEYS, AnthropicCodecException.TYPE_INVALID_REQUEST);
    }

    // ----------------------------------------------------------------- response

    public ChatResponse decodeResponse(String json) throws AnthropicCodecException {
        JsonNode root = readTree(json, AnthropicCodecException.TYPE_API_ERROR);
        AnthropicMessageResponse dto;
        try {
            dto = mapper.treeToValue(root, AnthropicMessageResponse.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(AnthropicCodecException.TYPE_API_ERROR, "invalid Anthropic response: " + e.getMessage(), e);
        }
        return toCanonicalResponse(dto);
    }

    public String encodeResponse(ChatResponse canonical) throws AnthropicCodecException {
        validateResponse(canonical);
        List<AnthropicContentBlock> content = new ArrayList<>();
        // Hosted (server-side) tool executions are response-level output, like usage:
        // re-emit them as server_tool_use blocks ahead of the assistant content so an
        // Anthropic-sourced canonical served back to an Anthropic face keeps the
        // web-search output (the canonical loses the original block ordering, so
        // hosted calls are deterministically placed first — same contract as the
        // Responses face, which puts web_search_call items ahead of the message).
        if (canonical.hostedToolCalls() != null) {
            int searchIndex = 0;
            for (HostedToolCall hosted : canonical.hostedToolCalls()) {
                if (hosted instanceof HostedToolCall.WebSearchCall search) {
                    content.add(new AnthropicServerToolUseBlock(
                            "server_tool_use", "ws_" + searchIndex, "web_search", Map.of("query", search.query())));
                    searchIndex++;
                }
            }
        }
        for (ChatChoice choice : canonical.choices()) {
            Message message = choice.message();
            if (message instanceof AssistantMessage assistant) {
                if (assistant.toolCalls() != null && !assistant.toolCalls().isEmpty()) {
                    // mixed text + tool calls → [text block] + [tool_use blocks]
                    if (nonBlank(assistant.content())) {
                        content.add(new AnthropicTextBlock("text", assistant.content()));
                    }
                    for (ToolCall call : assistant.toolCalls()) {
                        content.add(toToolUseBlock(call));
                    }
                } else {
                    content.add(
                            new AnthropicTextBlock("text", nonBlank(assistant.content()) ? assistant.content() : ""));
                }
            } else if (message instanceof ToolMessage) {
                throw codec(
                        AnthropicCodecException.TYPE_API_ERROR,
                        "tool message in a response choice has no Anthropic home (responses carry only assistant content)");
            } else {
                // the reference renders any non-empty content as a text block; blank → "" block.
                content.add(new AnthropicTextBlock("text", nonBlank(contentOf(message)) ? contentOf(message) : ""));
            }
        }
        AnthropicMessageResponse dto = new AnthropicMessageResponse(
                canonical.id(),
                DEFAULT_RESPONSE_OBJECT,
                "assistant",
                canonical.model(),
                content,
                toAnthropicStopReason(stopReasonFor(canonical)),
                null,
                canonical.usage() == null ? null : toAnthropicUsage(canonical.usage()),
                null);
        return writeWithExtras(dto, canonical.extras(), RESPONSE_MAPPED_KEYS, AnthropicCodecException.TYPE_API_ERROR);
    }

    /**
     * Per-choice finish reason preferred, response-level {@code stopReason} fallback —
     * mirrors {@link OpenAiMessageCodec#finishReasonFor}. The same fact is
     * stored twice on the canonical (per-choice {@link ChatChoice#finishReason} and
     * response-level {@link ChatResponse#stopReason}, kept in sync by convention on
     * decode); reading the per-choice spelling here means a hand-built canonical whose
     * two spellings diverge encodes consistently across both faces instead of silently
     * dropping the per-choice reason. The Anthropic wire carries a single
     * {@code stop_reason}, so the first choice is the deterministic source.
     */
    private static String stopReasonFor(ChatResponse canonical) {
        if (canonical.choices() != null && !canonical.choices().isEmpty()) {
            String choiceFinish = canonical.choices().get(0).finishReason();
            if (choiceFinish != null) {
                return choiceFinish;
            }
        }
        return canonical.stopReason();
    }

    // -------------------------------------------------------------------- chunk

    /**
     * One SSE event payload → canonical chunk. Returns {@code null} for no-op events
     * ({@code content_block_start} with a text block, {@code content_block_stop},
     * {@code message_stop}, {@code ping}, unknown event types — the reference ignores them).
     * {@code content_block_start} with a {@code tool_use} block → the first-fragment
     * chunk (id + name + empty arguments, role announced); {@code content_block_delta}
     * with an {@code input_json_delta} → a verbatim-fragment chunk keyed by the block
     * index. Throws for {@code error} events ({@code api_error}).
     */
    public StreamChunk decodeChunk(String eventType, String dataJson) throws AnthropicCodecException {
        if (eventType == null) {
            return null;
        }
        return switch (eventType) {
            case "message_start" ->
                chunkFromMessageStart(
                        parse(dataJson, AnthropicMessageStart.class, AnthropicCodecException.TYPE_API_ERROR));
            case "content_block_start" ->
                chunkFromContentBlockStart(
                        parse(dataJson, AnthropicContentBlockStart.class, AnthropicCodecException.TYPE_API_ERROR));
            case "content_block_stop", "message_stop", "ping" -> null;
            case "content_block_delta" ->
                chunkFromContentBlockDelta(
                        parse(dataJson, AnthropicContentBlockDelta.class, AnthropicCodecException.TYPE_API_ERROR));
            case "message_delta" -> {
                AnthropicMessageDelta delta =
                        parse(dataJson, AnthropicMessageDelta.class, AnthropicCodecException.TYPE_API_ERROR);
                yield chunkFromMessageDelta(delta, toCanonicalUsage(delta.usage()));
            }
            case "error" -> {
                AnthropicErrorPayload payload =
                        parse(dataJson, AnthropicErrorPayload.class, AnthropicCodecException.TYPE_API_ERROR);
                AnthropicErrorBody body = payload.error();
                String message = body == null || body.message() == null ? "upstream stream error" : body.message();
                throw codec(AnthropicCodecException.TYPE_API_ERROR, message);
            }
            default -> null; // unknown event types are ignored (tolerance)
        };
    }

    /**
     * Stateful decoder for the Anthropic SSE → canonical-stream direction:
     * accumulates the prompt side of usage from {@code message_start} and the completion
     * side from {@code message_delta}, delivering the merged terminal usage on the
     * {@code message_delta} chunk. One instance per SSE stream; the adapter drives
     * {@code decodeChunk} per event frame. See {@link AnthropicStreamDecoder}.
     */
    public AnthropicStreamDecoder newStreamDecoder() throws AnthropicCodecException {
        return new AnthropicStreamDecoderImpl(this);
    }

    /** Stateful encoder for the canonical-stream → Anthropic-event direction (multi-block:
     * interleaved text + N tool calls). One instance per SSE stream; the publisher
     * drives {@code feed} per canonical chunk and <em>must always call
     * {@code finish}</em> (see {@link AnthropicStreamEncoder}).
     */
    public AnthropicStreamEncoder newStreamEncoder() throws AnthropicCodecException {
        return new AnthropicStreamEncoderImpl(mapper);
    }

    // ------------------------------------------------------------- request decode

    private ChatRequest toCanonicalRequest(AnthropicMessageRequest dto) {
        // A JSON literal `null` body binds the DTO to null — dereferencing
        // it below would NPE (a 500) for purely client-malformed input. The real
        // Anthropic API 400s a null body, so the codec's typed invalid-request error is
        // the correct wire behavior (mirrors the OpenAI codec's null-body guard).
        if (dto == null) {
            throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "request body must be a JSON object");
        }
        String model = dto.model();
        if (!nonBlank(model)) {
            throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "model is required and must be non-blank");
        }
        List<AnthropicMessage> dtoMessages = dto.messages();
        if (dtoMessages == null || dtoMessages.isEmpty()) {
            throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "messages must be non-null and non-empty");
        }
        Map<String, Object> extras = new HashMap<>(dto.extras());
        List<Message> messages = new ArrayList<>();
        for (AnthropicMessage dtoMessage : dtoMessages) {
            ChatRole role = requireAnthropicRole(dtoMessage.role());
            messages.addAll(toCanonicalMessages(role, dtoMessage));
            foldMessageExtras(dtoMessage, extras);
        }
        Object cacheControl = dto.cacheControl();
        if (!PromptCache.isEphemeral(cacheControl)) {
            Object fromSystem = cacheControlFromAnthropicSystem(dto.system());
            if (PromptCache.isEphemeral(fromSystem)) {
                cacheControl = fromSystem;
            } else {
                cacheControl = cacheControlFromParts(messages);
                if (!PromptCache.isEphemeral(cacheControl)) {
                    // Assistant text/tool_use blocks have no per-block canonical home
                    // (AssistantMessage carries no parts), but cache_control there is
                    // the standard agent-loop caching pattern — capture it into the
                    // request-level fallback so the aa round trip re-emits a marker
                    // (on the system block or the last user message) instead of
                    // silently degrading to cache misses.
                    cacheControl = cacheControlFromAssistantBlocks(dto.messages());
                }
            }
        }
        SplitTools split = splitAnthropicTools(dto.tools());
        return new ChatRequest(
                model,
                messages,
                toCanonicalSystem(dto.system()),
                split.functionTools(),
                ToolChoiceMapper.anthropicToCanonical(dto.toolChoice(), extras),
                dto.temperature(),
                dto.topP(),
                dto.topK(),
                dto.maxTokens(),
                dto.stopSequences(),
                null, // seed — no Anthropic wire home (dropped, documented)
                null, // n
                null, // frequencyPenalty
                null, // presencePenalty
                null, // logitBias
                null, // responseFormat
                dto.stream() != null && dto.stream(),
                dto.streamOptions(),
                toReasoning(dto.thinking(), dto.outputConfig(), extras),
                cacheControl,
                split.hostedTools(),
                unmodifiable(extras),
                null);
    }

    /** Wire-only message fields (unknown fields) fold into the request extras. */
    private static void foldMessageExtras(AnthropicMessage dtoMessage, Map<String, Object> extras) {
        extras.putAll(dtoMessage.extras());
    }

    /**
     * One wire message → zero or more canonical messages. String content passes through;
     * array content maps per block type (partition_content +
     * {@code build_canonical_messages} precedent): text blocks join into the message
     * content, {@code tool_use} blocks become {@link ToolCall}s on an assistant message,
     * {@code tool_result} blocks become {@link ToolMessage}s (prepended by a user message
     * when text is present), thinking blocks are dropped (documented + pinned), image
     * blocks become multimodal {@link UserMessage} parts.
     */
    private List<Message> toCanonicalMessages(ChatRole role, AnthropicMessage dto) {
        Object content = dto.content();
        if (content == null) {
            return List.of(role == ChatRole.USER ? new UserMessage(null) : new AssistantMessage(null, null));
        }
        if (content instanceof String text) {
            return List.of(role == ChatRole.USER ? new UserMessage(text) : new AssistantMessage(text, null));
        }
        if (content instanceof List<?> list) {
            StringBuilder text = new StringBuilder();
            List<ContentPart> parts = new ArrayList<>();
            List<ToolCall> toolCalls = new ArrayList<>();
            List<ToolMessage> toolResults = new ArrayList<>();
            boolean sawImage = false;
            for (Object item : list) {
                AnthropicContentBlock block = toBlock(item);
                if (block instanceof AnthropicTextBlock textBlock) {
                    if (textBlock.text() != null) {
                        text.append(textBlock.text());
                        parts.add(new TextContent(textBlock.text(), textBlock.cacheControl()));
                    }
                } else if (block instanceof AnthropicThinkingBlock) {
                    // dropped: the canonical model has no thinking-content home (documented).
                } else if (block instanceof AnthropicImageBlock image) {
                    if (role != ChatRole.USER) {
                        throw codec(
                                AnthropicCodecException.TYPE_INVALID_REQUEST,
                                "image block is only legal on user messages");
                    }
                    sawImage = true;
                    parts.add(imageSourceFromAnthropic(image.source(), image.cacheControl()));
                } else if (block instanceof AnthropicToolUseBlock toolUse) {
                    if (role == ChatRole.USER) {
                        throw codec(
                                AnthropicCodecException.TYPE_INVALID_REQUEST,
                                "tool_use block in a user message is malformed (tool_use belongs to assistant messages)");
                    }
                    toolCalls.add(new ToolCall(
                            toolUse.id(),
                            "function", // Anthropic has no type field; canonical normalizes to "function"
                            new FunctionCall(
                                    toolUse.name(),
                                    writeRawJson(toolUse.input(), AnthropicCodecException.TYPE_INVALID_REQUEST))));
                } else if (block instanceof AnthropicToolResultBlock toolResult) {
                    if (role == ChatRole.ASSISTANT) {
                        throw codec(
                                AnthropicCodecException.TYPE_INVALID_REQUEST,
                                "tool_result block in an assistant message is malformed (tool_result belongs to user messages)");
                    }
                    toolResults.add(new ToolMessage(toolResult.toolUseId(), toolResultText(toolResult.content())));
                } else {
                    throw codec(
                            AnthropicCodecException.TYPE_INVALID_REQUEST,
                            "content block " + blockType(block) + " is not supported");
                }
            }
            if (!toolResults.isEmpty()) {
                // the reference maybe_prepend_text: text rides a user message ahead of the tool results.
                List<Message> result = new ArrayList<>();
                if (sawImage) {
                    result.add(UserMessage.multimodal(parts));
                } else if (!text.isEmpty()) {
                    result.add(new UserMessage(text.toString()));
                }
                result.addAll(toolResults);
                return result;
            }
            if (toolCalls.isEmpty()) {
                boolean sawCache = parts.stream()
                        .anyMatch(p -> p instanceof TextContent t && PromptCache.isEphemeral(t.cacheControl())
                                || p instanceof ImageSourceContent img && PromptCache.isEphemeral(img.cacheControl()));
                if (role == ChatRole.USER && (sawImage || sawCache)) {
                    return List.of(UserMessage.multimodal(parts));
                }
                return List.of(
                        role == ChatRole.USER
                                ? new UserMessage(text.toString())
                                : new AssistantMessage(text.toString(), null));
            }
            String assistantText = text.isEmpty() ? null : text.toString();
            return List.of(new AssistantMessage(assistantText, toolCalls));
        }
        throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "content must be a string or an array of blocks");
    }

    @SuppressWarnings("unchecked")
    private ImageSourceContent imageSourceFromAnthropic(Object source, Object cacheControl) {
        if (!(source instanceof Map<?, ?> map)) {
            throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "image.source must be an object");
        }
        Map<String, Object> m = (Map<String, Object>) map;
        Object typeObj = m.get("type");
        String type = typeObj == null ? null : String.valueOf(typeObj);
        if ("base64".equals(type)) {
            Object mediaType = m.get("media_type");
            Object data = m.get("data");
            if (data == null || String.valueOf(data).isBlank()) {
                throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "image.source.data is required for base64");
            }
            ImageSourceContent image = ImageSourceContent.base64(
                    mediaType == null ? "image/png" : String.valueOf(mediaType), String.valueOf(data));
            return cacheControl == null
                    ? image
                    : new ImageSourceContent(image.type(), image.mediaType(), image.data(), image.url(), cacheControl);
        }
        if ("url".equals(type)) {
            Object url = m.get("url");
            if (url == null || String.valueOf(url).isBlank()) {
                throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "image.source.url is required for url");
            }
            ImageSourceContent image = ImageSourceContent.url(String.valueOf(url));
            return cacheControl == null
                    ? image
                    : new ImageSourceContent(image.type(), image.mediaType(), image.data(), image.url(), cacheControl);
        }
        throw codec(
                AnthropicCodecException.TYPE_INVALID_REQUEST,
                "unsupported image.source.type: " + type + " (supported: base64, url)");
    }

    /** {@code tool_result} content → canonical string: string form passes through, block arrays join text. */
    private String toolResultText(Object content) {
        if (content == null) {
            return null;
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> list) {
            StringBuilder text = new StringBuilder();
            for (Object item : list) {
                AnthropicContentBlock block = toBlock(item);
                if (block instanceof AnthropicTextBlock textBlock) {
                    if (textBlock.text() != null) {
                        text.append(textBlock.text());
                    }
                } else {
                    // the reference serializes non-text parts; the canonical home is a string, so
                    // anything beyond text blocks has no faithful representation — reject.
                    throw codec(
                            AnthropicCodecException.TYPE_INVALID_REQUEST,
                            "tool_result content block " + blockType(block)
                                    + " is not supported (multimodal out of scope)");
                }
            }
            return text.toString();
        }
        throw codec(
                AnthropicCodecException.TYPE_INVALID_REQUEST,
                "tool_result content must be a string or an array of blocks");
    }

    /**
     * Top-level {@code system} → canonical string: string form passes through, text-block
     * array form is joined; any non-text block is rejected (Anthropic system accepts text
     * blocks only — multimodal/thinking system content out of scope).
     */
    private String toCanonicalSystem(Object system) {
        if (system == null) {
            return null;
        }
        if (system instanceof String text) {
            return text;
        }
        if (system instanceof List<?> list) {
            StringBuilder text = new StringBuilder();
            for (Object item : list) {
                AnthropicContentBlock block = toBlock(item);
                if (block instanceof AnthropicTextBlock textBlock) {
                    if (textBlock.text() != null) {
                        text.append(textBlock.text());
                    }
                } else {
                    throw codec(
                            AnthropicCodecException.TYPE_INVALID_REQUEST,
                            "system accepts text blocks only (got " + blockType(block) + ")");
                }
            }
            return text.toString();
        }
        throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "system must be a string or an array of text blocks");
    }

    private Object cacheControlFromAnthropicSystem(Object system) {
        if (!(system instanceof List<?> list)) {
            return null;
        }
        Object found = null;
        for (Object item : list) {
            AnthropicContentBlock block = toBlock(item);
            if (block instanceof AnthropicTextBlock textBlock && PromptCache.isEphemeral(textBlock.cacheControl())) {
                found = textBlock.cacheControl();
            }
        }
        return found;
    }

    private static Object cacheControlFromParts(List<Message> messages) {
        for (Message message : messages) {
            if (message instanceof UserMessage user && user.isMultimodal()) {
                for (ContentPart part : user.parts()) {
                    Object marker =
                            switch (part) {
                                case TextContent text -> text.cacheControl();
                                case ImageUrlContent image -> image.cacheControl();
                                case ImageSourceContent source -> source.cacheControl();
                            };
                    if (PromptCache.isEphemeral(marker)) {
                        return marker;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Lowest-priority marker source: an ephemeral {@code cache_control} on an assistant
     * message's text or {@code tool_use} block (the last one wins, mirroring the other
     * scans). Legal on the Anthropic wire and the standard agent-loop caching pattern;
     * the canonical model has no per-block home for assistant content, so the marker
     * rides the request-level {@code cacheControl} fallback and encode re-emits it on
     * the system block (when a system prompt exists) or the last user message —
     * the single-slot placement rule, documented in docs/providers.md.
     */
    private Object cacheControlFromAssistantBlocks(List<AnthropicMessage> messages) {
        Object found = null;
        for (AnthropicMessage message : messages) {
            if (!"assistant".equals(message.role()) || !(message.content() instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                AnthropicContentBlock block = toBlock(item);
                Object marker = null;
                if (block instanceof AnthropicTextBlock text) {
                    marker = text.cacheControl();
                } else if (block instanceof AnthropicToolUseBlock toolUse) {
                    marker = toolUse.cacheControl();
                }
                if (PromptCache.isEphemeral(marker)) {
                    found = marker;
                }
            }
        }
        return found;
    }

    /** The wire {@code thinking} object rides the -reserved {@code reasoning} slot. */
    private static Map<String, Object> toReasoning(Object thinking, Map<String, Object> extras) {
        return toReasoning(thinking, null, extras);
    }

    /**
     * Fold {@code thinking} plus {@code output_config.effort} into the canonical
     * reasoning map. Claude Code sends {@code thinking:{type:"adaptive",display:…}}
     * with a sibling {@code output_config:{effort}} — both must survive decode so
     * encode can rebuild the modern Anthropic spelling.
     */
    private static Map<String, Object> toReasoning(
            Object thinking, Map<String, Object> outputConfig, Map<String, Object> extras) {
        Map<String, Object> result = new HashMap<>();
        if (thinking instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else if (thinking != null) {
            extras.put("thinking", thinking);
        }
        if (outputConfig != null && outputConfig.get("effort") != null && !result.containsKey("effort")) {
            result.put("effort", outputConfig.get("effort"));
        }
        return result.isEmpty() ? null : Collections.unmodifiableMap(result);
    }

    private AnthropicContentBlock toBlock(Object item) {
        if (item instanceof AnthropicContentBlock block) {
            return block;
        }
        try {
            return mapper.convertValue(item, AnthropicContentBlock.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(
                    AnthropicCodecException.TYPE_INVALID_REQUEST, "unrecognized content block: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ request encode

    private AnthropicMessage toAnthropicMessage(Message message, Object fallbackCache) {
        return switch (message) {
            case SystemMessage system ->
                throw new IllegalStateException("system messages merge into the top-level system field");
            case DeveloperMessage developer ->
                throw new IllegalStateException("developer messages merge into the top-level system field");
            case UserMessage user -> {
                if (user.isMultimodal()) {
                    List<AnthropicContentBlock> blocks = new ArrayList<>();
                    for (ContentPart part : user.parts()) {
                        blocks.add(contentPartToAnthropicBlock(part));
                    }
                    yield new AnthropicMessage("user", blocks, null);
                }
                if (PromptCache.isEphemeral(fallbackCache) && nonBlank(user.content())) {
                    yield new AnthropicMessage(
                            "user", List.of(new AnthropicTextBlock("text", user.content(), fallbackCache)), null);
                }
                yield new AnthropicMessage("user", user.content(), null);
            }
            case AssistantMessage assistant -> {
                if (assistant.toolCalls() != null && !assistant.toolCalls().isEmpty()) {
                    // mixed text + tool calls → content block array
                    List<AnthropicContentBlock> blocks = new ArrayList<>();
                    if (nonBlank(assistant.content())) {
                        blocks.add(new AnthropicTextBlock("text", assistant.content()));
                    }
                    for (ToolCall call : assistant.toolCalls()) {
                        blocks.add(toToolUseBlock(call));
                    }
                    yield new AnthropicMessage("assistant", blocks, null);
                }
                yield new AnthropicMessage("assistant", assistant.content(), null);
            }
            case ToolMessage tool -> {
                if (!nonBlank(tool.toolCallId())) {
                    throw codec(
                            AnthropicCodecException.TYPE_INVALID_REQUEST,
                            "tool message requires a non-blank tool_call_id");
                }
                yield new AnthropicMessage(
                        "user",
                        List.of(new AnthropicToolResultBlock("tool_result", tool.toolCallId(), tool.content(), null)),
                        null);
            }
        };
    }

    /** Canonical {@code ToolCall} → Anthropic {@code tool_use} block (arguments → input object). */
    private AnthropicToolUseBlock toToolUseBlock(ToolCall call) {
        FunctionCall function = call.function();
        String toolName = function == null ? null : function.name();
        String raw = function == null ? null : function.arguments();
        return new AnthropicToolUseBlock(
                "tool_use",
                call.id(),
                toolName,
                raw == null || raw.isBlank() ? Map.of() : readInputObject(raw, toolName));
    }

    /**
     * Wire {@code tools} → canonical {@code tools}: {@code input_schema} object → raw-JSON
     * schema string, {@code description} → {@code ToolDefinition.description}, type
     * normalized to {@code "function"} (Anthropic has no type field — documented).
     */
    private record SplitTools(List<ToolDefinition> functionTools, List<HostedToolDefinition> hostedTools) {}

    /**
     * Wire {@code tools} → function definitions plus hosted web-search. Anthropic's
     * {@code web_search_20250305} is a server tool, not a client function.
     */
    private SplitTools splitAnthropicTools(List<AnthropicTool> tools) {
        if (tools == null || tools.isEmpty()) {
            return new SplitTools(null, null);
        }
        List<ToolDefinition> functions = new ArrayList<>();
        List<HostedToolDefinition> hosted = new ArrayList<>();
        for (AnthropicTool tool : tools) {
            if (isWebSearchTool(tool)) {
                hosted.add(new HostedToolDefinition.WebSearch(
                        searchContextSizeFromMaxUses(tool.maxUses()), tool.userLocation()));
            } else {
                functions.add(new ToolDefinition(
                        "function",
                        tool.name(),
                        tool.description(),
                        tool.inputSchema() == null
                                ? null
                                : writeRawJson(tool.inputSchema(), AnthropicCodecException.TYPE_INVALID_REQUEST),
                        null,
                        tool.cacheControl()));
            }
        }
        return new SplitTools(
                functions.isEmpty() ? null : List.copyOf(functions), hosted.isEmpty() ? null : List.copyOf(hosted));
    }

    private static boolean isWebSearchTool(AnthropicTool tool) {
        String type = tool.type();
        return "web_search_20250305".equals(type)
                || "web_search".equals(type)
                || (type != null && type.startsWith("web_search_"));
    }

    private static String searchContextSizeFromMaxUses(Integer maxUses) {
        if (maxUses == null) {
            return null;
        }
        return switch (maxUses) {
            case 1 -> "low";
            case 5 -> "medium";
            case 10 -> "high";
            default -> null;
        };
    }

    /**
     * The canonical tools + hosted tools → the Anthropic tools array. Hosted web
     * search becomes the {@code web_search_20250305} server tool (search_context_size →
     * max_uses via the LiteLLM table low 1 / medium 5 / high 10; a null size omits
     * max_uses and takes the provider default; user_location passes through). A hosted
     * tool the Anthropic leg cannot serve is a typed 400 — never a silent drop.
     */
    private List<AnthropicTool> toAnthropicToolsWithHosted(
            List<ToolDefinition> tools, List<HostedToolDefinition> hostedTools) throws AnthropicCodecException {
        List<ToolDefinition> functionTools = tools == null ? List.of() : tools;
        List<AnthropicTool> result = new ArrayList<>(toAnthropicTools(functionTools));
        if (hostedTools != null) {
            for (HostedToolDefinition hosted : hostedTools) {
                if (hosted instanceof HostedToolDefinition.WebSearch webSearch) {
                    Integer maxUses = null;
                    if (webSearch.searchContextSize() != null) {
                        maxUses = switch (webSearch.searchContextSize()) {
                            case "low" -> 1;
                            case "medium" -> 5;
                            case "high" -> 10;
                            default ->
                                throw codec(
                                        AnthropicCodecException.TYPE_INVALID_REQUEST,
                                        "unsupported search_context_size for the Anthropic web-search tool: "
                                                + webSearch.searchContextSize());
                        };
                    }
                    result.add(new AnthropicTool(
                            "web_search", null, null, null, "web_search_20250305", maxUses, webSearch.userLocation()));
                } else {
                    throw codec(
                            AnthropicCodecException.TYPE_INVALID_REQUEST,
                            "unsupported_hosted_tool on the Anthropic leg: "
                                    + hosted.getClass().getSimpleName());
                }
            }
        }
        return result.isEmpty() ? null : result;
    }

    /**
     * Canonical {@code tools} (schema as raw-JSON {@code ToolDefinition.inputSchema}) →
     * wire {@code tools} ({@code input_schema} object). Missing/blank schema → the default
     * {@code {"type":"object","properties":{}}} (openai_tool_to_anthropic
     * precedent); invalid raw JSON → {@code TYPE_INVALID_REQUEST} naming the tool.
     */
    private List<AnthropicTool> toAnthropicTools(List<ToolDefinition> tools) {
        if (tools == null) {
            return null;
        }
        List<AnthropicTool> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            result.add(new AnthropicTool(
                    tool.name(),
                    tool.description(),
                    readInputObject(tool.inputSchema(), tool.name()),
                    tool.cacheControl()));
        }
        return result;
    }

    /**
     * Canonical tool-argument/schema raw JSON → decoded object. Blank → the default
     * {@code {"type":"object","properties":{}}} (default) — used for tool
     * <em>definition</em> {@code input_schema} only; the tool-<em>call</em> path maps
     * blank arguments to the empty object {@code {}} (see {@link #toToolUseBlock}, which
     * mirrors the streaming encoder's {@code Map.of} input). Invalid JSON → a typed
     * exception naming the tool (documented divergence from the reference implementation's silent {@code %{}}).
     */
    private Map<String, Object> readInputObject(String raw, String toolName) {
        if (raw == null || raw.isBlank()) {
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("type", TOOL_SCHEMA_DEFAULT_TYPE);
            defaults.put(TOOL_SCHEMA_DEFAULT_PROPERTIES, Map.of());
            return defaults;
        }
        try {
            return mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(
                    AnthropicCodecException.TYPE_INVALID_REQUEST,
                    "tool '" + (toolName == null ? "?" : toolName)
                            + "' arguments must be a JSON object (input_schema/input): " + e.getMessage(),
                    e);
        }
    }

    /** Canonical tool choice → Anthropic {@code tool_choice} (consuming the disable-parallel extras key). */
    private static Object toolChoiceForAnthropic(Object toolChoice, Map<String, Object> extras) {
        Object mapped = ToolChoiceMapper.canonicalToAnthropic(toolChoice);
        if (Boolean.TRUE.equals(extras.remove(ToolChoiceMapper.EXTRAS_DISABLE_PARALLEL))
                && mapped instanceof Map<?, ?> map) {
            Map<String, Object> withFlag = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                withFlag.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            withFlag.put("disable_parallel_tool_use", true);
            return withFlag;
        }
        return mapped;
    }

    // ----------------------------------------------------------- response mapping

    private ChatResponse toCanonicalResponse(AnthropicMessageResponse dto) {
        String stopReason = toCanonicalStopReason(dto.stopReason());
        List<AnthropicContentBlock> blocks = dto.content();
        List<ChatChoice> choices = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        List<HostedToolCall> hostedCalls = new ArrayList<>();
        if (blocks != null) {
            for (AnthropicContentBlock block : blocks) {
                if (block instanceof AnthropicServerToolUseBlock serverToolUse) {
                    // The web-search server tool becomes a canonical hosted call
                    // (response-level output); other server-tool names keep the unknown
                    // tolerance (dropped) until they gain a translation.
                    if ("web_search".equals(serverToolUse.name())) {
                        Object query = serverToolUse.input() == null
                                ? null
                                : serverToolUse.input().get("query");
                        hostedCalls.add(new HostedToolCall.WebSearchCall(query instanceof String q ? q : ""));
                    }
                    continue;
                }
                if (block instanceof AnthropicTextBlock textBlock) {
                    if (textBlock.text() != null) {
                        text.append(textBlock.text());
                    }
                } else if (block instanceof AnthropicThinkingBlock) {
                    // dropped: the reference rejects thinking blocks from choices (documented + pinned).
                } else if (block instanceof AnthropicToolUseBlock toolUse) {
                    toolCalls.add(new ToolCall(
                            toolUse.id(),
                            "function", // Anthropic has no type field; canonical normalizes to "function"
                            new FunctionCall(
                                    toolUse.name(),
                                    isEmptyInput(toolUse.input())
                                            ? ""
                                            : writeRawJson(toolUse.input(), AnthropicCodecException.TYPE_API_ERROR))));
                } else if (block instanceof AnthropicToolResultBlock) {
                    // tool_result is a request-side (user-role) block — it has no place in
                    // a response, so this is genuinely malformed and stays a hard error.
                    throw codec(
                            AnthropicCodecException.TYPE_API_ERROR,
                            "response content block " + blockType(block)
                                    + " has no place in a response (tool_result is request-side)");
                } else {
                    // AnthropicImageBlock (assistant output has no canonical multimodal
                    // home — vision is request-face only) and AnthropicUnknownBlock
                    // (web_search_tool_result, fallback, …) are dropped here,
                    // mirroring the streaming path (the non-streaming decode must
                    // agree with the streaming tolerance — a payload that streams must decode,
                    // Anthropic's versioning contract). No abort; no content contributed.
                }
            }
        }
        // A response whose content is empty or entirely dropped (e.g. thinking-only) still
        // yields exactly one choice with empty content — zero choices would be a broken 200
        // for OpenAI-face clients (an empty choices array). The encode side mirrors this by
        // rendering blank content as a "" text block.
        String assistantText = text.isEmpty() && !toolCalls.isEmpty() ? null : text.toString();
        // Anthropic has a single content array: one canonical choice, index 0.
        choices.add(new ChatChoice(
                0, new AssistantMessage(assistantText, toolCalls.isEmpty() ? null : toolCalls), stopReason));
        return new ChatResponse(
                dto.id() == null ? DEFAULT_RESPONSE_ID : dto.id(),
                dto.type() == null ? DEFAULT_RESPONSE_OBJECT : dto.type(),
                0L, // Anthropic has no created timestamp (deterministic)
                dto.model() == null ? DEFAULT_RESPONSE_MODEL : dto.model(),
                choices,
                toCanonicalUsage(dto.usage()),
                stopReason,
                hostedCalls.isEmpty() ? null : List.copyOf(hostedCalls),
                unmodifiable(new HashMap<>(dto.extras())),
                null);
    }

    // ------------------------------------------------------------- chunk mapping

    private static StreamChunk chunkFromMessageStart(AnthropicMessageStart start) {
        AnthropicMessageResponse message = start.message();
        Map<String, Object> extras = new HashMap<>();
        if (message != null) {
            extras.putAll(message.extras());
        }
        return new StreamChunk(
                message == null ? null : message.id(),
                DEFAULT_RESPONSE_OBJECT,
                0L,
                message == null ? null : message.model(),
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, null, null), null)),
                null, // the start's usage carries the real prompt count, but it is NOT emitted here —
                // governance settles on the terminal usage-bearing chunk, so emitting it would
                // settle with completion=0. The stateful stream decoder accumulates it and
                // delivers the merged prompt+completion usage on the message_delta chunk.
                unmodifiable(extras));
    }

    /**
     * {@code content_block_start} with a {@code tool_use} block → the first tool-call
     * fragment chunk (id + name + arguments, role announced — mirrors the OpenAI
     * wire's first fragment). Per the Anthropic fine-grained tool-streaming spec the
     * start event's {@code input} is always {@code {}} and real arguments arrive via
     * {@code input_json_delta}; a non-conformant upstream that ships the full input in
     * the start block (and no deltas) is treated as the first fragment's arguments
     * (defense-in-depth). Text/unknown block-open signals carry no canonical content →
     * null.
     */
    private StreamChunk chunkFromContentBlockStart(AnthropicContentBlockStart dto) {
        return chunkFromContentBlockStart(dto, dto.index());
    }

    /** As {@link #chunkFromContentBlockStart(AnthropicContentBlockStart)} but with an
     * explicit index for the canonical {@code ToolCall} — the stateful stream decoder
     * passes its renumbered tool-only index (the Anthropic content-block
     * index counts text and tool blocks; the OpenAI wire keys tool calls only). */
    StreamChunk chunkFromContentBlockStart(AnthropicContentBlockStart dto, int index) {
        AnthropicContentBlock block = dto.contentBlock();
        if (block instanceof AnthropicToolUseBlock toolUse) {
            Object input = toolUse.input();
            String arguments = isEmptyInput(input) ? "" : writeRawJson(input, AnthropicCodecException.TYPE_API_ERROR);
            return new StreamChunk(
                    null,
                    null,
                    0L,
                    null,
                    List.of(new ChunkChoice(
                            0,
                            new Delta(
                                    ChatRole.ASSISTANT,
                                    null,
                                    List.of(new ToolCall(
                                            toolUse.id(),
                                            "function",
                                            new FunctionCall(toolUse.name(), arguments),
                                            index))),
                            null)),
                    null,
                    Map.of());
        }
        return null;
    }

    /** The spec's {@code content_block_start.tool_use.input} is always {@code {}}; only a
     * non-empty input becomes the first fragment's arguments (see
     * {@link #chunkFromContentBlockStart}). */
    private static boolean isEmptyInput(Object input) {
        if (input == null) {
            return true;
        }
        if (input instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        if (input instanceof List<?> list) {
            return list.isEmpty();
        }
        return false; // a scalar/string input is content — serialize it as the first fragment
    }

    private static StreamChunk chunkFromContentBlockDelta(AnthropicContentBlockDelta dto) {
        return chunkFromContentBlockDelta(dto, dto.index());
    }

    /** As {@link #chunkFromContentBlockDelta(AnthropicContentBlockDelta)} but with an
     * explicit index for the canonical {@code ToolCall} — the stateful stream decoder
     * maps the block index through its tool-only renumbering table. */
    private static StreamChunk chunkFromContentBlockDelta(AnthropicContentBlockDelta dto, int index) {
        AnthropicDelta delta = dto.delta();
        if (delta instanceof AnthropicTextDelta textDelta) {
            return new StreamChunk(
                    null,
                    null,
                    0L,
                    null,
                    List.of(new ChunkChoice(0, new Delta(null, textDelta.text(), null), null)),
                    null,
                    Map.of());
        }
        if (delta instanceof AnthropicInputJsonDelta inputJson) {
            // verbatim partial JSON fragment keyed by the (renumbered) tool-call index
            return new StreamChunk(
                    null,
                    null,
                    0L,
                    null,
                    List.of(new ChunkChoice(
                            0,
                            new Delta(
                                    null,
                                    null,
                                    List.of(new ToolCall(
                                            null, null, new FunctionCall(null, inputJson.partialJson()), index))),
                            null)),
                    null,
                    Map.of());
        }
        return null; // absent/unknown delta — nothing to translate
    }

    private static StreamChunk chunkFromMessageDelta(AnthropicMessageDelta dto, Usage usage) {
        AnthropicStopReasonDelta delta = dto.delta();
        String finishReason = delta == null ? null : toCanonicalStopReason(delta.stopReason());
        return new StreamChunk(
                null,
                null,
                0L,
                null,
                List.of(new ChunkChoice(0, new Delta(null, null, null), finishReason)),
                usage,
                Map.of());
    }

    // ------------------------------------------------------------------- usage

    private static AnthropicUsage toAnthropicUsage(Usage usage) {
        return new AnthropicUsage(
                usage.promptTokens(),
                usage.completionTokens(),
                usage.cacheCreationInputTokens(),
                usage.cacheReadInputTokens());
    }

    private static Usage toCanonicalUsage(AnthropicUsage dto) {
        if (dto == null) {
            return null;
        }
        long prompt = dto.inputTokens() == null ? 0L : dto.inputTokens();
        long completion = dto.outputTokens() == null ? 0L : dto.outputTokens();
        // Anthropic's cache_creation_input_tokens / cache_read_input_tokens map to the
        // canonical cache fields (/cache pricing). They are ADDITIVE to input_tokens
        // (Anthropic's input_tokens counts only the non-cached input), so the derived
        // total is the FULL input (regular + cache) + output — exactly the
        // prompt + completion + cache == total invariant the OpenAI encode's restore
        // heuristic checks, so both OpenAI legs re-emit the full input count with the
        // cache split alongside it (pinned in CanonicalRoundTripPropertyTest).
        return new Usage(
                prompt,
                completion,
                prompt + nullable(dto.cacheCreationInputTokens()) + nullable(dto.cacheReadInputTokens()) + completion,
                dto.cacheCreationInputTokens(),
                dto.cacheReadInputTokens());
    }

    private static long nullable(Long value) {
        return value == null ? 0L : value;
    }

    // -------------------------------------------------------------- stop reasons

    private static String toCanonicalStopReason(String raw) {
        return StopReasonTable.anthropicToCanonical(raw);
    }

    private static String toAnthropicStopReason(String canonical) {
        return StopReasonTable.canonicalToAnthropic(canonical);
    }

    /**
     * The canonical reasoning map → the Anthropic
     * {@code thinking} object. Two spellings share the slot, distinguished
     * <b>structurally</b> (pinned on both legs):
     * <ul>
     * <li><b>Anthropic-shaped</b> ({@code type} + {@code budget_tokens} — what the ao
     * decode stores verbatim): passes through untouched (round-trip preserved);</li>
     * <li><b>effort-shaped</b> ({@code effort} — the OpenAI/Responses spelling):
     * translated to the <b>modern</b> Anthropic spelling — {@code thinking:
     * {type:"adaptive"}} plus top-level {@code output_config: {effort}} — which
     * preserves the effort value end-to-end (no budget guessing) and is what the
     * current model generation requires (live-verified: claude-sonnet-5 rejects
     * {@code thinking.type:"enabled"} with "use thinking.type.adaptive and
     * output_config.effort"); {@code "none"} omits thinking entirely (pinned);</li>
     * <li>anything else: dropped ({@code null}) — no home, never a malformed emit.</li>
     * </ul>
     * An unknown effort value is a typed invalid-request 400 (honest, never a silent
     * wrong shape).
     */
    private static Object thinkingForAnthropic(Map<String, Object> reasoning) throws AnthropicCodecException {
        if (reasoning == null || reasoning.isEmpty()) {
            return null;
        }
        if (reasoning.containsKey("type") && reasoning.containsKey("budget_tokens")) {
            return reasoning; // Anthropic-shaped: verbatim
        }
        Object type = reasoning.get("type");
        if ("adaptive".equals(type) || "enabled".equals(type) || "disabled".equals(type)) {
            // Claude Code sends {type:"adaptive", display:"omitted"}. Real Anthropic
            // rejects unknown thinking fields — emit type only.
            return Map.of("type", type);
        }
        Object effort = reasoning.get("effort");
        if (effort instanceof String spelling) {
            requireSupportedEffort(spelling);
            return "none".equals(spelling) ? null : Map.of("type", "adaptive");
        }
        return null; // summary-only or unknown shape: no home, dropped
    }

    /** The effort spellings the Anthropic leg serves (validation shared by both shapers). */
    private static void requireSupportedEffort(String spelling) throws AnthropicCodecException {
        switch (spelling) {
            case "low", "medium", "high", "minimal", "none" -> {}
            default ->
                throw codec(
                        AnthropicCodecException.TYPE_INVALID_REQUEST,
                        "unsupported reasoning effort for the Anthropic leg: " + spelling);
        }
    }

    /**
     * The companion to {@link #thinkingForAnthropic}: {@code output_config.effort} for
     * effort-shaped reasoning (the modern spelling's effort carrier). "minimal" maps
     * to "low" (the Anthropic vocabulary); "none" omits (thinking already null).
     */
    private static Map<String, Object> outputConfigForAnthropic(Map<String, Object> reasoning) {
        if (reasoning == null) {
            return null;
        }
        // type+budget_tokens is the verbatim Anthropic-shaped path (budget owns
        // the slot). type=adaptive still carries effort on a sibling output_config.
        if (reasoning.containsKey("type") && reasoning.containsKey("budget_tokens")) {
            return null;
        }
        Object effort = reasoning.get("effort");
        if (!(effort instanceof String spelling) || "none".equals(spelling)) {
            return null;
        }
        return Map.of("effort", "minimal".equals(spelling) ? "low" : spelling);
    }

    // ------------------------------------------------------------ shared helpers

    /**
     * Serialize the DTO, then merge {@code extras} at the top level. Mapped (gateway)
     * fields win on collision — mirrors the merge_extras precedence
     * ({@code Map.merge(extras, base, fn _, _, gateway -> gateway end)}); extras keys the
     * DTO already emitted are skipped, and extras keys that collide with a declared DTO
     * component are dropped even when the component is absent (the
     * hardening from the OpenAI codec, ported: an absent-optional mapped field like
     * {@code temperature} — omitted by {@code NON_NULL} — must never let an extras
     * entry re-introduce it on the wire; real Anthropic rejects unknown/contradictory
     * request fields).
     */
    private String writeWithExtras(Object dto, Map<String, Object> extras, Set<String> mappedKeys, String failureType)
            throws AnthropicCodecException {
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
            throw codec(failureType, "failed to encode Anthropic payload: " + e.getMessage(), e);
        }
    }

    private JsonNode readTree(String json, String failureType) throws AnthropicCodecException {
        try {
            return mapper.readTree(json);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(failureType, "invalid JSON: " + e.getMessage(), e);
        }
    }

    private <T> T parse(String dataJson, Class<T> type, String failureType) throws AnthropicCodecException {
        if (dataJson == null) {
            throw codec(failureType, "missing data payload for " + type.getSimpleName());
        }
        try {
            return mapper.readValue(dataJson, type);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(failureType, "invalid " + type.getSimpleName() + " payload: " + e.getMessage(), e);
        }
    }

    private String writeRawJson(Object value, String failureType) {
        try {
            return mapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException e) {
            throw codec(failureType, "failed to serialize tool input: " + e.getMessage(), e);
        }
    }

    private static void validateRequest(ChatRequest request) {
        if (!nonBlank(request.model())) {
            throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "model is required and must be non-blank");
        }
        if (request.messages() == null || request.messages().isEmpty()) {
            throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "messages must be non-null and non-empty");
        }
        for (Message message : request.messages()) {
            if (message instanceof ToolMessage tool && !nonBlank(tool.toolCallId())) {
                throw codec(
                        AnthropicCodecException.TYPE_INVALID_REQUEST, "tool message requires a non-blank tool_call_id");
            }
            if (message instanceof UserMessage user) {
                boolean hasText = nonBlank(user.content());
                boolean hasParts = user.isMultimodal();
                if (!hasText && !hasParts) {
                    throw codec(
                            AnthropicCodecException.TYPE_INVALID_REQUEST,
                            "user message requires non-blank content or multimodal parts");
                }
            }
        }
    }

    private AnthropicContentBlock contentPartToAnthropicBlock(ContentPart part) {
        return switch (part) {
            case TextContent text -> new AnthropicTextBlock("text", text.text(), text.cacheControl());
            case ImageSourceContent source -> {
                Map<String, Object> src = new HashMap<>();
                src.put("type", source.type());
                if ("base64".equals(source.type())) {
                    src.put("media_type", source.mediaType() == null ? "image/png" : source.mediaType());
                    src.put("data", source.data());
                } else {
                    src.put("url", source.url());
                }
                yield new AnthropicImageBlock("image", src, source.cacheControl());
            }
            case ImageUrlContent image -> {
                String url = image.url();
                Map<String, Object> src = new HashMap<>();
                if (url != null && url.startsWith("data:") && url.contains(";base64,")) {
                    int comma = url.indexOf(";base64,");
                    String header = url.substring(5, comma); // after data:
                    String data = url.substring(comma + ";base64,".length());
                    src.put("type", "base64");
                    src.put("media_type", header.isBlank() ? "image/png" : header);
                    src.put("data", data);
                } else {
                    src.put("type", "url");
                    src.put("url", url);
                }
                yield new AnthropicImageBlock("image", src, image.cacheControl());
            }
        };
    }

    private static void validateResponse(ChatResponse response) {
        if (!nonBlank(response.id())) {
            throw codec(AnthropicCodecException.TYPE_API_ERROR, "response id is required and must be non-blank");
        }
        if (!nonBlank(response.model())) {
            throw codec(AnthropicCodecException.TYPE_API_ERROR, "response model is required and must be non-blank");
        }
        if (response.choices() == null) {
            throw codec(AnthropicCodecException.TYPE_API_ERROR, "response choices must be non-null");
        }
        for (ChatChoice choice : response.choices()) {
            if (choice.message() instanceof ToolMessage) {
                throw codec(
                        AnthropicCodecException.TYPE_API_ERROR,
                        "tool message in a response choice has no Anthropic home (responses carry only assistant content)");
            }
        }
    }

    private static ChatRole requireAnthropicRole(String role) {
        if (!nonBlank(role)) {
            throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "message is missing a role");
        }
        return switch (role) {
            case "user" -> ChatRole.USER;
            case "assistant" -> ChatRole.ASSISTANT;
            default ->
                throw codec(AnthropicCodecException.TYPE_INVALID_REQUEST, "unknown Anthropic message role: " + role);
        };
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

    private static String blockType(AnthropicContentBlock block) {
        return switch (block) {
            case AnthropicTextBlock b -> b.type();
            case AnthropicToolUseBlock b -> b.type();
            case AnthropicToolResultBlock b -> b.type();
            case AnthropicImageBlock b -> b.type();
            case AnthropicThinkingBlock b -> b.type();
            case AnthropicServerToolUseBlock b -> b.type();
            case AnthropicUnknownBlock b -> "unknown";
        };
    }

    private static Map<String, Object> unmodifiable(Map<String, Object> map) {
        return Collections.unmodifiableMap(map);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static AnthropicCodecException codec(String type, String message) {
        return new AnthropicCodecException(type, message);
    }

    private static AnthropicCodecException codec(String type, String message, Throwable cause) {
        return new AnthropicCodecException(type, message, cause);
    }

    // ------------------------------------------------------- stream encoder impl

    /**
     * Multi-block stateful encoder (a rewrite of the earlier single-text-block encoder).
     * The first {@code feed} carrying a non-null delta opens the stream
     * ({@code message_start} + a lazily-created block 0 — text, or {@code tool_use} when
     * the first delta carries tool fragments); content-bearing deltas emit
     * {@code content_block_delta(text_delta)}; a tool fragment for a new tool closes the
     * current block and opens a {@code tool_use} block (block indices assigned in arrival
     * order), then emits {@code content_block_delta(input_json_delta, partial_json)} per
     * fragment (verbatim); text after a tool block reopens a text block. {@code finish}
     * closes the open block (when any), then {@code message_delta} (stop reason from the
     * last seen {@code finishReason} — {@code tool_use} when {@code tool_calls} — usage
     * from the last seen chunk usage) and {@code message_stop}. Role-only chunks open the
     * stream without a text delta.
     */
    private static final class AnthropicStreamEncoderImpl implements AnthropicStreamEncoder {

        private final ObjectMapper mapper;
        private boolean fed;
        private boolean started;
        private boolean finished;
        private String finishReason;
        private Usage usage;
        private int nextBlockIndex;
        private OpenBlock openBlock;

        /** The currently open content block (kind + index + tool identity); null when none is open. */
        private static final class OpenBlock {
            final int index;
            final boolean toolUse;
            final String toolId;
            final String toolName;

            OpenBlock(int index, boolean toolUse) {
                this(index, toolUse, null, null);
            }

            OpenBlock(int index, boolean toolUse, String toolId, String toolName) {
                this.index = index;
                this.toolUse = toolUse;
                this.toolId = toolId;
                this.toolName = toolName;
            }
        }

        AnthropicStreamEncoderImpl(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public List<AnthropicSseEvent> feed(StreamChunk canonical) throws AnthropicCodecException {
            if (finished) {
                return List.of();
            }
            // Capture usage before the choices==null guard: a usage-bearing chunk with no
            // choices (constructible; the OpenAI decoder always emits an empty choices list,
            // but a usage-only canonical is legal) must still be captured so finish can
            // deliver it — dropping it entirely would lose the usage and starve the stream.
            if (canonical.usage() != null) {
                usage = canonical.usage();
            }
            if (canonical.choices() == null) {
                if (canonical.usage() != null) {
                    fed = true;
                }
                return List.of();
            }
            fed = true;
            List<AnthropicSseEvent> events = new ArrayList<>();
            for (ChunkChoice choice : canonical.choices()) {
                Delta delta = choice.delta();
                // Capture the finish reason before the null-delta guard: a terminal chunk
                // with {delta: null, finishReason:...} must still drive the message_delta
                // stop_reason — the canonical stream can carry such chunks.
                if (choice.finishReason() != null) {
                    finishReason = choice.finishReason();
                }
                if (delta == null) {
                    continue;
                }
                if (!started) {
                    started = true;
                    events.add(messageStart(canonical));
                    boolean firstDeltaHasTools =
                            delta.toolCalls() != null && !delta.toolCalls().isEmpty();
                    if (!firstDeltaHasTools) {
                        // contract: the first feed lazily creates block 0 (text).
                        events.add(blockStart(0, false, null, null));
                        openBlock = new OpenBlock(0, false);
                        nextBlockIndex = 1;
                    }
                }
                if (delta.toolCalls() != null) {
                    for (ToolCall call : delta.toolCalls()) {
                        FunctionCall function = call.function();
                        String toolId = call.id();
                        String toolName = function == null ? null : function.name();
                        if (openBlock != null && !openBlock.toolUse) {
                            // Defensive: a tool fragment must never emit
                            // input_json_delta against an open *text* block. Unreachable via
                            // the real wire paths (OpenAI never interleaves text between one
                            // tool call's fragments; a re-encoded Anthropic stream reopens
                            // with a fresh id), but if it ever happens, close the text block
                            // and reopen a tool_use block instead of misrouting the delta.
                            events.add(blockStop(openBlock.index));
                            openBlock = null;
                        }
                        boolean carriesIdentity = (toolId != null && !toolId.isBlank()) || nonBlank(toolName);
                        if (openBlock != null
                                && openBlock.toolUse
                                && carriesIdentity
                                && !sameTool(openBlock, toolId, toolName)) {
                            // A fragment that repeats the open block's
                            // id/name is a continuation, not a new tool — only a genuinely
                            // different identity closes the block and opens another (two
                            // tool_use blocks with the same id are invalid on the Anthropic
                            // wire; a fragment with no id and no name is always a
                            // continuation of the open block).
                            events.add(blockStop(openBlock.index));
                            openBlock = null;
                        }
                        if (openBlock == null) {
                            int index = nextBlockIndex++;
                            events.add(blockStart(index, true, toolId, toolName));
                            openBlock = new OpenBlock(index, true, toolId, toolName);
                        }
                        String partial = function == null || function.arguments() == null ? "" : function.arguments();
                        events.add(inputJsonDelta(openBlock.index, partial));
                    }
                }
                if (delta.content() != null) {
                    if (openBlock != null && openBlock.toolUse) {
                        events.add(blockStop(openBlock.index));
                        openBlock = null;
                    }
                    if (openBlock == null) {
                        int index = nextBlockIndex++;
                        events.add(blockStart(index, false, null, null));
                        openBlock = new OpenBlock(index, false);
                    }
                    events.add(textDelta(openBlock.index, delta.content()));
                }
            }
            return List.copyOf(events);
        }

        @Override
        public List<AnthropicSseEvent> finish() throws AnthropicCodecException {
            if (finished) {
                return List.of();
            }
            finished = true;
            if (!fed) {
                // A zero-feed stream (empty Anthropic wire) must still yield a
                // well-formed terminal sequence — synthesize the message_start opener
                // (symmetric with the usage-only path below) so the face never leaves an
                // SSE wire bare with zero frames (a strict Anthropic SDK fails to
                // accumulate on an empty stream). The opener carries no id/model (none
                // were seen) — omitted by NON_NULL, exactly like the usage-only synthetic
                // opener.
                return List.of(messageStartSynthetic(), messageDelta(), messageStop());
            }
            if (!started) {
                // A stream fed only null-delta chunks never opened a
                // message_start — emitting message_delta without it is an invalid Anthropic
                // SSE sequence (a strict SDK fails to accumulate). When there is nothing to
                // deliver (no usage, no finish reason) the stream emits nothing; when a
                // usage-only chunk was fed, synthesize the missing opener so the terminal
                // sequence is well-formed and the usage is delivered.
                if (usage == null && finishReason == null) {
                    return List.of();
                }
                List<AnthropicSseEvent> synthesized = new ArrayList<>();
                synthesized.add(messageStartSynthetic());
                synthesized.add(messageDelta());
                synthesized.add(messageStop());
                return List.copyOf(synthesized);
            }
            List<AnthropicSseEvent> events = new ArrayList<>();
            if (openBlock != null) {
                events.add(blockStop(openBlock.index));
            }
            events.add(messageDelta());
            events.add(messageStop());
            return List.copyOf(events);
        }

        /** The synthesized opener for a stream that never carried a non-null delta
         * a usage-only feed still yields a valid {@code message_start} → {@code message_delta}
         * → {@code message_stop} sequence). The opener's wire usage is prompt-only
         * (Anthropic's {@code message_start} has {@code output_tokens:0}) — the captured
         * usage may be the merged terminal value, so the completion side is stripped. */
        private AnthropicSseEvent messageStartSynthetic() {
            Usage promptOnly = usage == null
                    ? null
                    : new Usage(
                            usage.promptTokens(),
                            0L,
                            usage.promptTokens(),
                            usage.cacheCreationInputTokens(),
                            usage.cacheReadInputTokens());
            return messageStart(new StreamChunk(null, null, 0L, null, List.of(), promptOnly, Map.of()));
        }

        private AnthropicSseEvent messageStart(StreamChunk chunk) {
            AnthropicMessageResponse message = new AnthropicMessageResponse(
                    chunk.id(),
                    DEFAULT_RESPONSE_OBJECT,
                    "assistant",
                    chunk.model(),
                    List.of(),
                    null,
                    null,
                    // Emit the opener chunk's real usage (the prompt side) when it
                    // carries one; zeroed 0/0 only when genuinely absent (a strict SDK
                    // requires the fields). The fabricated "msg_start"/"unknown" sentinels
                    // are gone — a blank id/model is omitted (NON_NULL) rather than leaked
                    // to a client that keys its accumulator on the message id.
                    chunk.usage() == null ? new AnthropicUsage(0L, 0L, null, null) : toAnthropicUsage(chunk.usage()),
                    null);
            return sse("message_start", new AnthropicMessageStart("message_start", message));
        }

        private AnthropicSseEvent blockStart(int index, boolean toolUse, String id, String name) {
            AnthropicContentBlock block = toolUse
                    ? new AnthropicToolUseBlock("tool_use", id, name, Map.of())
                    : new AnthropicTextBlock("text", "");
            return sse("content_block_start", new AnthropicContentBlockStart("content_block_start", index, block));
        }

        private AnthropicSseEvent textDelta(int index, String text) {
            return sse(
                    "content_block_delta",
                    new AnthropicContentBlockDelta(
                            "content_block_delta", index, new AnthropicTextDelta("text_delta", text)));
        }

        private AnthropicSseEvent inputJsonDelta(int index, String partialJson) {
            return sse(
                    "content_block_delta",
                    new AnthropicContentBlockDelta(
                            "content_block_delta",
                            index,
                            new AnthropicInputJsonDelta("input_json_delta", partialJson)));
        }

        private AnthropicSseEvent blockStop(int index) {
            return sse("content_block_stop", new AnthropicContentBlockStop("content_block_stop", index));
        }

        /**
         * Whether the fragment's identity is the tool already open in {@code open} — a
         * continuation fragment (one that repeats the open block's id/name, or that
         * carries no id and no name at all) keeps the open block; only a genuinely
         * differing identity starts a new {@code tool_use} block (
         * repeating the id on a continuation must not emit duplicate-id tool_use blocks).
         * An identity-bearing fragment after an anonymous open block (degenerate first
         * fragment with no id/name) counts as differing — it reopens so the id/name reach
         * the wire.
         */
        private static boolean sameTool(OpenBlock open, String id, String name) {
            boolean fragmentHasId = id != null && !id.isBlank();
            boolean fragmentHasName = nonBlank(name);
            if (fragmentHasId && open.toolId != null) {
                return open.toolId.equals(id);
            }
            if (fragmentHasName && open.toolName != null) {
                return open.toolName.equals(name);
            }
            // A fragment carrying no identity at all is always a continuation; one that
            // brings an identity the open block never recorded is a different tool.
            return !fragmentHasId && !fragmentHasName;
        }

        private AnthropicSseEvent messageDelta() {
            // The Anthropic spec requires input_tokens/output_tokens in
            // message_delta.usage — a canonical stream without a usage chunk (the
            // realistic path: plain Anthropic client → OpenAI upstream without
            // include_usage) must not emit "usage":{} (strict SDKs fail validation).
            AnthropicUsage dtoUsage = usage == null ? new AnthropicUsage(0L, 0L, null, null) : toAnthropicUsage(usage);
            // Same for the delta: a terminal message_delta needs a stop_reason; default
            // to end_turn when the canonical stream carried none (consistent with the
            // encode table's error → end_turn).
            String stopReason = toAnthropicStopReason(finishReason);
            return sse(
                    "message_delta",
                    new AnthropicMessageDelta(
                            "message_delta",
                            new AnthropicStopReasonDelta(stopReason == null ? "end_turn" : stopReason, null),
                            dtoUsage));
        }

        private AnthropicSseEvent messageStop() {
            return sse("message_stop", new AnthropicMessageStop("message_stop"));
        }

        private AnthropicSseEvent sse(String event, Object payload) {
            try {
                return new AnthropicSseEvent(event, mapper.writeValueAsString(payload));
            } catch (tools.jackson.core.JacksonException e) {
                throw codec(
                        AnthropicCodecException.TYPE_API_ERROR,
                        "failed to encode " + event + " payload: " + e.getMessage(),
                        e);
            }
        }
    }

    // ------------------------------------------------------- stream decoder impl

    /**
     * Stateful per-stream decoder. Anthropic splits usage across two SSE
     * events — {@code message_start} carries the prompt side
     * ({@code message.usage.input_tokens}) and {@code message_delta} the completion side
     * ({@code usage.output_tokens} — never {@code input_tokens}) — so the stateless
     * {@link #decodeChunk} cannot merge them and an Anthropic-derived canonical stream
     * used to settle {@code promptTokens=0} on every request. This decoder accumulates
     * the prompt/cache side at {@code message_start} and delivers the merged terminal
     * usage on the {@code message_delta} chunk. The {@code message_start} chunk itself
     * stays usage-free (governance settles on the terminal usage-bearing chunk — emitting
     * the prompt-only usage there would settle with completion=0).
     */
    private static final class AnthropicStreamDecoderImpl implements AnthropicStreamDecoder {

        private final AnthropicMessageCodec codec;
        private Long promptTokens;
        private Long cacheCreationTokens;
        private Long cacheReadTokens;
        // The canonical ToolCall.index is the *tool-only* streaming index
        // (the OpenAI wire semantics), but Anthropic indexes content blocks (text and
        // tools share one counter). This per-stream decoder renumbers tool-using blocks
        // into a 0-based tool-only space and maps input_json_delta fragments through the
        // block-index → tool-index table, so an ao passthrough emits contiguous OpenAI
        // tool_calls indices. The stateless decodeChunk keeps the verbatim block index
        // (a per-event mapping with no cross-event state; documented divergence).
        private int nextToolIndex;
        private final Map<Integer, Integer> blockToToolIndex = new HashMap<>();

        AnthropicStreamDecoderImpl(AnthropicMessageCodec codec) {
            this.codec = codec;
        }

        @Override
        public StreamChunk decodeChunk(String eventType, String dataJson) throws AnthropicCodecException {
            if ("message_start".equals(eventType)) {
                AnthropicMessageStart start =
                        codec.parse(dataJson, AnthropicMessageStart.class, AnthropicCodecException.TYPE_API_ERROR);
                AnthropicMessageResponse message = start.message();
                AnthropicUsage startUsage = message == null ? null : message.usage();
                if (startUsage != null) {
                    promptTokens = startUsage.inputTokens();
                    cacheCreationTokens = startUsage.cacheCreationInputTokens();
                    cacheReadTokens = startUsage.cacheReadInputTokens();
                }
                return AnthropicMessageCodec.chunkFromMessageStart(start);
            }
            if ("message_delta".equals(eventType)) {
                AnthropicMessageDelta dto =
                        codec.parse(dataJson, AnthropicMessageDelta.class, AnthropicCodecException.TYPE_API_ERROR);
                return AnthropicMessageCodec.chunkFromMessageDelta(dto, mergedUsage(dto.usage()));
            }
            if ("content_block_start".equals(eventType)) {
                AnthropicContentBlockStart start =
                        codec.parse(dataJson, AnthropicContentBlockStart.class, AnthropicCodecException.TYPE_API_ERROR);
                if (start.contentBlock() instanceof AnthropicToolUseBlock) {
                    int toolIndex = nextToolIndex++;
                    blockToToolIndex.put(start.index(), toolIndex);
                    return codec.chunkFromContentBlockStart(start, toolIndex);
                }
                return codec.decodeChunk(eventType, dataJson);
            }
            if ("content_block_delta".equals(eventType)) {
                AnthropicContentBlockDelta dto =
                        codec.parse(dataJson, AnthropicContentBlockDelta.class, AnthropicCodecException.TYPE_API_ERROR);
                if (dto.delta() instanceof AnthropicInputJsonDelta) {
                    // map the block index through the renumbering table; fall back to the
                    // verbatim block index for a fragment whose start was never seen
                    // (non-conformant/mid-stream) so the stream never misroutes.
                    Integer toolIndex = blockToToolIndex.get(dto.index());
                    int index = toolIndex == null ? dto.index() : toolIndex;
                    return AnthropicMessageCodec.chunkFromContentBlockDelta(dto, index);
                }
                return codec.decodeChunk(eventType, dataJson);
            }
            return codec.decodeChunk(eventType, dataJson);
        }

        /**
         * Merge the prompt/cache side captured at {@code message_start} with the
         * completion side carried by the terminal {@code message_delta} (Anthropic's
         * {@code message_delta} usage has no {@code input_tokens}); the terminal event's
         * own values win when both sides carry a field (cache read is split across both).
         */
        private Usage mergedUsage(AnthropicUsage dto) {
            if (dto == null && promptTokens == null) {
                return null;
            }
            long prompt = dto == null || dto.inputTokens() == null
                    ? (promptTokens == null ? 0L : promptTokens)
                    : dto.inputTokens();
            long completion = dto == null || dto.outputTokens() == null ? 0L : dto.outputTokens();
            Long cacheCreation = dto != null && dto.cacheCreationInputTokens() != null
                    ? dto.cacheCreationInputTokens()
                    : cacheCreationTokens;
            Long cacheRead =
                    dto != null && dto.cacheReadInputTokens() != null ? dto.cacheReadInputTokens() : cacheReadTokens;
            // Same additive-total contract as toCanonicalUsage: input_tokens excludes
            // the cache counts, so the merged total is full input + output — the
            // invariant the OpenAI encode restores the full input from.
            return new Usage(
                    prompt,
                    completion,
                    prompt + nullable(cacheCreation) + nullable(cacheRead) + completion,
                    cacheCreation,
                    cacheRead);
        }
    }
}
