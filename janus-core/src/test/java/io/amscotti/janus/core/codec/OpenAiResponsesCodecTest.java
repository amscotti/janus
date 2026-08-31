package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.HostedToolCall;
import io.amscotti.janus.core.model.HostedToolDefinition;
import io.amscotti.janus.core.model.ImageUrlContent;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.ToolDefinition;
import io.amscotti.janus.core.model.ToolMessage;
import io.amscotti.janus.core.model.Usage;
import io.amscotti.janus.core.model.UserMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The {@link OpenAiResponsesCodec} mapping-table pins: one
 * one test per contract row, the decode contracts (consecutive-merge, orphan, flat→nested
 * tool_choice, echo-item drops), the typed 400 rows with fixed messages, the status
 * mapping, id synthesis, and the exact-JSON response wire shape. The request tables
 * here are the codec's contract; the golden-matrix ro/ra legs capture the same shapes
 * against real upstreams (env-gated, out of CI).
 */
class OpenAiResponsesCodecTest {

    private final OpenAiResponsesCodec codec = OpenAiResponsesCodec.create();

    private static OpenAiCodecException decodeFails(String json) {
        return assertThrows(
                OpenAiCodecException.class,
                () -> OpenAiResponsesCodec.create().decodeRequest(json),
                "expected a typed 400: " + json);
    }

    // ------------------------------------------------------------ the 400 rows

