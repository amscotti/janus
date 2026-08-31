package io.amscotti.janus.core.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.Usage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * golden response fixture: decode the committed real upstream non-streaming response
 * and assert the canonical field-by-field expectations (id/object/created/model,
 * choices[0] message role + content, {@code finish_reason} → stop-reason vocabulary,
 * usage token counts), then re-encode and assert semantic deep-equality: every fixture
 * field's value survives (order-insensitive tree compare for the mapped parts; unknown
 * top-level fields — {@code system_fingerprint} — and nested unknowns — choice-level
 * {@code logprobs} — re-emerge at the top level per the extras contract, values
 * preserved, positions not).
 */
class GoldenResponseFixtureTest {

    private static final String FIXTURE = "/fixtures/openai/chat.response.json";

    private static final String EXPECTED_ID = "chatcmpl-9d8c7b6a5f4e3d2c1b0a9f8e7d6c5b4a";
    private static final String EXPECTED_CONTENT = "The weather in Paris is 18 degrees with light rain.";

    private final OpenAiMessageCodec codec = new OpenAiMessageCodec(JsonSupport.mapper());

    @Test
    void decodeYieldsCanonicalFieldExpectations() throws Exception {
        ChatResponse response = codec.decodeResponse(read());

        assertEquals(EXPECTED_ID, response.id());
        assertEquals("chat.completion", response.object());
        assertEquals(1785715200L, response.created());
        assertEquals("deepseek-v4-flash", response.model());
        assertEquals(
                List.of(new ChatChoice(0, new AssistantMessage(EXPECTED_CONTENT, null), "stop")), response.choices());
        assertEquals(new Usage(14, 12, 26), response.usage());
        assertEquals(ChatResponse.STOP_REASON_STOP, response.stopReason());
        // Unknown top-level field (system_fingerprint) rides extras; the choice-level
        // logprobs (explicit null in the real capture) folds into the same extras map
        // ( pass-through contract). Map.of forbids null values — build by hand.
        Map<String, Object> extras = new java.util.HashMap<>();
        extras.put("system_fingerprint", "fp_3a5770e1b4");
        extras.put("logprobs", null);
        assertEquals(extras, response.extras());
    }

    @Test
    void reencodeIsValuePreservingAndSemanticallyEqual() throws Exception {
        String fixture = read();
        ChatResponse canonical = codec.decodeResponse(fixture);
        JsonNode reencoded = JsonSupport.mapper().readTree(codec.encodeResponse(canonical));
        JsonNode fixtureTree = JsonSupport.mapper().readTree(fixture);

        // Mapped top-level fields survive verbatim.
        assertEquals(fixtureTree.get("id"), reencoded.get("id"));
        assertEquals(fixtureTree.get("object"), reencoded.get("object"));
        assertEquals(fixtureTree.get("created"), reencoded.get("created"));
        assertEquals(fixtureTree.get("model"), reencoded.get("model"));
        // Choice: index + message deep-equal (order-insensitive); finish_reason verbatim.
        assertTrue(
                JsonSupport.treeEquals(
                        fixtureTree.get("choices").get(0).get("message"),
                        reencoded.get("choices").get(0).get("message")),
                "choice message must round-trip value-preserving");
        assertEquals(
                fixtureTree.get("choices").get(0).get("index"),
                reencoded.get("choices").get(0).get("index"));
        assertEquals(
                fixtureTree.get("choices").get(0).get("finish_reason"),
                reencoded.get("choices").get(0).get("finish_reason"));
        // Usage: only the modeled members are committed (OpenAiUsage drops unknowns —
        // scope, README), so the usage trees are deep-equal.
        assertTrue(JsonSupport.treeEquals(fixtureTree.get("usage"), reencoded.get("usage")), "usage must round-trip");
        // Extras re-emission: unknown top-level fields re-appear at the top level with
        // their values; the nested choice-level logprobs re-emerges at the top level
        // ( documented: values survive, positions do not).
        assertEquals("fp_3a5770e1b4", reencoded.get("system_fingerprint").asString());
        assertTrue(reencoded.has("logprobs"), "choice-level logprobs must re-emerge top-level");
        assertTrue(reencoded.get("logprobs").isNull(), "logprobs value (null) must survive");
        // Every fixture top-level field survives into the re-encode output.
        fixtureTree.propertyNames().forEach(name -> assertTrue(reencoded.has(name), name));
    }

    private static String read() throws IOException {
        try (InputStream in = GoldenResponseFixtureTest.class.getResourceAsStream(FIXTURE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
