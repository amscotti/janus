package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link AdminKeysController} must run its blocking store work off the
 * Netty event loop (the {@code @ExecuteOn(TaskExecutors.BLOCKING)} posture the chat
 * controllers document): with the Postgres store each admin operation is a blocking
 * JDBC round-trip, and a slow Pg (or a large {@code list}) must never stall the
 * single shared IO thread. Pins both the annotation presence and the actual dispatch:
 * a recording {@link KeyStore} observes the executing thread name per operation, and
 * the Netty event loop threads ({@code *-nioEventLoopGroup-*}) must never appear.
 */
@MicronautTest
@Property(name = "janus.test.master-key", value = "test-master-key-000")
@Property(name = "janus.test.record-threads", value = "true")
class AdminExecuteOnTest {

    private static final String MASTER_KEY = "test-master-key-000";

    @Inject
    @Client("/")
    HttpClient client;

    @BeforeEach
    void clear() {
        RecordingKeyStoreFactory.RecordingKeyStore.clear();
    }

    @Test
    void adminHandlersAreAnnotatedToRunOnTheBlockingExecutor() throws Exception {
        // The presence guard: every admin handler carries @ExecuteOn(TaskExecutors.BLOCKING)
        // (the same annotation the chat controllers use; a dropped annotation would send
        // the store's JDBC work back to the event loop).
        for (Method method : AdminKeysController.class.getDeclaredMethods()) {
            if (method.getName().equals("generate")
                    || method.getName().equals("delete")
                    || method.getName().equals("list")) {
                ExecuteOn executeOn = method.getAnnotation(ExecuteOn.class);
                assertNotNull(executeOn, method.getName() + " must be @ExecuteOn(TaskExecutors.BLOCKING)");
                assertEquals(TaskExecutors.BLOCKING, executeOn.value(), method.getName());
            }
        }
    }

    @Test
    void adminOperationsExecuteOffTheNettyEventLoop() {
        HttpResponse<String> generated = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/generate", "{\"models\":[],\"name\":\"off-loop\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, generated.getStatus());

        HttpResponse<String> listed = client.toBlocking()
                .exchange(HttpRequest.GET("/key/list").header("Authorization", "Bearer " + MASTER_KEY), String.class);
        assertEquals(HttpStatus.OK, listed.getStatus());

        HttpResponse<String> deleted = client.toBlocking()
                .exchange(
                        HttpRequest.POST("/key/delete", "{\"key_id\":\"no-such-id\"}")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + MASTER_KEY),
                        String.class);
        assertEquals(HttpStatus.OK, deleted.getStatus());

        assertFalse(
                RecordingKeyStoreFactory.RecordingKeyStore.EXECUTED_ON.isEmpty(),
                "the recording store must observe every admin operation");
        for (String thread : RecordingKeyStoreFactory.RecordingKeyStore.EXECUTED_ON) {
            assertFalse(
                    thread.contains("nioEventLoop"),
                    "admin store work must never run on the Netty event loop (ran on: " + thread + ")");
            assertTrue(thread.contains("executor"), "admin work should run on a blocking executor thread: " + thread);
        }
    }
}
