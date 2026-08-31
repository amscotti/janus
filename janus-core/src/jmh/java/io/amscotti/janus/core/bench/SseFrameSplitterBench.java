package io.amscotti.janus.core.bench;

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
 * Microbenchmark for the shared SSE frame grammar used by fixture tests and
 * gateway replay. Payload is the committed OpenAI stream fixture
 * ({@code fixtures/openai/chat.stream.sse}).
 *
 * <p>Note: {@link #dataPayloads} is not an independent grammar cost — it is implemented
 * as {@code frames(...).stream.map(SseFrame::data)} — so its score is {@code frames}
 * plus a small stream-mapping overhead (observed ~2%). Read the two as
 * {@code dataPayloads ≈ frames + mapping}; the marginal mapping cost is
 * {@code dataPayloads − frames}.
 *
 * <p>Opt-in: {@code./gradlew :janus-core:jmh}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class SseFrameSplitterBench {

    private String streamSse;

    @Setup
    public void setup() {
        streamSse = readFixture("fixtures/openai/chat.stream.sse");
    }

    @Benchmark
    public void frames(Blackhole bh) {
        bh.consume(SseFrameSplitter.frames(streamSse));
    }

    @Benchmark
    public void dataPayloads(Blackhole bh) {
        bh.consume(SseFrameSplitter.dataPayloads(streamSse));
    }

    private static String readFixture(String classpathResource) {
        ClassLoader cl = SseFrameSplitterBench.class.getClassLoader();
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
