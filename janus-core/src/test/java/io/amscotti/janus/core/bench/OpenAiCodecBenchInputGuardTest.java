package io.amscotti.janus.core.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.util.SseFrameSplitter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Drift guard for the JMH bench inputs. The codec bench derives its chunk input from
 * the committed {@code chat.stream.sse} fixture at {@code @Setup} time (single source of
 * truth — no inline literal), so a fixture regeneration that changes the shape of the
 * payloads the benches measure must fail {@code./gradlew build} instead of silently
 * altering the benchmark numbers.
 *
 * <p>The golden tests already pin the full wire semantics of these fixtures
 * ({@code GoldenStreamFixtureTest} et al.); this guard pins the narrower
 * <em>bench-input contract</em> — what the codec bench feeds {@code decodeChunk}/
 * {@code encodeChunk}, and that the request/response fixtures still decode through the
 * codec the benches time.
 */
class OpenAiCodecBenchInputGuardTest {

    private static final String STREAM_FIXTURE = "fixtures/openai/chat.stream.sse";
    private static final String REQUEST_FIXTURE = "fixtures/matrix/oo/plain/inbound.request.json";
    private static final String RESPONSE_FIXTURE = "fixtures/openai/chat.response.json";

    private final OpenAiMessageCodec codec = OpenAiMessageCodec.create();

    @Test
    void firstStreamPayloadIsTheRoleAnnouncementChunkTheCodecBenchMeasures() throws IOException {
        List<String> payloads = SseFrameSplitter.dataPayloads(readFixture(STREAM_FIXTURE));
        assertFalse(payloads.isEmpty(), "stream fixture must contain at least one chunk payload");

        StreamChunk chunk = codec.decodeChunk(payloads.get(0));
        // The chunk the codec bench measures: role-announcement delta with empty content,
        // a single choice, no finish reason and no usage. The earlier inline literal
        // silently mixed the role from frame 0 with the "The" content of frame 1.
        assertEquals(
                List.of(new ChunkChoice(0, new Delta(ChatRole.ASSISTANT, "", null), null)),
                chunk.choices(),
                "first data payload must stay the role-announcement chunk");
        assertNull(chunk.usage(), "first chunk must not carry usage");
    }

    @Test
    void requestAndResponseBenchFixturesDecodeThroughTheCodec() throws IOException {
        ChatRequest request = codec.decodeRequest(readFixture(REQUEST_FIXTURE));
        assertEquals("deepseek-v4-flash", request.model(), "request fixture must keep a model");

        ChatResponse response = codec.decodeResponse(readFixture(RESPONSE_FIXTURE));
        assertEquals("deepseek-v4-flash", response.model(), "response fixture must keep a model");
        assertFalse(response.choices().isEmpty(), "response fixture must keep at least one choice");
    }

    private static String readFixture(String classpathResource) throws IOException {
        try (InputStream in =
                OpenAiCodecBenchInputGuardTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("missing test fixture on classpath: " + classpathResource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
