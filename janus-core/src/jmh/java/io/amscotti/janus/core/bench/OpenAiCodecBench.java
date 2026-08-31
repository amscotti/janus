package io.amscotti.janus.core.bench;

import io.amscotti.janus.core.codec.OpenAiMessageCodec;
import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.util.SseFrameSplitter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Microbenchmarks for the OpenAI wire codec — the hottest path on every chat request
 * and stream chunk. Input payloads are the committed fixtures under
 * {@code fixtures/} (also used by golden tests), so benches measure real shapes, not
 * synthetic toys. The chunk input is the <em>first</em> data payload of the committed
 * {@code chat.stream.sse} stream (the role-announcement delta), derived at {@code
 * @Setup} time via {@link SseFrameSplitter#dataPayloads} — a single source of truth, so
 * a fixture regeneration cannot silently change what the chunk benches measure.
 *
 * <p>Opt-in only: {@code./gradlew :janus-core:jmh}. Not part of {@code build}. Scores
 * are machine-local; use them for before/after on the same box, not cross-host claims.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class OpenAiCodecBench {

    private OpenAiMessageCodec codec;
    private String requestJson;
    private String responseJson;
    private String chunkJson;
    private ChatRequest request;
    private ChatResponse response;
    private StreamChunk chunk;

    @Setup
    public void setup() {
        codec = OpenAiMessageCodec.create();
        requestJson = readFixture("fixtures/matrix/oo/plain/inbound.request.json");
        responseJson = readFixture("fixtures/openai/chat.response.json");
        // First data payload of the committed stream fixture (the role-announcement
        // chunk: {"delta":{"role":"assistant","content":""}}), not a hand-typed hybrid —
        // the splitter + fixture are the single source of truth.
        chunkJson = SseFrameSplitter.dataPayloads(readFixture("fixtures/openai/chat.stream.sse"))
                .get(0);
        request = codec.decodeRequest(requestJson);
        response = codec.decodeResponse(responseJson);
        chunk = codec.decodeChunk(chunkJson);
    }

    @Benchmark
    public void decodeRequest(Blackhole bh) {
        bh.consume(codec.decodeRequest(requestJson));
    }

    @Benchmark
    public void encodeRequest(Blackhole bh) {
        bh.consume(codec.encodeRequest(request));
    }

    @Benchmark
    public void decodeEncodeRequest(Blackhole bh) {
        bh.consume(codec.encodeRequest(codec.decodeRequest(requestJson)));
    }

    @Benchmark
    public void decodeResponse(Blackhole bh) {
        bh.consume(codec.decodeResponse(responseJson));
    }

    @Benchmark
    public void encodeResponse(Blackhole bh) {
        bh.consume(codec.encodeResponse(response));
    }

    @Benchmark
    public void decodeChunk(Blackhole bh) {
        bh.consume(codec.decodeChunk(chunkJson));
    }

    @Benchmark
    public void encodeChunk(Blackhole bh) {
        bh.consume(codec.encodeChunk(chunk));
    }

    private static String readFixture(String classpathResource) {
        ClassLoader cl = OpenAiCodecBench.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("missing jmh fixture on classpath: " + classpathResource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
