package io.amscotti.janus.gateway;

import io.amscotti.janus.core.model.StreamChunk;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Canned upstream streams for {@link SseChunkPublisher} tests: plain, failing
 * mid-iteration, and blocking (to pin backpressure and the stall watchdog). The
 * blocking stream counts {@code next} calls so tests can assert exactly how many
 * chunks were fetched under a given demand.
 */
final class TestStreams {

    private TestStreams() {}

    static Stream<StreamChunk> of(StreamChunk... chunks) {
        return Stream.of(chunks);
    }

    /** Stream that yields {@code first} then throws {@code failure} on the next fetch. */
    static Stream<StreamChunk> failingAfter(StreamChunk first, Throwable failure) {
        Iterator<StreamChunk> iterator = new Iterator<>() {
            private boolean delivered;

            @Override
            public boolean hasNext() {
                // Always claim a next so consumers poll next and observe the failure
                // (a finite hasNext would let them treat the stream as exhausted instead).
                return true;
            }

            @Override
            public StreamChunk next() {
                if (!delivered) {
                    delivered = true;
                    return first;
                }
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new IllegalStateException(failure);
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
    }

    /**
     * Stream that blocks on {@code release} before delivering every chunk after the
     * first, counting each {@code next} call in {@code nextCalls}. Attach
     * {@code.onClose( -> release.countDown)} to emulate the upstream
     * close-releases-the-connection contract — the watchdog's close then
     * unblocks the iterator exactly like closing a socket body stream does.
     */
    static Stream<StreamChunk> blockingAfterFirst(
            List<StreamChunk> chunks, CountDownLatch release, AtomicInteger nextCalls) {
        Iterator<StreamChunk> iterator = new Iterator<>() {
            private int index;

            @Override
            public boolean hasNext() {
                return index < chunks.size();
            }

            @Override
            public StreamChunk next() {
                if (index >= chunks.size()) {
                    throw new NoSuchElementException();
                }
                if (index > 0 && release != null) {
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                nextCalls.incrementAndGet();
                return chunks.get(index++);
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
    }
}
