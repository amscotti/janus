package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.codec.AnthropicCodecException;
import io.amscotti.janus.core.codec.AnthropicMessageCodec;
import io.amscotti.janus.core.codec.AnthropicSseEvent;
import io.amscotti.janus.core.codec.AnthropicStreamEncoder;
import io.amscotti.janus.core.model.ChatRole;
import io.amscotti.janus.core.model.ChunkChoice;
import io.amscotti.janus.core.model.Delta;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.StreamChunk;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.provider.ProviderException;
import io.micronaut.http.sse.Event;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * C1 — {@link AnthropicSsePublisher} unit suite (plan step 5). Uses the
 * {@link TestStreams}/{@link TestSubscriber} harnesses. Contract pinned:
 * <b>demand unit = one SSE frame</b> — the Reactive Streams contract (one
 * {@code onNext} per requested item) that the Micronaut SSE writer relies on; one
 * canonical chunk fans out to N frames through the publisher's internal queue (the
 * one-frame-per-unit precedent, generalised), the terminal {@code finish}
 * frames are emitted as ordinary queued frames (the {@code [DONE]} analogue,
 * Anthropic ends with {@code message_stop}), exhaustion →
 * {@code content_block_stop}/{@code message_delta}/{@code message_stop} then
 * {@code onComplete} + upstream close, <b>no {@code [DONE]} anywhere</b>, cancel →
 * upstream close, mid-stream codec/provider failure → {@code event: error} frame then
 * complete (never onError, never a hang), and the idle watchdog stall-close. Expected
 * frame data JSON comes from the stateful {@link AnthropicStreamEncoder} itself — the
 * publisher must name and order exactly what the encoder produces.
 */
class AnthropicSsePublisherTest {

    private static final AnthropicMessageCodec CODEC = AnthropicMessageCodec.create();
    private static final AnthropicErrorMapper ERROR_MAPPER = new AnthropicErrorMapper();

    @Test
    void oneRequestYieldsOneFrameAndOneFetchPerEmptyQueue() throws Exception {
        AtomicInteger nextCalls = new AtomicInteger();
        List<StreamChunk> chunks = List.of(textChunk("Hello"), textChunk(" world"));
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(chunks, null, nextCalls);
        AnthropicSsePublisher publisher = publisher(upstream);
        TestSubscriber subscriber = subscribe(publisher);

        List<List<AnthropicSseEvent>> perChunk = perChunkFrames(chunks);
        List<AnthropicSseEvent> firstChunkFrames = perChunk.get(0);
        List<AnthropicSseEvent> secondChunkFrames = perChunk.get(1);
        assertTrue(firstChunkFrames.size() > 1, "one content chunk opens several frames");
        assertEquals(1, secondChunkFrames.size(), "a continuation chunk on an open block emits one delta");

        // RS contract: exactly one onNext per demand unit. The first request pulls one
        // chunk (one upstream fetch) and emits its first frame; subsequent requests
        // drain the queued frames without further fetches.
        subscriber.subscription().request(1);
        assertFrame(subscriber.awaitEvent(0, 2_000), firstChunkFrames.get(0));
        subscriber.assertNotTerminated();
        assertEquals(1, nextCalls.get(), "one upstream fetch to fill the queue");

        for (int i = 1; i < firstChunkFrames.size(); i++) {
            subscriber.subscription().request(1);
            assertFrame(subscriber.awaitEvent(i, 2_000), firstChunkFrames.get(i));
            subscriber.assertNotTerminated();
            assertEquals(1, nextCalls.get(), "queued frames emit without another fetch");
        }

        subscriber.subscription().request(1);
        assertFrame(subscriber.awaitEvent(firstChunkFrames.size(), 2_000), secondChunkFrames.get(0));
        subscriber.assertNotTerminated();
        assertEquals(2, nextCalls.get(), "the next request refills the queue with a second fetch");
    }

    @Test
    void exhaustionEmitsFinishSequenceThenCompletesAndClosesUpstream() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        List<StreamChunk> chunks = List.of(textChunk("Hello"), textChunk(" world"));
        Stream<StreamChunk> upstream =
                TestStreams.of(chunks.toArray(StreamChunk[]::new)).onClose(() -> closed.set(true));
        AnthropicSsePublisher publisher = publisher(upstream);
        TestSubscriber subscriber = subscribe(publisher);

