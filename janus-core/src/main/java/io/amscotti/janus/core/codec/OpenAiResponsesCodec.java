package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.DeveloperMessage;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.HostedToolCall;
import io.amscotti.janus.core.model.HostedToolDefinition;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * The OpenAI Responses API face codec: a third
 * ingress/egress pair over the unchanged canonical model. Request decode walks the
 * input items structurally (the chat codec's role-dispatch style); response encode
 * builds the typed {@link OpenAiResponsesResponse} family from the canonical.
 *
 * <p><b>Stateless contract — every row is a typed 400 with a fixed message:</b>
 * {@code store: true}, {@code previous_response_id}, {@code conversation},
 * {@code background: true}, {@code truncation: "auto"}, hosted tool types
 * ({@code unsupported_hosted_tool: <type>}), hosted {@code tool_choice} of a
 * non-served type ({@code unsupported_hosted_tool in tool_choice: <type>} —
 * {@code {"type":"web_search"}} itself is accepted and maps to the provider default
 * "auto", matching docs/responses.md), the {@code computer_call_output} item
 * ({@code unsupported_item}), and an orphan {@code function_call_output}
 * ({@code orphan_tool_output: <call_id>}). Absent
 * {@code store} ≡ {@code false} (the OpenAI server default is true, but the official
 * SDK omits the field — a 400-on-absent would break every default client call);
 * {@code include} and {@code truncation: "disabled"} are accepted-and-dropped.
 *
 * <p><b>Documented non-idempotence (decode → canonical → encode), each test-pinned:</b>
 * <ul>
 * <li>consecutive {@code function_call} items merge into ONE assistant message
 *     (adjacency-based — an intervening item closes the group; required because
 *     keeps the ra leg Anthropic-legal);</li>
 * <li>echo items ({@code reasoning}, {@code web_search_call}, {@code item_reference})
 *     are dropped; input consisting ONLY of dropped echo items is a typed 400 (never a
 *     chat-shaped one leaking through the chat codec's validation);</li>
 * <li>{@code reasoning.summary} is dropped on decode (effort survives); the response
 *     echoes {@code reasoning: {effort, summary: null}} — the input summary is a
 *     string, the output an array of parts, so it is never echoed verbatim;</li>
 * <li>image parts inside {@code function_call_output} are dropped (the canonical
 *     ToolMessage content is text); text parts are joined;</li>
 * <li>{@code instructions} → canonical {@code system}; system/developer input message
 *     items keep their per-message canonical homes (the chat codec's merge applies on
 *     egress).</li>
 * </ul>
 *
 * <p>Throws the shared {@link OpenAiCodecException} (final, reused — the gateway's
 * {@code ErrorMapper} needs no new row). Thread-safe; {@link #create} supplies the
 * mapper (SNAKE_CASE, tolerant — the house conventions).
 */
public final class OpenAiResponsesCodec {

    private final JsonMapper mapper;

    public OpenAiResponsesCodec(JsonMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Default mapper per the codec contract (SNAKE_CASE, tolerant, single-value arrays). */
    public static OpenAiResponsesCodec create() {
        return new OpenAiResponsesCodec(JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
                .build());
    }

    // ------------------------------------------------------------------ request

    public ChatRequest decodeRequest(String json) throws OpenAiCodecException {
        if (json == null || json.isBlank()) {
            throw invalid("request body must be a JSON object");
        }
        JsonNode root = readTree(json);
        if (root == null || !root.isObject()) {
            throw invalid("request body must be a JSON object");
        }
        String model = textOf(root, "model");
        if (model == null || model.isBlank()) {
            throw invalid("model is required and must be non-blank");
        }
        // The stateless 400 rows. store: absent ≡ false; explicit true 400s.
        Boolean store = booleanOf(root, "store");
        if (Boolean.TRUE.equals(store)) {
            throw invalid("store: true is not supported (stateless gateway) — omit store or send store: false");
        }
        if (root.hasNonNull("previous_response_id")) {
            throw invalid("previous_response_id is not supported (stateless gateway) — replay the full input list");
        }
        if (root.hasNonNull("conversation")) {
            throw invalid("conversation is not supported (stateless gateway) — replay the full input list");
        }
        if (Boolean.TRUE.equals(booleanOf(root, "background"))) {
            throw invalid("background: true is not supported (stateless gateway)");
        }
        String truncation = textOf(root, "truncation");
        if ("auto".equals(truncation)) {
            throw invalid("truncation: \"auto\" is not supported (no server-side context trimming)");
        }
        JsonNode input = root.get("input");
        if (input == null || input.isNull()) {
            throw invalid("input is required");
        }
        ParsedTools parsedTools = parseTools(root.get("tools"));
        InputResult parsed = parseInput(input);
        if (parsed.messages().isEmpty()) {
            throw invalid("input must contain at least one translatable item (message, function_call, "
                    + "function_call_output) — reasoning/web_search_call echo items alone cannot seed a request");
        }
        Map<String, Object> extras = new LinkedHashMap<>();
        captureExtras(root, extras);
        // Accepted-and-dropped / stateless-400 fields never ride extras onward.
        for (String key :
                List.of("store", "previous_response_id", "conversation", "background", "truncation", "include")) {
            extras.remove(key);
        }
        return new ChatRequest(
                model,
                parsed.messages(),
                textOf(root, "instructions"),
                parsedTools.functionTools(),
                parseToolChoice(root.get("tool_choice")),
                doubleOf(root, "temperature"),
                doubleOf(root, "top_p"),
                null,
                intOf(root, "max_output_tokens"),
                null,
                null,
                null,
                null,
                null,
                null,
                parseTextFormat(root.get("text")),
                Boolean.TRUE.equals(booleanOf(root, "stream")),
                // Decision B: the Responses face forces include_usage at
                // INGRESS DECODE — response.completed carries the response object
                // including usage (LiteLLM forces it for the same reason); a
                // face-scoped, documented exception to the never-forced rule.
                Boolean.TRUE.equals(booleanOf(root, "stream")) ? ordered("include_usage", Boolean.TRUE) : null,
                parseReasoning(root.get("reasoning")),
                parsed.cacheControl(),
                parsedTools.hostedTools(),
                Collections.unmodifiableMap(extras),
                null);
    }

    /** The parsed input: canonical messages (order preserved) plus any cache marker. */
    private record InputResult(List<Message> messages, Object cacheControl) {
        InputResult(List<Message> messages) {
            this(messages, null);
        }
    }

    private InputResult parseInput(JsonNode input) throws OpenAiCodecException {
        if (input.isString()) {
            return new InputResult(List.<Message>of(new UserMessage(input.stringValue())));
        }
        if (!input.isArray()) {
            throw invalid("input must be a string or an array of items");
        }
        List<Message> messages = new ArrayList<>();
        List<ToolCall> pendingCalls = new ArrayList<>();
        Set<String> seenCallIds = new HashSet<>();
        boolean droppedEchoOnlySoFar = true;
        Object[] cacheBox = new Object[1];
        for (JsonNode item : input) {
            if (!item.isObject()) {
                throw invalid("input items must be JSON objects");
            }
            String type = textOf(item, "type");
            if (type == null) {
                throw invalid("input item requires a type");
            }
            switch (type) {
                case "message" -> {
                    closePendingCalls(messages, pendingCalls);
                    messages.add(parseMessageItem(item, cacheBox));
                    droppedEchoOnlySoFar = false;
                }
                case "function_call" -> {
                    // Consecutive function_call items merge into ONE assistant message
                    // (Anthropic rejects back-to-back assistant
                    // tool_use messages).
                    String callId = textOf(item, "call_id");
                    String name = textOf(item, "name");
                    String arguments = textOf(item, "arguments");
                    if (callId == null || callId.isBlank() || name == null || name.isBlank()) {
                        throw invalid("function_call item requires non-blank call_id and name");
                    }
                    seenCallIds.add(callId);
                    pendingCalls.add(new ToolCall(callId, "function", new FunctionCall(name, arguments, null)));
                    droppedEchoOnlySoFar = false;
                }
                case "function_call_output" -> {
                    closePendingCalls(messages, pendingCalls);
                    String callId = textOf(item, "call_id");
                    if (callId == null || callId.isBlank()) {
                        throw invalid("function_call_output item requires a non-blank call_id");
                    }
                    if (!seenCallIds.contains(callId)) {
                        throw invalid("orphan_tool_output: " + callId
                                + " — function_call_output must follow the function_call it answers");
                    }
                    messages.add(new ToolMessage(callId, parseToolOutput(item.get("output"))));
                    droppedEchoOnlySoFar = false;
                }
                case "computer_call_output" ->
                    throw invalid(
                            "unsupported_item: computer_call_output (the computer_use tool family is not served)");
                case "reasoning", "web_search_call", "item_reference", "additional_tools" -> {
                    // Model-produced echo items and Codex additional_tools (custom
                    // tool-VM payloads) are dropped — Janus does not host the Codex
                    // exec isolate. The accompanying message items still decode.
                    closePendingCalls(messages, pendingCalls);
                }
                default -> throw invalid("unknown input item type: " + type);
            }
        }
        closePendingCalls(messages, pendingCalls);
        if (droppedEchoOnlySoFar) {
            return new InputResult(List.of());
        }
        return new InputResult(messages, cacheBox[0]);
    }

    /** Flush accumulated consecutive function_calls as one assistant message. */
    private static void closePendingCalls(List<Message> messages, List<ToolCall> pendingCalls) {
        if (!pendingCalls.isEmpty()) {
            messages.add(new AssistantMessage(null, List.copyOf(pendingCalls)));
            pendingCalls.clear();
        }
    }

    private Message parseMessageItem(JsonNode item, Object[] cacheBox) throws OpenAiCodecException {
        String role = textOf(item, "role");
        if (role == null || role.isBlank()) {
            throw invalid("message input item requires a role");
        }
        JsonNode content = item.get("content");
        String joined = null;
        List<io.amscotti.janus.core.model.ContentPart> parts = null;
        if (content != null && content.isString()) {
            joined = content.stringValue();
        } else if (content != null && content.isArray()) {
            StringBuilder text = new StringBuilder();
            boolean hasImage = false;
            boolean hasCache = false;
            List<io.amscotti.janus.core.model.ContentPart> parsedParts = new ArrayList<>();
            for (JsonNode part : content) {
                String partType = textOf(part, "type");
                if (partType == null) {
                    continue;
                }
                Object partCache = null;
                if (part.has("prompt_cache_breakpoint")
                        && PromptCache.isOpenAiBreakpoint(plainValue(part.get("prompt_cache_breakpoint")))) {
                    partCache = PromptCache.EPHEMERAL;
                    hasCache = true;
                    cacheBox[0] = PromptCache.EPHEMERAL;
                }
                switch (partType) {
                    case "input_text", "output_text", "summary_text" -> {
                        String partText = textOf(part, "text");
                        if (partText != null) {
                            if (text.length() > 0) {
                                text.append('\n');
                            }
                            text.append(partText);
                        }
                        parsedParts.add(new TextContent(partText == null ? "" : partText, partCache));
                    }
                    case "input_image" -> {
                        String url = textOf(part, "image_url");
                        if (url == null || url.isBlank()) {
                            throw invalid("input_image part requires a non-blank image_url");
                        }
                        hasImage = true;
                        parsedParts.add(new ImageUrlContent(url, textOf(part, "detail"), partCache));
                    }
                    default -> {
                        // Unknown part types drop (the tolerant-input contract); the
                        // round-trip property test pins the enumeration.
                    }
                }
            }
            joined = text.length() == 0 ? null : text.toString();
            if (hasImage || (hasCache && "user".equals(role))) {
                parts = parsedParts; // multimodal: text parts + image parts
            }
        }
        return switch (role) {
            case "user" -> parts == null ? new UserMessage(joined) : UserMessage.multimodal(parts);
            case "assistant" -> new AssistantMessage(joined, null);
            case "system" -> new SystemMessage(joined);
            case "developer" -> new DeveloperMessage(joined);
            default -> throw invalid("unknown message role in input: " + role);
        };
    }

    /** Tool output: string form verbatim; part arrays join their text parts (images drop). */
    private static String parseToolOutput(JsonNode output) {
        if (output == null || output.isNull()) {
            return "";
        }
        if (output.isString()) {
            return output.stringValue();
        }
        if (output.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : output) {
                String partType = textOf(part, "type");
                if ("input_text".equals(partType) || "output_text".equals(partType) || "text".equals(partType)) {
                    String partText = textOf(part, "text");
                    if (partText == null) {
                        continue;
                    }
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(partText);
                }
            }
            return text.toString();
        }
        return output.toString();
    }

    /** Both tool families the decode produces (either list null when none present). */
    private record ParsedTools(List<ToolDefinition> functionTools, List<HostedToolDefinition> hostedTools) {}

    private ParsedTools parseTools(JsonNode tools) throws OpenAiCodecException {
        if (tools == null || tools.isNull()) {
            return new ParsedTools(null, null);
        }
        if (!tools.isArray()) {
            throw invalid("tools must be an array");
        }
        List<ToolDefinition> functionTools = new ArrayList<>();
        List<HostedToolDefinition> hosted = new ArrayList<>();
        for (JsonNode tool : tools) {
            String type = textOf(tool, "type");
            if (type == null) {
                throw invalid("tool entries require a type");
            }
            switch (type) {
                case "function" -> {
                    // parameters must be a JSON OBJECT when present —
                    // an array/primitive schema is client-malformed and previously rode
                    // the decode silently (dropped from the echo or failing later on the
                    // chat egress). Reject at the ingress, naming the tool.
                    JsonNode parameters = tool.get("parameters");
                    if (parameters != null && !parameters.isNull() && !parameters.isObject()) {
                        throw invalid("tool '" + textOf(tool, "name") + "' parameters must be a JSON object (got "
                                + parameters.getNodeType().name().toLowerCase() + ")");
                    }
                    functionTools.add(new ToolDefinition(
                            "function",
                            textOf(tool, "name"),
                            textOf(tool, "description"),
                            writeRawJson(parameters),
                            booleanObjectOf(tool, "strict")));
                }
                case "namespace" -> {
                    // Codex groups tools under type=namespace. Not a hosted
                    // Anthropic/OpenAI family — skip the wrapper (inner function
                    // tools, if any, arrive as sibling function entries).
                }
                case "web_search", "web_search_preview" -> {
                    // The first hosted tool (the legacy _preview alias accepted).
                    Map<String, Object> locationMap = null;
                    JsonNode location = tool.get("user_location");
                    if (location != null && location.isObject()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> converted = (Map<String, Object>) plainValue(location);
                        locationMap = converted;
                    }
                    hosted.add(new HostedToolDefinition.WebSearch(textOf(tool, "search_context_size"), locationMap));
                }
                default -> throw invalid("unsupported_hosted_tool: " + type + " (served: function, web_search)");
            }
        }
        return new ParsedTools(
                functionTools.isEmpty() ? null : List.copyOf(functionTools),
                hosted.isEmpty() ? null : List.copyOf(hosted));
    }

    /** Flat Responses {@code tool_choice} → the canonical nested chat shape. */
    private Object parseToolChoice(JsonNode toolChoice) throws OpenAiCodecException {
        if (toolChoice == null || toolChoice.isNull()) {
            return null;
        }
        if (toolChoice.isString()) {
            return toolChoice.stringValue(); // "auto" | "none" | "required" pass through
        }
        if (toolChoice.isObject()) {
            String type = textOf(toolChoice, "type");
            if ("function".equals(type)) {
                String name = textOf(toolChoice, "name");
                if (name == null || name.isBlank()) {
                    throw invalid("tool_choice of type function requires a name");
                }
                return ordered("type", "function", "function", ordered("name", name));
            }
            if ("web_search".equals(type)) {
                // Hosted tool choice — the tool is configured via tools[]; the
                // chat-completions vocabulary has no per-hosted-tool choice, so the
                // provider default (auto) applies.
                return "auto";
            }
            throw invalid("unsupported_hosted_tool in tool_choice: " + type + " (served: web_search)");
        }
        throw invalid("tool_choice must be a string or an object");
    }

    /** {@code text.format} → canonical responseFormat (the chat wire's shape). */
    private Map<String, Object> parseTextFormat(JsonNode text) {
        if (text == null || !text.isObject()) {
            return null;
        }
        JsonNode format = text.get("format");
        if (format == null || format.isNull() || !format.isObject()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        format.propertyNames().forEach(name -> result.put(name, plainValue(format.get(name))));
        return result.isEmpty() ? null : ResponseFormats.toChat(result);
    }

    /** {@code reasoning.effort} survives (summary drops — the echo-shape rule). */
    private Map<String, Object> parseReasoning(JsonNode reasoning) {
        if (reasoning == null || !reasoning.isObject()) {
            return null;
        }
        String effort = textOf(reasoning, "effort");
        return effort == null ? null : ordered("effort", effort);
    }

    private void captureExtras(JsonNode root, Map<String, Object> extras) {
        Set<String> known = Set.of(
                "model",
                "input",
                "instructions",
                "tools",
                "tool_choice",
                "temperature",
                "top_p",
                "max_output_tokens",
                "reasoning",
                "text",
                "stream",
                "store",
                "truncation",
                "background",
                "previous_response_id",
                "conversation",
                "include");
        root.propertyNames().forEach(name -> {
            if (!known.contains(name)) {
                extras.put(name, plainValue(root.get(name)));
            }
        });
    }

    // ----------------------------------------------------------------- response

    /**
     * Encode the Responses object. The request rides along for the echo fields
     * (instructions ← system, tools, tool_choice, sampling, text.format, reasoning) —
     * {@code ChatResponse} deliberately carries no request-side state.
     */
    public String encodeResponse(ChatRequest request, ChatResponse canonical) throws OpenAiCodecException {
        if (canonical == null) {
            throw new OpenAiCodecException(OpenAiCodecException.TYPE_API_ERROR, "response must not be null");
        }
        // Mirror the chat codec's validateResponse: a canonical with a blank id or null
        // choices is a typed api_error (the typed-error contract), never an NPE out of
        // the loop below or a wire id leak like "resp_null"/"msg_null"/"fc_null".
        if (!nonBlank(canonical.id())) {
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_API_ERROR, "response id is required and must be non-blank");
        }
        if (!nonBlank(canonical.model())) {
            throw new OpenAiCodecException(OpenAiCodecException.TYPE_API_ERROR, "response model must be non-blank");
        }
        if (canonical.choices() == null) {
            throw new OpenAiCodecException(OpenAiCodecException.TYPE_API_ERROR, "response choices must be non-null");
        }
        List<OpenAiResponsesOutputItem> output = new ArrayList<>();
        // Hosted searches the provider performed ride as web_search_call items
        // ahead of the message item (response-level output, like usage).
        if (canonical.hostedToolCalls() != null) {
            int searchIndex = 0;
            for (HostedToolCall hosted : canonical.hostedToolCalls()) {
                if (hosted instanceof HostedToolCall.WebSearchCall search) {
                    output.add(new OpenAiResponsesWebSearchCallItem(
                            "ws_" + searchIndex,
                            "web_search_call",
                            "completed",
                            new OpenAiResponsesWebSearchCallItem.Action("search", search.query())));
                    searchIndex++;
                }
            }
        }
        boolean textDelivered = false;
        for (ChatChoice choice : canonical.choices()) {
            if (!(choice.message() instanceof AssistantMessage assistant)) {
                continue;
            }
            // The item id must be unique per output item: an n>1 canonical response
            // yields several message items, and clients accumulate streamed/echoed
            // output items keyed by id — choice 0 keeps the plain spelling (the
            // single-choice contract, pinned byte-golden), later choices are suffixed.
            String messageId =
                    choice.index() == 0 ? "msg_" + canonical.id() : "msg_" + canonical.id() + "_" + choice.index();
            String text = assistant.content();
            if (text != null && !text.isBlank()) {
                output.add(new OpenAiResponsesMessageItem(
                        messageId,
                        "message",
                        "completed",
                        "assistant",
                        List.of(new OpenAiResponsesOutputText("output_text", text, List.of()))));
                textDelivered = true;
            }
            if (assistant.toolCalls() != null) {
                int callPosition = 0;
                for (ToolCall call : assistant.toolCalls()) {
                    // One canonical ToolCall.id feeds both the item id and call_id; a
                    // canonical tool call without an id (legal — stream fragments omit
                    // it) gets the deterministic stream-encoder spelling instead of a
                    // "fc_null" leak or an id-less item clients cannot key on.
                    String callId = call.id() != null ? call.id() : "call_" + callPosition;
                    output.add(new OpenAiResponsesFunctionCallItem(
                            "fc_" + callId,
                            "function_call",
                            "completed",
                            callId,
                            call.function() == null ? null : call.function().name(),
                            call.function() == null ? null : call.function().arguments()));
                    callPosition++;
                }
                textDelivered = true;
            }
        }
        if (!textDelivered) {
            output.add(new OpenAiResponsesMessageItem(
                    "msg_" + canonical.id(),
                    "message",
                    "completed",
                    "assistant",
                    List.of(new OpenAiResponsesOutputText("output_text", "", List.of()))));
        }
        String status;
        OpenAiResponsesResponse.IncompleteDetails incomplete = null;
        if (ChatResponse.STOP_REASON_LENGTH.equals(canonical.stopReason())) {
            status = "incomplete";
            incomplete = new OpenAiResponsesResponse.IncompleteDetails("max_output_tokens");
        } else if (ChatResponse.STOP_REASON_CONTENT_FILTER.equals(canonical.stopReason())) {
            status = "incomplete";
            incomplete = new OpenAiResponsesResponse.IncompleteDetails("content_filter");
        } else {
            status = "completed";
        }
        Map<String, Object> reasoning = new LinkedHashMap<>();
        Object effort = request == null || request.reasoning() == null
                ? null
                : request.reasoning().get("effort");
        reasoning.put("effort", effort);
        reasoning.put("summary", null);
        Map<String, Object> text = new LinkedHashMap<>();
        text.put(
                "format",
                request != null && request.responseFormat() != null
                        ? ResponseFormats.toResponses(request.responseFormat())
                        : ordered("type", "text"));
        OpenAiResponsesResponse dto = new OpenAiResponsesResponse(
                "resp_" + canonical.id(),
                "response",
                canonical.created(),
                status,
                null,
                incomplete,
                request == null ? null : request.system(),
                Map.of(),
                canonical.model(),
                output,
                null,
                request == null ? null : request.temperature(),
                request == null || request.toolChoice() == null ? "auto" : flatToolChoice(request.toolChoice()),
                flatToolsWithHosted(
                        request == null ? null : request.tools(), request == null ? null : request.hostedTools()),
                request == null ? null : request.topP(),
                request == null ? null : request.maxTokens(),
                null,
                reasoning,
                false,
                text,
                "disabled",
                usageOf(canonical.usage()));
        try {
            return mapper.writeValueAsString(dto);
        } catch (JacksonException e) {
            throw new OpenAiCodecException(
                    OpenAiCodecException.TYPE_API_ERROR, "failed to encode Responses payload: " + e.getMessage(), e);
        }
    }

    /** Canonical nested tool_choice → the flat Responses spelling (strings pass). */
    private static Object flatToolChoice(Object toolChoice) {
        if (toolChoice instanceof Map<?, ?> map
                && "function".equals(map.get("type"))
                && map.get("function") instanceof Map<?, ?> function) {
            Map<String, Object> flat = new LinkedHashMap<>();
            flat.put("type", "function");
            flat.put("name", function.get("name"));
            return flat;
        }
        return toolChoice;
    }

    /** Canonical ToolDefinitions → the flat Responses tools spelling. */
    private List<Map<String, Object>> flatTools(List<ToolDefinition> tools) {
        if (tools == null) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> flat = new LinkedHashMap<>();
            flat.put("type", "function");
            if (tool.name() != null) {
                flat.put("name", tool.name());
            }
            if (tool.description() != null) {
                flat.put("description", tool.description());
            }
            if (tool.inputSchema() != null) {
                flat.put("parameters", readParameters(tool.inputSchema()));
            }
            if (tool.strict() != null) {
                flat.put("strict", tool.strict());
            }
            result.add(flat);
        }
        return result;
    }

    /** The echo spelling for function + hosted tools (request echo and stream objects). */
    private List<Map<String, Object>> flatToolsWithHosted(
            List<ToolDefinition> tools, List<HostedToolDefinition> hosted) {
        List<Map<String, Object>> result = new ArrayList<>(flatTools(tools));
        if (hosted != null) {
            for (HostedToolDefinition tool : hosted) {
                if (tool instanceof HostedToolDefinition.WebSearch webSearch) {
                    Map<String, Object> flat = new LinkedHashMap<>();
                    flat.put("type", "web_search");
                    if (webSearch.searchContextSize() != null) {
                        flat.put("search_context_size", webSearch.searchContextSize());
                    }
                    if (webSearch.userLocation() != null) {
                        flat.put("user_location", webSearch.userLocation());
                    }
                    result.add(flat);
                }
            }
        }
        return result;
    }

    private Map<String, Object> readParameters(String schemaJson) {
        if (schemaJson == null) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(schemaJson);
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) plainValue(node);
            return value;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Canonical Usage → the Responses usage object (full input on the left). */
    private static OpenAiResponsesUsage usageOf(Usage usage) {
        if (usage == null) {
            return new OpenAiResponsesUsage(0, 0, 0, null, null);
        }
        long cached = usage.cacheReadInputTokens() == null ? 0L : usage.cacheReadInputTokens();
        // cached (and cache-creation) counted INSIDE input_tokens — the canonical
        // promptTokens is regular-only, so both cache sides merge back into the full
        // input (the same restore the chat encode's invariant performs).
        long written = usage.cacheCreationInputTokens() == null ? 0L : usage.cacheCreationInputTokens();
        long input = usage.promptTokens() + cached + written;
        OpenAiResponsesUsage.InputTokensDetails inputDetails =
                cached > 0 ? new OpenAiResponsesUsage.InputTokensDetails(cached) : null;
        OpenAiResponsesUsage.OutputTokensDetails outputDetails =
                usage.reasoningTokens() != null && usage.reasoningTokens() > 0
                        ? new OpenAiResponsesUsage.OutputTokensDetails(usage.reasoningTokens())
                        : null;
        return new OpenAiResponsesUsage(
                input, usage.completionTokens(), input + usage.completionTokens(), inputDetails, outputDetails);
    }

    /**
     * A per-stream encoder (one per SSE response — the encoder is stateful; never
     * shared). The request rides along for the echo fields on the created/completed/
     * failed response objects.
     */
    public OpenAiResponsesStreamEncoder newStreamEncoder(ChatRequest request) {
        return new StreamEncoderImpl(request, System::currentTimeMillis);
    }

    /**
     * The deterministic-clock form — the byte-golden fixture path (the created/completed/
     * failed response objects carry {@code created_at}; a fixed supplier keeps the
     * committed corpus reproducible; production uses the wall clock).
     */
    public OpenAiResponsesStreamEncoder newStreamEncoder(
            ChatRequest request, java.util.function.LongSupplier epochSecondSupplier) {
        return new StreamEncoderImpl(request, epochSecondSupplier);
    }

    /**
     * The immutable failure snapshot the publisher's error path reads (the
     * thread-safety discipline: the worker mutates encoder state inside feed/finish
     * and swaps this reference at item boundaries; the watchdog-spawned stall thread
     * reads it concurrently — never the mutable internals).
     */
    public interface OpenAiResponsesStreamEncoder {
        /** The created + in_progress prefix events (drained before the first feed). */
        List<OpenAiResponsesStreamEvent> initialEvents();

        /** One canonical chunk → zero-or-more wire events. */
        List<OpenAiResponsesStreamEvent> feed(StreamChunk chunk);

        /** Clean exhaustion → the terminal completed/incomplete event. */
        List<OpenAiResponsesStreamEvent> finish();

        /** The failure path → a single response.failed carrying the full object. */
        OpenAiResponsesStreamEvent failed(Throwable failure);
    }

    private final class StreamEncoderImpl implements OpenAiResponsesStreamEncoder {

        private final ChatRequest request;
        private final java.util.concurrent.atomic.AtomicLong sequence = new java.util.concurrent.atomic.AtomicLong(-1);

        // Worker-confined mutable state (swapped into the snapshot at item boundaries).
        // Streamed text accumulates in a StringBuilder — one materialization per change
        // at the finish/snapshot boundaries, never a full re-copy per delta (quadratic
        // copying/GC churn for long streamed responses otherwise).
        private final StringBuilder messageText = new StringBuilder();
        private String messageTextMaterialized = "";
        private boolean messageOpen = false;
        // The message item's output_index, allocated sequentially when the item first
        // opens (NOT hardcoded 0): an upstream that streams tool_calls fragments before
        // content deltas would otherwise emit response.output_item.added for the message
        // with a lower output_index after higher ones — non-monotonic indices strict
        // Responses SDKs reject. -1 = the message item has not opened yet.
        private int messageOutputIndex = -1;
        private final List<ToolCall> openToolFragments = new ArrayList<>();
        private final List<String> toolArguments = new ArrayList<>();
        // The function-call items' output indices, allocated in the same sequential
        // space as the message item's (open order), parallel to openToolFragments.
        private final List<Integer> toolOutputIndices = new ArrayList<>();
        private final List<Map<String, Object>> closedItems = new ArrayList<>();
        private Usage usage;
        private String finishReason;
        // The served model captured from the fed chunks — the completed/failed echo must
        // report what actually served the response (the canonical chunk model), matching
        // the non-streaming encodeResponse contract, falling back to the requested alias
        // until/unless a chunk carries one.
        private String servedModel;

        private final java.util.concurrent.atomic.AtomicReference<Object[]> snapshot =
                new java.util.concurrent.atomic.AtomicReference<>(new Object[] {List.of(), null, null});

        private final java.util.function.LongSupplier epochSeconds;

        StreamEncoderImpl(ChatRequest request, java.util.function.LongSupplier epochSeconds) {
            this.request = request;
            this.epochSeconds = epochSeconds;
        }

        @Override
        public List<OpenAiResponsesStreamEvent> initialEvents() {
            List<OpenAiResponsesStreamEvent> events = new ArrayList<>();
            events.add(
                    event("response.created", responseObject("in_progress", null, List.of(), null, null, servedModel)));
            events.add(event(
                    "response.in_progress", responseObject("in_progress", null, List.of(), null, null, servedModel)));
            return events;
        }

        @Override
        public List<OpenAiResponsesStreamEvent> feed(StreamChunk chunk) {
            List<OpenAiResponsesStreamEvent> events = new ArrayList<>();
            if (chunk.usage() != null) {
                usage = chunk.usage();
            }
            if (nonBlank(chunk.model())) {
                servedModel = chunk.model();
            }
            if (chunk.choices() != null) {
                for (ChunkChoice choice : chunk.choices()) {
                    Delta delta = choice.delta();
                    if (delta == null) {
                        continue;
                    }
                    if (choice.finishReason() != null) {
                        finishReason = choice.finishReason();
                    }
                    if (delta.content() != null && !delta.content().isEmpty()) {
                        if (!messageOpen) {
                            messageOpen = true;
                            messageOutputIndex = openToolFragments.size();
                            messageText.setLength(0);
                            messageTextMaterialized = "";
                            events.add(itemAddedEvent(messageItem(false), messageOutputIndex));
                            events.add(contentPartAddedEvent());
                        }
                        messageText.append(delta.content());
                        messageTextMaterialized = null; // invalidate; re-materialize lazily
                        events.add(textDeltaEvent(delta.content()));
                    }
                    if (delta.toolCalls() != null) {
                        for (ToolCall fragment : delta.toolCalls()) {
                            // The index rides the canonical verbatim from the upstream
                            // chunk, so a malformed/hostile OpenAI-compatible upstream
                            // sending a huge (or negative) tool_calls index must never
                            // allocate a slot per index value (OOM/CPU spin on a public
                            // ingress path). Non-contiguous jumps are treated as the
                            // NEXT SEQUENTIAL tool call — allocation is bounded by the
                            // fragment count, and well-formed contiguous streams (the
                            // OpenAI contract) are unaffected.
                            int index = fragment.index() == null ? 0 : fragment.index();
                            if (index < 0 || index > openToolFragments.size()) {
                                index = openToolFragments.size();
                            }
                            while (openToolFragments.size() <= index) {
                                int outputIndex = openToolFragments.size() + (messageOpen ? 1 : 0);
                                openToolFragments.add(null);
                                toolArguments.add("");
                                toolOutputIndices.add(outputIndex);
                                events.add(itemAddedEvent(functionCallItem(index, null, null, false), outputIndex));
                            }
                            ToolCall existing = openToolFragments.get(index);
                            String id = existing != null && existing.id() != null ? existing.id() : fragment.id();
                            String name = fragment.function() != null
                                            && fragment.function().name() != null
                                    ? fragment.function().name()
                                    : (existing != null && existing.function() != null
                                            ? existing.function().name()
                                            : null);
                            openToolFragments.set(
                                    index, new ToolCall(id, "function", new FunctionCall(name, null, null)));
                            if (fragment.function() != null
                                    && fragment.function().arguments() != null
                                    && !fragment.function().arguments().isEmpty()) {
                                toolArguments.set(
                                        index,
                                        toolArguments.get(index)
                                                + fragment.function().arguments());
                                events.add(argumentsDeltaEvent(
                                        index, fragment.function().arguments()));
                            }
                        }
                    }
                }
            }
            swapSnapshot();
            return events;
        }

        @Override
        public List<OpenAiResponsesStreamEvent> finish() {
            List<OpenAiResponsesStreamEvent> events = new ArrayList<>();
            List<Map<String, Object>> items = new ArrayList<>(closedItems);
            if (messageOpen) {
                String text = messageText();
                events.add(textDoneEvent(text));
                events.add(contentPartDoneEvent(text));
                events.add(itemDoneEvent(messageItem(true), messageOutputIndex));
                items.add(messageItemMap(text));
            }
            for (int i = 0; i < openToolFragments.size(); i++) {
                ToolCall call = openToolFragments.get(i);
                String callId = call != null && call.id() != null ? call.id() : "call_" + i;
                String name = call != null && call.function() != null
                        ? call.function().name()
                        : null;
                events.add(argumentsDoneEvent(i, toolArguments.get(i)));
                events.add(itemDoneEvent(functionCallItem(i, callId, name, true), toolOutputIndices.get(i)));
                items.add(functionCallItemMap(i, callId, name, toolArguments.get(i)));
            }
            String status = ChatResponse.STOP_REASON_LENGTH.equals(finishReason)
                            || ChatResponse.STOP_REASON_CONTENT_FILTER.equals(finishReason)
                    ? "incomplete"
                    : "completed";
            String incompleteReason = ChatResponse.STOP_REASON_LENGTH.equals(finishReason)
                    ? "max_output_tokens"
                    : ChatResponse.STOP_REASON_CONTENT_FILTER.equals(finishReason) ? "content_filter" : null;
            events.add(event(
                    "incomplete".equals(status) ? "response.incomplete" : "response.completed",
                    responseObject(status, incompleteReason, items, usage, null, servedModel)));
            return events;
        }

        @Override
        public OpenAiResponsesStreamEvent failed(Throwable failure) {
            Object[] snap = snapshot.get();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) snap[0];
            Usage usage = (Usage) snap[1];
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "server_error");
            error.put("message", String.valueOf(failure.getMessage()));
            Map<String, Object> response = responseObject("failed", null, items, usage, error, (String) snap[2]);
            return event("response.failed", response);
        }

        private void swapSnapshot() {
            List<Map<String, Object>> items = new ArrayList<>(closedItems);
            if (messageOpen) {
                items.add(messageItemMap(messageText()));
            }
            for (int i = 0; i < openToolFragments.size(); i++) {
                ToolCall call = openToolFragments.get(i);
                items.add(functionCallItemMap(
                        i,
                        call != null && call.id() != null ? call.id() : "call_" + i,
                        call != null && call.function() != null
                                ? call.function().name()
                                : null,
                        toolArguments.get(i)));
            }
            snapshot.set(new Object[] {List.copyOf(items), usage, servedModel});
        }

        // -------------------------------------------------- event builders

        private OpenAiResponsesStreamEvent event(String type, Map<String, Object> response) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("sequence_number", sequence.incrementAndGet());
            if (response != null) {
                payload.put("response", response);
            }
            try {
                return new OpenAiResponsesStreamEvent(type, mapper.writeValueAsString(payload));
            } catch (JacksonException e) {
                throw new OpenAiCodecException(
                        OpenAiCodecException.TYPE_API_ERROR,
                        "failed to encode Responses stream event: " + e.getMessage(),
                        e);
            }
        }

        private OpenAiResponsesStreamEvent itemAddedEvent(Map<String, Object> item, int outputIndex) {
            return indexedEvent("response.output_item.added", outputIndex, ordered("item", item));
        }

        private OpenAiResponsesStreamEvent itemDoneEvent(Map<String, Object> item, int outputIndex) {
            return indexedEvent("response.output_item.done", outputIndex, ordered("item", item));
        }

        private OpenAiResponsesStreamEvent contentPartAddedEvent() {
            return indexedEvent(
                    "response.content_part.added",
                    messageOutputIndex,
                    ordered(
                            "item_id",
                            messageId(),
                            "content_index",
                            0,
                            "part",
                            ordered("type", "output_text", "text", "", "annotations", List.of())));
        }

        private OpenAiResponsesStreamEvent contentPartDoneEvent(String text) {
            return indexedEvent(
                    "response.content_part.done",
                    messageOutputIndex,
                    ordered(
                            "item_id",
                            messageId(),
                            "content_index",
                            0,
                            "part",
                            ordered("type", "output_text", "text", text, "annotations", List.of())));
        }

        private OpenAiResponsesStreamEvent textDeltaEvent(String delta) {
            return indexedEvent(
                    "response.output_text.delta",
                    messageOutputIndex,
                    ordered("item_id", messageId(), "content_index", 0, "delta", delta));
        }

        private OpenAiResponsesStreamEvent textDoneEvent(String text) {
            return indexedEvent(
                    "response.output_text.done",
                    messageOutputIndex,
                    ordered("item_id", messageId(), "content_index", 0, "text", text));
        }

        private OpenAiResponsesStreamEvent argumentsDeltaEvent(int toolIndex, String delta) {
            return indexedEvent(
                    "response.function_call_arguments.delta",
                    toolOutputIndices.get(toolIndex),
                    ordered("item_id", functionId(toolIndex), "delta", delta));
        }

        private OpenAiResponsesStreamEvent argumentsDoneEvent(int toolIndex, String arguments) {
            return indexedEvent(
                    "response.function_call_arguments.done",
                    toolOutputIndices.get(toolIndex),
                    ordered("item_id", functionId(toolIndex), "arguments", arguments));
        }

        private OpenAiResponsesStreamEvent indexedEvent(String type, int outputIndex, Map<String, Object> fields) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            payload.put("sequence_number", sequence.incrementAndGet());
            payload.put("output_index", outputIndex);
            payload.putAll(fields);
            try {
                return new OpenAiResponsesStreamEvent(type, mapper.writeValueAsString(payload));
            } catch (JacksonException e) {
                throw new OpenAiCodecException(
                        OpenAiCodecException.TYPE_API_ERROR,
                        "failed to encode Responses stream event: " + e.getMessage(),
                        e);
            }
        }

        private String messageId() {
            return "msg_stream";
        }

        private String functionId(int index) {
            return "fc_" + index;
        }

        /**
         * The accumulated message text, materialized at most once per change — the
         * snapshot/finish boundaries hand out an immutable String (concurrent failed
         * reads), the per-delta path never copies.
         */
        private String messageText() {
            if (messageTextMaterialized == null) {
                messageTextMaterialized = messageText.toString();
            }
            return messageTextMaterialized;
        }

        private Map<String, Object> messageItem(boolean completed) {
            return messageItemMap(completed ? messageText() : "");
        }

        private Map<String, Object> messageItemMap(String text) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", messageId());
            item.put("type", "message");
            item.put("status", "completed");
            item.put("role", "assistant");
            item.put(
                    "content",
                    List.of(ordered(
                            "type", "output_text", "text", text == null ? "" : text, "annotations", List.of())));
            return item;
        }

        private Map<String, Object> functionCallItem(int index, String callId, String name, boolean completed) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", functionId(index));
            item.put("type", "function_call");
            item.put("status", completed ? "completed" : "in_progress");
            item.put("call_id", callId != null ? callId : "call_" + index);
            item.put("name", name);
            item.put("arguments", completed ? toolArguments.get(index) : "");
            return item;
        }

        private Map<String, Object> functionCallItemMap(int index, String callId, String name, String arguments) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", functionId(index));
            item.put("type", "function_call");
            item.put("status", "completed");
            item.put("call_id", callId);
            item.put("name", name);
            item.put("arguments", arguments);
            return item;
        }

        /**
         * The full response object riding created/completed/failed — the shared echo.
         * The {@code model} parameter is the served model captured from the fed chunks
         * (null until one arrives — created/in_progress then echo the requested alias),
         * so the terminal completed/failed object reports what actually served the
         * response, matching the non-streaming {@code encodeResponse} contract.
         */
        private Map<String, Object> responseObject(
                String status,
                String incompleteReason,
                List<Map<String, Object>> items,
                Usage usage,
                Map<String, Object> error,
                String model) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", "resp_stream");
            response.put("object", "response");
            response.put("created_at", epochSeconds.getAsLong() / 1000);
            response.put("status", status);
            response.put("error", error);
            if (incompleteReason != null) {
                response.put("incomplete_details", ordered("reason", incompleteReason));
            }
            response.put("instructions", request == null ? null : request.system());
            response.put("metadata", Map.of());
            response.put("model", model != null ? model : (request == null ? null : request.model()));
            response.put("output", items);
            response.put("temperature", request == null ? null : request.temperature());
            response.put(
                    "tool_choice",
                    request == null || request.toolChoice() == null ? "auto" : flatToolChoice(request.toolChoice()));
            response.put(
                    "tools",
                    flatToolsWithHosted(
                            request == null ? null : request.tools(), request == null ? null : request.hostedTools()));
            response.put("top_p", request == null ? null : request.topP());
            response.put("previous_response_id", null);
            Map<String, Object> reasoning = new LinkedHashMap<>();
            reasoning.put(
                    "effort",
                    request == null || request.reasoning() == null
                            ? null
                            : request.reasoning().get("effort"));
            reasoning.put("summary", null);
            response.put("reasoning", reasoning);
            response.put("store", false);
            response.put(
                    "text",
                    ordered(
                            "format",
                            request != null && request.responseFormat() != null
                                    ? ResponseFormats.toResponses(request.responseFormat())
                                    : ordered("type", "text")));
            response.put("truncation", "disabled");
            response.put("usage", usageOf(usage));
            return response;
        }
    }

    // ------------------------------------------------------------------- helpers

    /**
     * Insertion-ordered single-use map builder — every wire-visible map in this codec
     * goes through this, NEVER {@code Map.of}: JDK 25's immutable maps iterate in a
     * per-JVM randomized order, which would make emitted JSON field order vary across
     * restarts and break the byte-golden fixture contract (found by the ro/ra matrix
     * legs; probed: identical Map.of constructions order differently across JVM runs).
     */
    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private static OpenAiCodecException invalid(String message) {
        return new OpenAiCodecException(OpenAiCodecException.TYPE_INVALID_REQUEST, message);
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private JsonNode readTree(String json) throws OpenAiCodecException {
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            throw invalid("invalid JSON: " + e.getMessage());
        }
    }

    private String writeRawJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private static String textOf(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? null : node.stringValue();
    }

    private static Boolean booleanOf(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? null : node.asBoolean();
    }

    /** A Boolean object (never primitive-coerced) for the nullable strict slot. */
    private static Boolean booleanObjectOf(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isBoolean() ? node.asBoolean() : null;
    }

    private static Double doubleOf(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? null : node.asDouble();
    }

    private static Integer intOf(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        return node == null || node.isNull() ? null : node.asInt();
    }

    private Object plainValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return mapper.convertValue(node, Object.class);
    }
}
