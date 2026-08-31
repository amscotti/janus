package io.amscotti.janus.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.amscotti.janus.core.model.AssistantMessage;
import io.amscotti.janus.core.model.ChatChoice;
import io.amscotti.janus.core.model.ChatResponse;
import io.amscotti.janus.core.model.FunctionCall;
import io.amscotti.janus.core.model.ToolCall;
import io.amscotti.janus.core.model.Usage;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The agent-loop replay contract end to end against the
 * canned fake upstream: cycle 1 returns a function call; the client replays the input
 * with {@code function_call} + {@code function_call_output} items (the stateless
 * replacement for {@code previous_response_id}); cycle 2 returns text. This is the
 * round trip every Responses SDK agent loop performs — if the decode/encode ever
 * breaks the replay shape, this test is what fails.
 */
@MicronautTest
@Property(name = "janus.test.metrics", value = "true")
class ResponsesAgentLoopTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static ChatResponse toolCallResponse() {
        return new ChatResponse(
                "chatcmpl-1",
                "chat.completion",
                1_700_000_000L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(
                        0,
                        new AssistantMessage(
                                null,
                                List.of(new ToolCall(
                                        "call_abc",
                                        "function",
                                        new FunctionCall("get_weather", "{\"city\":\"Berlin\"}", null)))),
                        "tool_calls")),
                new Usage(10, 0, 10),
                ChatResponse.STOP_REASON_TOOL_CALLS,
                Map.of(),
                null);
    }

    private static ChatResponse textResponse() {
        return new ChatResponse(
                "chatcmpl-2",
                "chat.completion",
                1_700_000_001L,
                "deepseek-v4-flash",
                List.of(new ChatChoice(0, new AssistantMessage("Sunny, 24C", null), "stop")),
                new Usage(20, 6, 26),
                ChatResponse.STOP_REASON_STOP,
                Map.of(),
                null);
    }

    @Test
    void twoCycleFunctionCallReplayRoundTrips() {
        // ---- cycle 1: the model calls the tool --------------------------------------
        TestRouterFactory.BACKEND.completeReturns(toolCallResponse());
        HttpResponse<String> cycle1 = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/v1/responses",
                                        "{\"model\":\"deepseek-v4-flash\",\"input\":\"weather in Berlin?\","
                                                + "\"tools\":[{\"type\":\"function\",\"name\":\"get_weather\","
                                                + "\"parameters\":{\"type\":\"object\","
                                                + "\"properties\":{\"city\":{\"type\":\"string\"}}}}],"
                                                + "\"instructions\":\"use tools when asked\"}")
                                .contentType(MediaType.APPLICATION_JSON),
                        String.class);
        assertEquals(HttpStatus.OK, cycle1.getStatus());
        String body1 = cycle1.body();
        assertTrue(body1.contains("\"type\":\"function_call\""), body1);
        assertTrue(body1.contains("\"call_id\":\"call_abc\""), body1);
        assertTrue(body1.contains("\"name\":\"get_weather\""), body1);
        assertTrue(body1.contains("\"arguments\":\"{\\\"city\\\":\\\"Berlin\\\"}\""), body1);
        assertTrue(body1.contains("\"instructions\":\"use tools when asked\""), body1);

        // ---- cycle 2: the client replays input + function_call + output ------------
        TestRouterFactory.BACKEND.completeReturns(textResponse());
        HttpResponse<String> cycle2 = client.toBlocking()
                .exchange(
                        HttpRequest.POST(
                                        "/v1/responses",
                                        "{\"model\":\"deepseek-v4-flash\",\"input\":["
                                                + "{\"type\":\"message\",\"role\":\"user\",\"content\":\"weather in Berlin?\"},"
                                                + "{\"type\":\"function_call\",\"call_id\":\"call_abc\","
                                                + "\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"Berlin\\\"}\"},"
                                                + "{\"type\":\"function_call_output\",\"call_id\":\"call_abc\","
                                                + "\"output\":\"{\\\"temp\\\":24,\\\"sky\\\":\\\"sunny\\\"}\"}],"
                                                + "\"instructions\":\"use tools when asked\"}")
                                .contentType(MediaType.APPLICATION_JSON),
                        String.class);
        assertEquals(HttpStatus.OK, cycle2.getStatus(), cycle2.body());
        String body2 = cycle2.body();
        assertTrue(body2.contains("\"text\":\"Sunny, 24C\""), body2);
        assertTrue(body2.contains("\"status\":\"completed\""), body2);
        assertTrue(body2.contains("\"usage\":{\"input_tokens\":20,\"output_tokens\":6,\"total_tokens\":26}"), body2);

        // The fake upstream saw a LEGAL canonical on both cycles — cycle 2's replay
        // carries the merged assistant tool call + the tool message (the decode
        // contract), and the scope check ran against the same alias. (Delta-based:
        // the static fake is shared across the JVM's test classes.)
        var replayed = TestRouterFactory.BACKEND.completeCalls.getLast();
        assertEquals(
                "call_abc",
                ((AssistantMessage) replayed.messages().get(1))
                        .toolCalls()
                        .getFirst()
                        .id());
        assertEquals(
                "call_abc",
                ((io.amscotti.janus.core.model.ToolMessage) replayed.messages().get(2)).toolCallId());
    }

    @Test
    void replayedOutputForAnUnknownCallIsA400NamingTheCallId() {
        TestRouterFactory.BACKEND.completeReturns(textResponse());
        HttpResponse<String> orphan = errorResponse(HttpRequest.POST(
                        "/v1/responses",
                        "{\"model\":\"deepseek-v4-flash\",\"input\":["
                                + "{\"type\":\"function_call_output\",\"call_id\":\"call_ghost\","
                                + "\"output\":\"x\"}]}")
                .contentType(MediaType.APPLICATION_JSON));
        assertEquals(HttpStatus.BAD_REQUEST, orphan.getStatus());
        assertTrue(orphan.body().contains("orphan_tool_output: call_ghost"), orphan.body());
    }

    /** 4xx responses surface as {@code HttpClientResponseException} — read the body out. */
    private HttpResponse<String> errorResponse(HttpRequest<?> request) {
        io.micronaut.http.client.exceptions.HttpClientResponseException exception =
                org.junit.jupiter.api.Assertions.assertThrows(
                        io.micronaut.http.client.exceptions.HttpClientResponseException.class,
                        () -> client.toBlocking().exchange(request, String.class));
        HttpResponse<?> response = exception.getResponse();
        return HttpResponse.status(response.getStatus())
                .body(response.getBody(String.class).orElse(""));
    }
}