        // One demand unit per emitted frame; a large request covers chunk frames +
        // the terminal finish frames.
        subscriber.subscription().request(100);
        assertTrue(subscriber.awaitTerminal(2_000), "exhaustion must terminate");

        List<AnthropicSseEvent> expected = streamFrames(chunks);
        assertEquals(expected.size(), subscriber.eventCount(), "all chunk frames + finish frames");
        for (int i = 0; i < expected.size(); i++) {
            assertFrame(subscriber.events().get(i), expected.get(i));
        }
        assertNull(subscriber.error(), "clean exhaustion must onComplete, never onError");
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "clean exhaustion must close the upstream (close contract)");
        assertFalse(
                subscriber.events().stream().anyMatch(e -> "[DONE]".equals(e.getData())),
                "Anthropic streams terminate with message_stop — no [DONE] sentinel");
        assertEquals(
                "message_stop",
                subscriber.events().get(subscriber.eventCount() - 1).getName(),
                "the final frame must be message_stop");
    }

    @Test
    void interleavedTextAndToolFragmentsKeepBlockIndexBookkeeping() throws Exception {
        // Realistic OpenAI wire: the first tool fragment carries id+name; later
        // fragments carry neither; text and tools interleave across chunks.
        List<StreamChunk> chunks = List.of(
                toolChunk(ChatRole.ASSISTANT, "call_1", "get_weather", "{\"city\":\"S", null),
                toolChunk(null, null, null, "an", null),
                textChunk("the weather is sunny"),
                toolChunk(null, null, null, "}", "tool_calls"));
        AnthropicSsePublisher publisher = publisher(Stream.of(chunks.toArray(StreamChunk[]::new)));
        TestSubscriber subscriber = subscribe(publisher);
        subscriber.subscription().request(100);
        assertTrue(subscriber.awaitTerminal(2_000));

        List<AnthropicSseEvent> events = subscriber.events().stream()
                .map(e -> new AnthropicSseEvent(e.getName(), e.getData()))
                .toList();
        assertEquals("message_start", events.get(0).event(), events.toString());
        // bookkeeping: the tool call opens block 0; the text delta closes it and
        // opens block 1; the trailing fragment closes block 1 and opens block 2 — all
        // deltas land on the block they opened, in arrival order (no re-ordering).
        List<AnthropicSseEvent> jsonDeltas = events.stream()
                .filter(e -> e.dataJson().contains("\"input_json_delta\""))
                .toList();
        assertEquals(3, jsonDeltas.size(), "one input_json_delta per tool fragment, in order");
        assertEquals("0", jsonDeltaIndex(jsonDeltas.get(0)), "first fragment on block 0");
        assertEquals("0", jsonDeltaIndex(jsonDeltas.get(1)), "continuation stays on block 0");
        assertEquals("2", jsonDeltaIndex(jsonDeltas.get(2)), "trailing fragment reopens on block 2");
        assertEquals("{\"city\":\"S", jsonDeltaText(jsonDeltas.get(0)), "fragment verbatim");
        assertEquals("an", jsonDeltaText(jsonDeltas.get(1)));
        assertEquals("}", jsonDeltaText(jsonDeltas.get(2)));
        assertTrue(
                events.stream()
                        .anyMatch(e -> e.dataJson().contains("\"text_delta\"")
                                && e.dataJson().contains("\"index\":1")),
                "the interleaved text delta must land on block 1: " + events);
        assertEquals("message_stop", events.get(events.size() - 1).event(), "terminal frame");
    }

    @Test
    void cancelClosesUpstreamAndStopsEmissions() throws Exception {
        // The cancel-path close runs off the caller thread (the Netty event loop on a
        // real client disconnect — its onClose chain runs governance settle, possibly
        // a DB write) — await it, never assume it ran.
        CountDownLatch closed = new CountDownLatch(1);
        Stream<StreamChunk> upstream =
                TestStreams.of(textChunk("Hello"), textChunk(" world")).onClose(closed::countDown);
        AnthropicSsePublisher publisher = publisher(upstream);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        subscriber.awaitEvent(0, 2_000);
        int seen = subscriber.eventCount();
        subscriber.subscription().cancel();
        assertTrue(
                closed.await(2_000, TimeUnit.MILLISECONDS), "cancel must close the upstream (no leaked connections)");
        Thread.sleep(100);
        assertEquals(seen, subscriber.eventCount(), "no events after cancel");
    }

    @Test
    void nonPositiveRequestIsAProtocolViolation() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        Stream<StreamChunk> upstream = TestStreams.of(textChunk("Hello")).onClose(closed::countDown);
        AnthropicSsePublisher publisher = publisher(upstream);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(0);
        assertTrue(subscriber.awaitError(2_000), "non-positive request must deliver onError");
        assertNotNull(subscriber.error(), "violation is surfaced via onError");
        assertTrue(closed.await(2_000, TimeUnit.MILLISECONDS), "the upstream must be closed on the violation");
    }

    @Test
    void midStreamCodecFailureEmitsErrorFrameThenCompletes() throws Exception {
        Stream<StreamChunk> upstream = TestStreams.failingAfter(
                textChunk("Hello"),
                new AnthropicCodecException(AnthropicCodecException.TYPE_API_ERROR, "tool arguments failed to render"));
        AnthropicSsePublisher publisher = publisher(upstream);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(100);
        subscriber.awaitTerminal(2_000);

        int expectedChunkFrames = frames(textChunk("Hello")).size();
        assertEquals(expectedChunkFrames + 1, subscriber.eventCount(), "chunk frames then one error frame");
        assertEquals("error", subscriber.events().get(expectedChunkFrames).getName(), "error frame must be named");
        String frame = subscriber.events().get(expectedChunkFrames).getData();
        assertTrue(frame.contains("\"type\":\"error\""), frame);
        assertTrue(frame.contains("\"type\":\"api_error\""), frame);
        assertTrue(frame.contains("tool arguments failed to render"), frame);
        assertNull(subscriber.error(), "failure must be delivered as a frame, not onError");
    }

    @Test
    void midStreamProviderFailureEmitsErrorFrameThenCompletes() throws Exception {
        Stream<StreamChunk> upstream = TestStreams.failingAfter(
                textChunk("Hello"), new ProviderException(ProviderException.TYPE_NETWORK, "connection reset"));
        AnthropicSsePublisher publisher = publisher(upstream);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(100);
        subscriber.awaitTerminal(2_000);

        int expectedChunkFrames = frames(textChunk("Hello")).size();
        assertEquals(expectedChunkFrames + 1, subscriber.eventCount(), "chunk frames then one error frame");
        assertEquals("error", subscriber.events().get(expectedChunkFrames).getName());
        assertTrue(
                subscriber.events().get(expectedChunkFrames).getData().contains("connection reset"),
                subscriber.events().get(expectedChunkFrames).getData());
        assertNull(subscriber.error());
    }

    @Test
    void midStreamProviderFailureSetsTerminalStatusToMappedErrorStatus() throws Exception {
        // The Anthropic analogue of the OpenAI terminal-outcome threading — a
        // mid-stream failure records the mapped 5xx (TYPE_NETWORK → 502), never 200.
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        Stream<StreamChunk> upstream = TestStreams.failingAfter(
                textChunk("Hello"), new ProviderException(ProviderException.TYPE_NETWORK, "connection reset"));
        AnthropicSsePublisher publisher =
                new AnthropicSsePublisher(upstream, CODEC.newStreamEncoder(), ERROR_MAPPER, terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(100);
        subscriber.awaitTerminal(2_000);
        assertEquals(502, terminal.get(), "a network mid-stream failure must record 502");
    }

    @Test
    void nullMessageProviderFailureMidStreamStillTerminatesWithAPresentMessage() throws Exception {
        // A null-message ProviderException must never NPE *inside* the
        // mapping (which would escape the worker with no terminal signal — a hang), and
        // the Anthropic envelope must never omit `message` (NON_NULL would drop it) —
        // the frame degrades to the fixed "upstream error" message and still completes.
        Stream<StreamChunk> upstream = TestStreams.failingAfter(
                textChunk("Hello"), new ProviderException(ProviderException.TYPE_NETWORK, null));
        AnthropicSsePublisher publisher = publisher(upstream);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(100);
        assertTrue(subscriber.awaitTerminal(2_000), "a null-message failure must still terminate the stream");

        int errorIndex = subscriber.events().size() - 1;
        assertEquals("error", subscriber.events().get(errorIndex).getName());
        assertTrue(
                subscriber.events().get(errorIndex).getData().contains("\"message\":\"upstream error\""),
                subscriber.events().get(errorIndex).getData());
        assertNull(subscriber.error(), "delivered as a frame, never onError");
    }

    @Test
    void throwingSubscriberOnNextIsContainedAsErrorFrameThenComplete() throws Exception {
        // A subscriber violating RS 2.13 (throwing onNext) must not escape the
        // worker without a terminal signal (a hang) — the error-frame-then-complete
        // path, matching the OpenAI publisher's posture.
        List<StreamChunk> chunks = List.of(textChunk("Hello"));
        AnthropicSsePublisher publisher = publisher(TestStreams.of(chunks.toArray(StreamChunk[]::new)));
        AtomicReference<Subscription> sub = new AtomicReference<>();
        List<Event<String>> events = new ArrayList<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicInteger nextCalls = new AtomicInteger();
        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                sub.set(s);
            }

            @Override
            public void onNext(Event<String> event) {
                events.add(event);
                if (nextCalls.getAndIncrement() == 0) {
                    throw new IllegalStateException("subscriber exploded");
                }
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                terminal.countDown();
            }

            @Override
            public void onComplete() {
                terminal.countDown();
            }
        });

        sub.get().request(100);
        assertTrue(terminal.await(2_000, TimeUnit.MILLISECONDS), "a throwing onNext must not hang the stream");
        assertNull(error.get(), "the failure is delivered as an error frame, not onError");
        assertEquals(2, events.size(), "the first chunk frame then one error frame");
        assertEquals("error", events.get(1).getName(), "the error frame must be named");
    }

    @Test
    void idleWatchdogStallClosesAndEmitsTimeoutFrame() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger nextCalls = new AtomicInteger();
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(
                        List.of(textChunk("Hello"), textChunk(" world")), release, nextCalls)
                .onClose(() -> {
                    closed.set(true);
                    release.countDown(); // emulate socket-close unblocking the read
                });
        AnthropicSsePublisher publisher =
                new AnthropicSsePublisher(upstream, CODEC.newStreamEncoder(), ERROR_MAPPER, Duration.ofMillis(200));
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(100);
        subscriber.awaitEvent(frames(textChunk("Hello")).size() - 1, 2_000);
        // The worker's next refill blocks in next; the watchdog stall-closes it.

        assertTrue(subscriber.awaitTerminal(3_000), "watchdog must terminate the stalled stream");
        int expectedChunkFrames = frames(textChunk("Hello")).size();
        assertEquals(expectedChunkFrames + 1, subscriber.eventCount(), "chunk frames then timeout error frame");
        String frame = subscriber.events().get(expectedChunkFrames).getData();
        assertTrue(frame.contains("upstream stream stalled"), frame);
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "watchdog close must release the upstream connection");
    }

    @Test
    void aSlowButAliveClientParkedAtZeroDemandIsNeverStallKilled() throws Exception {
        // A compliant incremental-demand subscriber that requested 1,
        // consumed it, and requests nothing further parks the worker in waitForDemand —
        // the upstream is alive and client-paced, never stalled. The watchdog must not
        // close the stream (no stall frame, no terminal, no 5xx), and the stream must
        // complete normally once the subscriber resumes requesting.
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        List<StreamChunk> chunks = List.of(textChunk("Hello"), textChunk(" world"));
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(chunks, null, nextCalls);
        AnthropicSsePublisher publisher = new AnthropicSsePublisher(
                upstream, CODEC.newStreamEncoder(), ERROR_MAPPER, Duration.ofMillis(150), terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(1);
        subscriber.awaitEvent(0, 2_000);
        int seen = subscriber.eventCount();

        Thread.sleep(500); // well past the idle timeout, demand parked at zero
        assertEquals(seen, subscriber.eventCount(), "no stall frame may appear while the client paces at demand 0");
        subscriber.assertNotTerminated();

        subscriber.subscription().request(100);
        assertTrue(subscriber.awaitTerminal(2_000), "the stream completes normally when the client resumes");
        assertNull(subscriber.error(), "a client-paced stream is never a stall");
        assertFalse(
                subscriber.events().stream().anyMatch(e -> e.getData().contains("stalled")),
                "no stall frame may appear while the client paces at demand 0");
        assertEquals(200, terminal.get(), "a client-paced stream is never a stall (no 5xx)");
    }

    @Test
    void aSubscriberThrowingOnEveryNextStillReceivesATerminalSignal() throws Exception {
        // A subscriber violating RS 2.13 by throwing on *every* onNext
        // (including the error frame itself) must still terminate — the error-frame
        // onNext is swallowed so finish's onComplete reaches the subscriber (a
        // consistently-throwing subscriber must never leave the worker with neither
        // onError nor onComplete — a hang).
        AtomicReference<Subscription> sub = new AtomicReference<>();
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Event<String>> events = new ArrayList<>();
        AnthropicSsePublisher publisher = publisher(TestStreams.of(textChunk("Hello")));
        publisher.subscribe(new Subscriber<>() {
            @Override
            public void onSubscribe(Subscription s) {
                sub.set(s);
            }

            @Override
            public void onNext(Event<String> event) {
                events.add(event);
                throw new IllegalStateException("subscriber exploded on every frame");
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
                terminal.countDown();
            }

            @Override
            public void onComplete() {
                terminal.countDown();
            }
        });

        sub.get().request(100);
        assertTrue(terminal.await(2_000, TimeUnit.MILLISECONDS), "a throwing onNext must not hang the stream");
        assertNull(error.get(), "the failure is contained as an error frame + complete, never onError");
        assertEquals(2, events.size(), "the first chunk frame then the attempted error frame");
        assertEquals("error", events.get(1).getName(), "the attempted error frame must be named");
    }

    @Test
    void watchdogStallWithAMapperCrashStillUnblocksAndRecords500() throws Exception {
        // A mapper crash inside stallCheck must not skip the unblock (the
        // worker parked in the upstream read would stay asleep → connection hang) —
        // the stall degrades to a fixed 500, the terminal status is recorded, and the
        // stream still terminates.
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger nextCalls = new AtomicInteger();
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        Stream<StreamChunk> upstream = TestStreams.blockingAfterFirst(
                        List.of(textChunk("Hello"), textChunk(" world")), release, nextCalls)
                .onClose(() -> {
                    closed.set(true);
                    release.countDown(); // emulate socket-close unblocking the read
                });
        AnthropicSsePublisher publisher = new AnthropicSsePublisher(
                upstream,
                CODEC.newStreamEncoder(),
                new ThrowingAnthropicErrorMapper(),
                Duration.ofMillis(150),
                terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(100);
        subscriber.awaitEvent(frames(textChunk("Hello")).size() - 1, 2_000); // worker drains chunk 1, then blocks
        assertTrue(
                subscriber.awaitTerminal(3_000), "a mapper crash inside the watchdog must still terminate the stream");
        assertEquals(500, terminal.get(), "the stall degrades to a fixed 500");
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "the stall close must still release the upstream");
        String frame = subscriber.events().get(subscriber.eventCount() - 1).getData();
        assertTrue(frame.contains("internal server error"), frame);
        assertTrue(!frame.contains("upstream stream stalled"), "the degraded frame is the fixed 500, not the stall");
    }

    @Test
    void protocolViolationRecordsNon200TerminalStatus() throws Exception {
        // A stream killed by a non-positive request must not be recorded in
        // the 2xx bucket by the controller's close hook — the violation is threaded
        // through terminalStatus like every other failure path.
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        AnthropicSsePublisher publisher = new AnthropicSsePublisher(
                TestStreams.of(textChunk("Hello")), CODEC.newStreamEncoder(), ERROR_MAPPER, terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(0);
        assertTrue(subscriber.awaitError(2_000), "non-positive request must deliver onError");
        assertTrue(
                terminal.get() != 200,
                "a violation-terminated stream must not record a clean 200, got " + terminal.get());
    }

    @Test
    void emptyUpstreamEmitsSynthesizedMessageStartDeltaStopSequence() throws Exception {
        // An upstream that completes with zero canonical chunks (e.g. an
        // OpenAI upstream streaming only data: [DONE]) must never leave the Anthropic
        // wire bare — a strict Anthropic SDK fails to accumulate on an empty SSE. The
        // encoder synthesizes the well-formed opener → delta → stop sequence (the
        // [DONE] analogue; message_stop ends the stream), and the stream completes as
        // a clean 200.
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<Integer> terminal = new AtomicReference<>(200);
        AnthropicSsePublisher publisher = new AnthropicSsePublisher(
                TestStreams.of().onClose(() -> closed.set(true)), CODEC.newStreamEncoder(), ERROR_MAPPER, terminal);
        TestSubscriber subscriber = subscribe(publisher);

        subscriber.subscription().request(100);
        assertTrue(subscriber.awaitTerminal(2_000), "an empty upstream must still terminate");

        assertEquals(
                List.of("message_start", "message_delta", "message_stop"),
                subscriber.events().stream().map(Event::getName).toList(),
                "the empty Anthropic wire must be the synthesized opener→delta→stop sequence, never bare");
        assertTrue(
                subscriber.events().get(0).getData().contains("\"usage\":{\"input_tokens\":0,\"output_tokens\":0}"),
                subscriber.events().get(0).getData());
        assertNull(subscriber.error(), "an empty upstream is a clean completion, not onError");
        awaitTrue(closed, 2_000);
        assertTrue(closed.get(), "empty exhaustion must close the upstream (close contract)");
        assertEquals(200, terminal.get(), "an empty upstream is a clean 200");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * An {@link AnthropicErrorMapper} whose {@code map} throws — the watchdog's
     * mapper-crash stall path (the stall must still unblock the worker and
     * degrade to a fixed 500).
     */
    private static final class ThrowingAnthropicErrorMapper extends AnthropicErrorMapper {
        @Override
        ErrorMapping map(Throwable throwable) {
            throw new IllegalStateException("mapper exploded");
        }
    }

    private static AnthropicSsePublisher publisher(Stream<StreamChunk> upstream) throws Exception {
        return new AnthropicSsePublisher(upstream, CODEC.newStreamEncoder(), ERROR_MAPPER);
    }

    private static TestSubscriber subscribe(AnthropicSsePublisher publisher) {
        TestSubscriber subscriber = new TestSubscriber();
        publisher.subscribe(subscriber);
        return subscriber;
    }

    /** The frames one encoder emits for a single chunk fed first (before finish). */
    private static List<AnthropicSseEvent> frames(StreamChunk chunk) throws Exception {
        return perChunkFrames(List.of(chunk)).get(0);
    }

    /**
     * The frames each chunk emits through ONE stateful encoder in stream order — the
     * publisher's encoder is stateful (a continuation chunk on an open block emits
     * just its delta), so expectations must model the whole stream, never fresh
     * encoders per chunk.
     */
    private static List<List<AnthropicSseEvent>> perChunkFrames(List<StreamChunk> chunks) throws Exception {
        AnthropicStreamEncoder encoder = CODEC.newStreamEncoder();
        List<List<AnthropicSseEvent>> perChunk = new ArrayList<>();
        for (StreamChunk chunk : chunks) {
            perChunk.add(new ArrayList<>(encoder.feed(chunk)));
        }
        return perChunk;
    }

    /** All frames (chunk frames + terminal finish frames) for a whole stream. */
    private static List<AnthropicSseEvent> streamFrames(List<StreamChunk> chunks) throws Exception {
        AnthropicStreamEncoder encoder = CODEC.newStreamEncoder();
        List<AnthropicSseEvent> events = new ArrayList<>();
        for (StreamChunk chunk : chunks) {
            events.addAll(encoder.feed(chunk));
        }
        events.addAll(encoder.finish());
        return events;
    }

    private static void assertFrame(Event<String> event, AnthropicSseEvent expected) {
        assertEquals(expected.event(), event.getName(), "event name");
        assertEquals(expected.dataJson(), event.getData(), "event data");
    }

    private static String jsonDeltaText(AnthropicSseEvent event) {
        try {
            // {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"..."}}
            return GatewayJson.mapper()
                    .readTree(event.dataJson())
                    .get("delta")
                    .get("partial_json")
                    .asString();
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("bad delta payload: " + event.dataJson(), e);
        }
    }

    private static String jsonDeltaIndex(AnthropicSseEvent event) {
        int start = event.dataJson().indexOf("\"index\":") + "\"index\":".length();
        return event.dataJson().substring(start, start + 1);
    }

    private static StreamChunk textChunk(String content) {
        return chunk(ChatRole.ASSISTANT, content, null, null);
    }

    private static StreamChunk toolChunk(ChatRole role, String id, String name, String arguments, String finishReason) {
        return chunk(
                role, null, List.of(new ToolCall(id, "function", new FunctionCall(name, arguments))), finishReason);
    }

    private static StreamChunk chunk(ChatRole role, String content, List<ToolCall> toolCalls, String finishReason) {
        return new StreamChunk(
                "msg_1",
                "message",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChunkChoice(0, new Delta(role, content, toolCalls), finishReason)),
                null,
                Map.of());
    }

    /** Bounded wait for an async flag (the upstream close hook can trail the
     * subscriber's terminal signal on a slow runner — poll, then assert). */
    private static void awaitTrue(java.util.concurrent.atomic.AtomicBoolean flag, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!flag.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