    @Test
    void explicitStoreTrueIs400() {
        OpenAiCodecException e = decodeFails("""
                {"model":"m","input":"hi","store":true}
                """);
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("store: true is not supported"), e.getMessage());
    }

    @Test
    void absentStoreIsStatelessFalseNeverA400() {
        // Decision E: the server default is true, but the official SDK omits the field —
        // absent ≡ false must be accepted.
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":"hi"}
                """);
        assertFalse(request.stream());
        assertNull(request.extras().get("store"), "store never rides extras");
    }

    @Test
    void explicitStoreFalseIsAccepted() {
        assertNotNull(codec.decodeRequest("""
                {"model":"m","input":"hi","store":false}
                """));
    }

    @Test
    void statefulAndBackgroundFieldsAre400WithFixedMessages() {
        assertTrue(decodeFails("{\"model\":\"m\",\"input\":\"hi\",\"previous_response_id\":\"resp_1\"}")
                .getMessage()
                .contains("previous_response_id is not supported"));
        assertTrue(decodeFails("{\"model\":\"m\",\"input\":\"hi\",\"conversation\":\"c1\"}")
                .getMessage()
                .contains("conversation is not supported"));
        assertTrue(decodeFails("{\"model\":\"m\",\"input\":\"hi\",\"background\":true}")
                .getMessage()
                .contains("background: true is not supported"));
        assertTrue(decodeFails("{\"model\":\"m\",\"input\":\"hi\",\"truncation\":\"auto\"}")
                .getMessage()
                .contains("truncation"));
    }

    @Test
    void hostedToolsAre400NamingTheType() {
        // web_search is SERVED; the other hosted families stay named 400s.
        for (String type : new String[] {"code_interpreter", "computer_use", "mcp"}) {
            OpenAiCodecException e =
                    decodeFails("{\"model\":\"m\",\"input\":\"hi\",\"tools\":[{\"type\":\"" + type + "\"}]}");
            assertTrue(e.getMessage().contains("unsupported_hosted_tool: " + type), e.getMessage());
        }
    }

    @Test
    void webSearchToolDecodesToTheCanonicalHostedSlot() {
        // web_search (+ the legacy _preview alias) → the sealed canonical hosted
        // slot with its knobs; function tools still land in ToolDefinition.
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":"hi","tools":[
                    {"type":"function","name":"f","parameters":{"type":"object"}},
                    {"type":"web_search","search_context_size":"medium",
                     "user_location":{"type":"approximate","city":"Berlin"}},
                    {"type":"web_search_preview"}]}
                """);
        assertEquals(
                List.of(new ToolDefinition("function", "f", null, "{\"type\":\"object\"}", null)), request.tools());
        assertEquals(
                List.of(
                        new HostedToolDefinition.WebSearch("medium", Map.of("type", "approximate", "city", "Berlin")),
                        new HostedToolDefinition.WebSearch(null, null)),
                request.hostedTools());
    }

    @Test
    void hostedWebSearchToolChoiceIsAcceptedAsAuto() {
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":"hi","tools":[{"type":"web_search"}],"tool_choice":{"type":"web_search"}}
                """);
        assertEquals("auto", request.toolChoice());
    }

    @Test
    void additionalToolsInputItemsAreDroppedLikeEchoItems() {
        // Codex CLI sends type=additional_tools (custom exec-isolate tools). Janus
        // does not host that VM — drop the item and keep the user message.
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":[
                  {"type":"additional_tools","role":"developer","tools":[{"type":"custom","name":"exec"}]},
                  {"type":"message","role":"user","content":"pong"}
                ]}
                """);
        assertEquals(1, request.messages().size());
        assertEquals("pong", ((UserMessage) request.messages().get(0)).content());
    }

    @Test
    void namespaceToolsAreSkipped() {
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":"hi","tools":[
                  {"type":"namespace","name":"default"},
                  {"type":"function","name":"echo","parameters":{"type":"object"}}
                ]}
                """);
        assertEquals(1, request.tools().size());
        assertEquals("echo", request.tools().get(0).name());
    }

    @Test
    void hostedSearchCallsRideTheResponseAsWebSearchCallItems() {
        // Canonical hosted outputs (e.g. mapped from Anthropic server_tool_use)
        // emit as typed web_search_call items ahead of the message item.
        ChatResponse withSearch = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, new AssistantMessage("Berlin weather", null), "stop")),
                new Usage(10, 5, 15),
                "stop",
                List.of(new HostedToolCall.WebSearchCall("berlin weather")),
                Map.of(),
                null);
        String json = codec.encodeResponse(echoRequest(), withSearch);
        assertTrue(json.contains("\"type\":\"web_search_call\""), json);
        assertTrue(json.contains("\"action\":{\"type\":\"search\",\"query\":\"berlin weather\"}"), json);
        int wsIndex = json.indexOf("web_search_call");
        int msgIndex = json.indexOf("\"type\":\"message\"");
        assertTrue(wsIndex >= 0 && msgIndex > wsIndex, "search items precede the message item: " + json);
    }

    @Test
    void unsupportedHostedToolChoiceIsStill400() {
        // web_search tool_choice is accepted (as auto); other hosted spellings 400.
        OpenAiCodecException e = decodeFails("""
                {"model":"m","input":"hi","tool_choice":{"type":"code_interpreter"}}
                """);
        assertTrue(e.getMessage().contains("unsupported_hosted_tool in tool_choice"), e.getMessage());
    }

    @Test
    void nonObjectToolParametersIsATyped400() {
        // An array/primitive `parameters` used to ride the decode
        // as a raw string and surface later (dropped from the response echo, or a
        // 500-ish failure on the chat egress). It is client-malformed input — reject
        // at the ingress with the face's own 400 naming the tool.
        OpenAiCodecException e =
                decodeFails("{\"model\":\"m\",\"input\":\"hi\",\"tools\":[{\"type\":\"function\",\"name\":\"f\","
                        + "\"parameters\":[\"not\",\"an\",\"object\"]}]}");
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("parameters"), e.getMessage());
        assertTrue(e.getMessage().contains("f"), "names the tool: " + e.getMessage());
        // absent parameters stay legal (the provider default applies)
        assertNotNull(codec.decodeRequest(
                "{\"model\":\"m\",\"input\":\"hi\",\"tools\":[{\"type\":\"function\",\"name\":\"f\"}]}"));
    }

    @Test
    void computerCallOutputItemIs400UnsupportedItem() {
        OpenAiCodecException e = decodeFails("""
                {"model":"m","input":[{"type":"computer_call_output","call_id":"c","output":{}}]}
                """);
        assertTrue(e.getMessage().contains("unsupported_item: computer_call_output"), e.getMessage());
    }

    @Test
    void orphanFunctionCallOutputIs400NamingTheCallId() {
        OpenAiCodecException e = decodeFails("""
                {"model":"m","input":[{"type":"function_call_output","call_id":"call_x","output":"r"}]}
                """);
        assertTrue(e.getMessage().contains("orphan_tool_output: call_x"), e.getMessage());
    }

    @Test
    void inputOfOnlyEchoItemsIs400() {
        // Never a chat-shaped validateRequest error leaking through — the Responses
        // face's own message.
        OpenAiCodecException e = decodeFails("""
                {"model":"m","input":[{"type":"reasoning","summary":[]},{"type":"web_search_call"}]}
                """);
        assertTrue(e.getMessage().contains("at least one translatable item"), e.getMessage());
    }

    // ------------------------------------------------------- the decode tables

    @Test
    void stringInputBecomesOneUserMessageAndInstructionsBecomeSystem() {
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":"hello","instructions":"be brief"}
                """);
        assertEquals("m", request.model());
        assertEquals("be brief", request.system());
        assertEquals(List.of(new UserMessage("hello")), request.messages());
        assertFalse(request.stream());
    }

    @Test
    void messageItemsMapByRoleAndJoinTextParts() {
        ChatRequest request = codec.decodeRequest("""
                {
                  "model":"m",
                  "input":[
                    {"type":"message","role":"system","content":"sys"},
                    {"type":"message","role":"user","content":[
                       {"type":"input_text","text":"hello "},
                       {"type":"input_text","text":"world"}]},
                    {"type":"message","role":"assistant","content":[{"type":"output_text","text":"hi"}]}
                  ]
                }
                """);
        assertEquals(3, request.messages().size());
        assertEquals("hello \nworld", ((UserMessage) request.messages().get(1)).content());
        assertEquals("hi", ((AssistantMessage) request.messages().get(2)).content());
    }

    @Test
    void nullTextPartsNeverInjectLiteralNull() {
        // A missing or JSON-null text member must not write the literal string "null"
        // into the joined message content or the tool output.
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":[
                    {"type":"message","role":"user","content":[
                       {"type":"input_text","text":"hello"},
                       {"type":"input_text","text":null},
                       {"type":"input_text"}]},
                    {"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"},
                    {"type":"function_call_output","call_id":"c1","output":[
                       {"type":"output_text","text":"sunny"},
                       {"type":"output_text","text":null}]}
                ]}
                """);
        assertEquals("hello", ((UserMessage) request.messages().get(0)).content());
        assertEquals("sunny", ((ToolMessage) request.messages().get(2)).content());
    }

    @Test
    void inputImageWithoutNonBlankUrlIsRejected() {
        // Mirrors the Anthropic path's non-blank url check: an image part with a
        // missing/blank image_url must not become a canonical part with url:null.
        OpenAiCodecException e = decodeFails("""
                {"model":"m","input":[{"type":"message","role":"user","content":[
                    {"type":"input_image","image_url":"   "}]}]}
                """);
        assertEquals(OpenAiCodecException.TYPE_INVALID_REQUEST, e.type());
        assertTrue(e.getMessage().contains("image_url"), e.getMessage());
    }

    @Test
    void inputImagePartsBecomeCanonicalVisionParts() {
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":[{"type":"message","role":"user","content":[
                    {"type":"input_text","text":"look"},
                    {"type":"input_image","image_url":"https://x/img.png"}]}]}
                """);
        UserMessage user = (UserMessage) request.messages().getFirst();
        assertNotNull(user.parts());
        assertEquals(2, user.parts().size());
        assertTrue(user.parts().get(1) instanceof ImageUrlContent image
                && image.url().equals("https://x/img.png"));
    }

    @Test
    void consecutiveFunctionCallsMergeIntoOneAssistantMessage() {
        // Adjacency-based merge — an intervening non-function_call
        // item closes the group (verified by the two-cycle replay below).
        ChatRequest request = codec.decodeRequest("""
                {
                  "model":"m",
                  "input":[
                    {"type":"message","role":"user","content":"weather?"},
                    {"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"},
                    {"type":"function_call","call_id":"c2","name":"get_time","arguments":"{}"},
                    {"type":"function_call_output","call_id":"c1","output":"sunny"},
                    {"type":"function_call","call_id":"c3","name":"again","arguments":"{}"},
                    {"type":"function_call_output","call_id":"c3","output":"done"}
                  ]
                }
                """);
        assertEquals(
                List.of(
                        UserMessage.class,
                        AssistantMessage.class,
                        ToolMessage.class,
                        AssistantMessage.class,
                        ToolMessage.class),
                request.messages().stream().map(Object::getClass).toList());
        AssistantMessage first = (AssistantMessage) request.messages().get(1);
        assertEquals(2, first.toolCalls().size());
        assertEquals("c1", first.toolCalls().getFirst().id());
        AssistantMessage second = (AssistantMessage) request.messages().get(3);
        assertEquals(1, second.toolCalls().size());
    }

    @Test
    void functionCallOutputPartArraysJoinTextAndDropImages() {
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":[
                    {"type":"function_call","call_id":"c1","name":"f","arguments":"{}"},
                    {"type":"function_call_output","call_id":"c1","output":[
                       {"type":"input_text","text":"a"},
                       {"type":"input_image","image_url":"data:image/png;base64,AAA"},
                       {"type":"output_text","text":"b"}]}]}
                """);
        assertEquals(2, request.messages().size());
        ToolMessage tool = (ToolMessage) request.messages().get(1);
        assertEquals("a\nb", tool.content());
    }

    @Test
    void echoItemsAreDroppedAndSummaryIsDroppedFromReasoning() {
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":[
                    {"type":"message","role":"user","content":"hi"},
                    {"type":"reasoning","summary":[{"type":"summary_text","text":"thought"}]}],
                 "reasoning":{"effort":"high","summary":"detailed"}}
                """);
        assertEquals(1, request.messages().size());
        assertEquals(Map.of("effort", "high"), request.reasoning());
        assertNull(request.extras().get("reasoning"));
    }

    @Test
    void flatToolChoiceNestsAndStringsPass() {
        ChatRequest named = codec.decodeRequest("""
                {"model":"m","input":"hi","tool_choice":{"type":"function","name":"f"}}
                """);
        assertEquals(Map.of("type", "function", "function", Map.of("name", "f")), named.toolChoice());
        ChatRequest auto = codec.decodeRequest("""
                {"model":"m","input":"hi","tool_choice":"auto"}
                """);
        assertEquals("auto", auto.toolChoice());
    }

    @Test
    void functionToolsMapWithStrictAndTextFormatMapsToResponseFormat() {
        ChatRequest request = codec.decodeRequest("""
                {
                  "model":"m","input":"hi",
                  "tools":[{"type":"function","name":"f","description":"d",
                            "parameters":{"type":"object"},"strict":true}],
                  "text":{"format":{"type":"json_schema","name":"out","schema":{"type":"object"}}}
                }
                """);
        assertEquals(
                List.of(new ToolDefinition("function", "f", "d", "{\"type\":\"object\"}", Boolean.TRUE)),
                request.tools());
        assertEquals(
                Map.of("type", "json_schema", "json_schema", Map.of("name", "out", "schema", Map.of("type", "object"))),
                request.responseFormat());
        String chat = OpenAiMessageCodec.create().encodeRequest(request);
        assertTrue(chat.contains("\"json_schema\""), chat);
        assertTrue(chat.contains("\"name\":\"out\""), chat);
    }

    @Test
    void samplingAndMaxOutputTokensAndStreamMap() {
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":"hi","temperature":0.5,"top_p":0.9,"max_output_tokens":256,"stream":true}
                """);
        assertEquals(0.5, request.temperature());
        assertEquals(0.9, request.topP());
        assertEquals(256, request.maxTokens());
        assertTrue(request.stream());
    }

    @Test
    void openAiOnlyFieldsRideExtrasAndStatelessOnesDoNot() {
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":"hi","user":"u1","service_tier":"auto",
                 "parallel_tool_calls":false,"include":["reasoning.encrypted_content"],
                 "truncation":"disabled","metadata":{"a":"b"},"custom":"x"}
                """);
        assertEquals("u1", request.extras().get("user"));
        assertEquals("auto", request.extras().get("service_tier"));
        assertEquals(Boolean.FALSE, request.extras().get("parallel_tool_calls"));
        assertEquals("x", request.extras().get("custom"));
        assertFalse(request.extras().containsKey("include"), "include accepted-and-dropped");
        assertFalse(request.extras().containsKey("truncation"), "truncation disabled accepted-and-dropped");
        assertFalse(request.extras().containsKey("store"));
    }

    // --------------------------------------------------- the cross-format (ra) leg

    @Test
    void responsesDecodedRequestEncodesLegallyToAnthropic() {
        // The whole point of the canonical bridge — a Responses-face request routed
        // to an Anthropic-format upstream produces a legal Anthropic request: effort
        // shaped into thinking (budget table), tool definitions flat->input_schema,
        // consecutive function_call items pre-merged (the decode contract keeps the
        // ra leg Anthropic-legal by construction).
        ChatRequest decoded = codec.decodeRequest("""
                {"model":"m","input":[
                    {"type":"message","role":"user","content":"weather?"},
                    {"type":"function_call","call_id":"c1","name":"get_weather","arguments":"{}"},
                    {"type":"function_call","call_id":"c2","name":"get_time","arguments":"{}"}],
                 "instructions":"be brief",
                 "tools":[{"type":"function","name":"get_weather","parameters":{"type":"object"}}],
                 "reasoning":{"effort":"high"}}
                """);
        String anthropicJson =
                new AnthropicMessageCodec(io.amscotti.janus.core.codec.JsonSupport.mapper()).encodeRequest(decoded);
        assertTrue(anthropicJson.contains("\"system\":\"be brief\""), anthropicJson);
        assertTrue(anthropicJson.contains("\"thinking\":{\"type\":\"adaptive\"}"), anthropicJson);
        assertTrue(anthropicJson.contains("\"output_config\":{\"effort\":\"high\"}"), anthropicJson);
        assertTrue(anthropicJson.contains("\"input_schema\":{\"type\":\"object\"}"), anthropicJson);
        // The merged tool calls ride ONE assistant message (no back-to-back assistant
        // tool_use blocks — Anthropic rejects those).
        int assistantCount = anthropicJson.split("\"role\":\"assistant\"", -1).length - 1;
        org.junit.jupiter.api.Assertions.assertEquals(1, assistantCount, anthropicJson);
    }

    // ------------------------------------------------------ the response table

    @Test
    void encodeResponseValidatesIdModelAndChoicesLikeTheChatCodec() {
        // Mirror of the chat codec's validateResponse: a canonical with a blank id or
        // null choices is a typed api_error — never an NPE out of the choices loop or
        // a wire id leak like "resp_null"/"msg_null" (the chat decode does not require
        // an id, so such canonicals are reachable).
        ChatResponse blankId = new ChatResponse(
                null,
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, new AssistantMessage("x", null), "stop")),
                new Usage(1, 1, 2),
                "stop",
                Map.of(),
                null);
        OpenAiCodecException idError =
                assertThrows(OpenAiCodecException.class, () -> codec.encodeResponse(null, blankId));
        assertEquals(OpenAiCodecException.TYPE_API_ERROR, idError.type());
        assertTrue(idError.getMessage().contains("response id"), idError.getMessage());

        ChatResponse nullChoices = new ChatResponse(
                "chatcmpl-1", "chat.completion", 1L, "m", null, new Usage(1, 1, 2), "stop", Map.of(), null);
        OpenAiCodecException choicesError =
                assertThrows(OpenAiCodecException.class, () -> codec.encodeResponse(null, nullChoices));
        assertEquals(OpenAiCodecException.TYPE_API_ERROR, choicesError.type());
        assertTrue(choicesError.getMessage().contains("choices"), choicesError.getMessage());
    }

    @Test
    void encodeResponseSynthesizesACallIdForIdlessToolCalls() {
        // A canonical tool call without an id (legal — stream fragments omit it) must
        // not leak "fc_null" or an id-less item; the deterministic stream-encoder
        // spelling ("call_<position>") feeds both the item id and call_id.
        AssistantMessage assistant = new AssistantMessage(
                null,
                List.of(new ToolCall(
                        null, "function", new io.amscotti.janus.core.model.FunctionCall("get_weather", "{}", null))));
        String json = codec.encodeResponse(
                echoRequest(), response(ChatResponse.STOP_REASON_TOOL_CALLS, new Usage(10, 0, 10), assistant));
        assertTrue(json.contains("\"id\":\"fc_call_0\""), json);
        assertTrue(json.contains("\"call_id\":\"call_0\""), json);
        assertFalse(json.contains("fc_null"), json);
    }

    private static ChatRequest echoRequest() {
        return new ChatRequest(
                "m",
                List.of(new UserMessage("hello")),
                "be brief",
                List.of(new ToolDefinition("function", "f", "d", "{\"type\":\"object\"}", Boolean.TRUE)),
                Map.of("type", "function", "function", Map.of("name", "f")),
                0.5,
                0.9,
                null,
                256,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                Map.of("effort", "high"),
                null,
                Map.of(),
                null);
    }

    private static ChatResponse response(String stopReason, Usage usage, AssistantMessage assistant) {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1L,
                "m",
                List.of(new ChatChoice(0, assistant, stopReason)),
                usage,
                stopReason,
                Map.of(),
                null);
    }

    @Test
    void encodeResponseWireShapeTextTurn() {
        String json = codec.encodeResponse(
                echoRequest(),
                response(ChatResponse.STOP_REASON_STOP, new Usage(10, 5, 15), new AssistantMessage("Hello", null)));
        assertEquals(
                "{\"id\":\"resp_chatcmpl-1\",\"object\":\"response\",\"created_at\":1,\"status\":\"completed\","
                        + "\"error\":null,\"instructions\":\"be brief\",\"metadata\":{},\"model\":\"m\","
                        + "\"output\":[{\"id\":\"msg_chatcmpl-1\",\"type\":\"message\",\"status\":\"completed\","
                        + "\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello\",\"annotations\":[]}]}],"
                        + "\"parallel_tool_calls\":null,"
                        + "\"temperature\":0.5,\"tool_choice\":{\"type\":\"function\",\"name\":\"f\"},"
                        + "\"tools\":[{\"type\":\"function\",\"name\":\"f\",\"description\":\"d\","
                        + "\"parameters\":{\"type\":\"object\"},\"strict\":true}],\"top_p\":0.9,"
                        + "\"max_output_tokens\":256,\"previous_response_id\":null,"
                        + "\"reasoning\":{\"effort\":\"high\",\"summary\":null},\"store\":false,"
                        + "\"text\":{\"format\":{\"type\":\"text\"}},\"truncation\":\"disabled\","
                        + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}",
                json);
    }

    @Test
    void encodeResponseMakesMessageItemIdsUniquePerChoice() {
        // An n>1 canonical response yields one message item per choice, and clients
        // accumulate output items keyed by id — every item id must be unique: choice 0
        // keeps the plain "msg_<response id>" spelling (the single-choice contract,
        // pinned byte-golden), later choices are suffixed with their choice index.
        ChatResponse twoChoices = new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1L,
                "m",
                List.of(
                        new ChatChoice(0, new AssistantMessage("Hello", null), ChatResponse.STOP_REASON_STOP),
                        new ChatChoice(1, new AssistantMessage("Bonjour", null), ChatResponse.STOP_REASON_STOP)),
                new Usage(10, 5, 15),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                null);
        String json = codec.encodeResponse(echoRequest(), twoChoices);
        List<String> ids = new ArrayList<>();
        JsonNode output = JsonSupport.mapper().readTree(json).get("output");
        output.forEach(item -> ids.add(item.get("id").asString()));
        assertEquals(List.of("msg_chatcmpl-1", "msg_chatcmpl-1_1"), ids, json);
    }

    @Test
    void encodeResponseToolTurnUsesCallIdForBothIds() {
        AssistantMessage assistant = new AssistantMessage(
                null,
                List.of(new ToolCall(
                        "call_1",
                        "function",
                        new io.amscotti.janus.core.model.FunctionCall("get_weather", "{}", null))));
        String json = codec.encodeResponse(
                echoRequest(), response(ChatResponse.STOP_REASON_TOOL_CALLS, new Usage(10, 0, 10), assistant));
        assertTrue(json.contains("\"type\":\"function_call\""), json);
        assertTrue(json.contains("\"id\":\"fc_call_1\""), json);
        assertTrue(json.contains("\"call_id\":\"call_1\""), json);
        assertTrue(json.contains("\"name\":\"get_weather\""), json);
        assertFalse(json.contains("\"output_text\":\"\""), "no empty text item beside tool calls: " + json);
    }

    @Test
    void lengthMapsToIncompleteWithDetailsAndContentFilterToo() {
        String length = codec.encodeResponse(
                echoRequest(),
                response(ChatResponse.STOP_REASON_LENGTH, new Usage(10, 5, 15), new AssistantMessage("He", null)));
        assertTrue(length.contains("\"status\":\"incomplete\""), length);
        assertTrue(length.contains("\"incomplete_details\":{\"reason\":\"max_output_tokens\"}"), length);
        String filtered = codec.encodeResponse(
                echoRequest(),
                response(
                        ChatResponse.STOP_REASON_CONTENT_FILTER,
                        new Usage(10, 5, 15),
                        new AssistantMessage("He", null)));
        assertTrue(filtered.contains("\"incomplete_details\":{\"reason\":\"content_filter\"}"), filtered);
    }

    @Test
    void usageMergesCacheIntoInputTokensWithDetailsSplit() {
        // Canonical promptTokens is REGULAR input (cache split out); the Responses wire
        // counts cached INSIDE input_tokens with the details split — the inverse of the
        // chat codec's cache-split decode.
        String json = codec.encodeResponse(
                echoRequest(),
                response(
                        ChatResponse.STOP_REASON_STOP,
                        new Usage(7, 5, 15, null, 3L, 4L),
                        new AssistantMessage("x", null)));
        assertTrue(
                json.contains(
                        "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15,"
                                + "\"input_tokens_details\":{\"cached_tokens\":3},\"output_tokens_details\":{\"reasoning_tokens\":4}}"),
                json);
    }

    @Test
    void usageRestoresCacheCreationIntoInputTokensToo() {
        // An Anthropic-sourced canonical carries cache-creation alongside cache-read
        // (both additive to the regular input on that wire); the Responses face counts
        // the FULL input inside input_tokens — both cache sides merge back, never just
        // the read side (7 regular + 2 written + 3 cached = 12 input).
        String json = codec.encodeResponse(
                echoRequest(),
                response(ChatResponse.STOP_REASON_STOP, new Usage(7, 5, 17, 2L, 3L), new AssistantMessage("x", null)));
        assertTrue(
                json.contains("\"usage\":{\"input_tokens\":12,\"output_tokens\":5,\"total_tokens\":17,"
                        + "\"input_tokens_details\":{\"cached_tokens\":3}}"),
                json);
    }

    @Test
    void textFormatEchoesFromTheRequestWhenSet() {
        ChatRequest request = codec.decodeRequest("""
                {"model":"m","input":"hi","text":{"format":{"type":"json_object"}}}
                """);
        String json = codec.encodeResponse(
                request, response(ChatResponse.STOP_REASON_STOP, new Usage(1, 1, 2), new AssistantMessage("{}", null)));
        assertTrue(json.contains("\"text\":{\"format\":{\"type\":\"json_object\"}}"), json);
    }

    @Test
    void blankAssistantYieldsAnEmptyOutputTextItem() {
        String json = codec.encodeResponse(
                echoRequest(),
                response(ChatResponse.STOP_REASON_STOP, new Usage(1, 1, 2), new AssistantMessage(null, null)));
        assertTrue(json.contains("\"text\":\"\""), json);
    }
}
