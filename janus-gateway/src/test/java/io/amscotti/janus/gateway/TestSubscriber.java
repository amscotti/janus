package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.fail;

import io.micronaut.http.sse.Event;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Reactive Streams subscriber harness for {@link SseChunkPublisher} tests:
 * collects events in order, records the error/terminal signal, and exposes
 * await helpers with generous deadlines (the worker is a real virtual thread).
 */
final class TestSubscriber implements Subscriber<Event<String>> {

    private final List<Event<String>> events = new ArrayList<>();
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private final CountDownLatch terminal = new CountDownLatch(1);
    private final AtomicReference<Subscription> subscription = new AtomicReference<>();

    @Override
    public void onSubscribe(Subscription subscription) {
        this.subscription.set(subscription);
    }

    @Override
    public synchronized void onNext(Event<String> event) {
        events.add(event);
    }

    @Override
    public void onError(Throwable throwable) {
        error.set(throwable);
        terminal.countDown();
    }

    @Override
    public void onComplete() {
        terminal.countDown();
    }

    Subscription subscription() {
        return subscription.get();
    }

    synchronized List<Event<String>> events() {
        return List.copyOf(events);
    }

    synchronized int eventCount() {
        return events.size();
    }

    Throwable error() {
        return error.get();
    }

    boolean terminated() {
        return terminal.getCount() == 0;
    }

    Event<String> awaitEvent(int index, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            synchronized (this) {
                if (events.size() > index) {
                    return events.get(index);
                }
            }
            Thread.sleep(5);
        }
        fail("event " + index + " was not delivered within " + timeoutMillis + "ms; events=" + events());
        return null;
    }

    boolean awaitTerminal(long timeoutMillis) throws InterruptedException {
        return terminal.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** True when the terminal latch fired AND the signal was {@code onError}. */
    boolean awaitError(long timeoutMillis) throws InterruptedException {
        return awaitTerminal(timeoutMillis) && error.get() != null;
    }

    void assertNotTerminated() throws InterruptedException {
        if (awaitTerminal(150)) {
            fail("expected no terminal signal, but got error=" + error() + " complete=" + terminated());
        }
    }
}
