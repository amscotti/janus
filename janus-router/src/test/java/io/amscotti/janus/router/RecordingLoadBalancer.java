package io.amscotti.janus.router;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * test double: returns a canned {@link #pickResult} and records every pick/start/
 * sample/end call in {@link #trace} so tests can pin hook ordering (including the
 * exception path). No state of its own — the strategies' tests use the real strategies.
 */
final class RecordingLoadBalancer implements LoadBalancer {

    final List<String> trace = new ArrayList<>();
    final ChatBackend pickResult;
    String lastModel;
    List<ChatBackend> lastCandidates;
    /** The request the 3-arg (request-aware) pick received — non-null only if the
     * router used the request-aware entry point (the contract the affinity strategy
     * rides); a router that called the 2-arg form leaves this null. */
    ChatRequest lastRequest;

    RecordingLoadBalancer(ChatBackend pickResult) {
        this.pickResult = pickResult;
    }

    @Override
    public String name() {
        return "recording";
    }

    @Override
    public ChatBackend pick(String model, List<ChatBackend> candidates) {
        lastModel = model;
        lastCandidates = candidates;
        trace.add("pick");
        return pickResult;
    }

    @Override
    public ChatBackend pick(String model, List<ChatBackend> candidates, ChatRequest request) {
        lastModel = model;
        lastCandidates = candidates;
        lastRequest = request; // pins the router's request-aware entry point
        trace.add("pick"); // same trace token — existing byte-pins stay green
        return pickResult;
    }

    @Override
    public void onRequestStart(String model, ChatBackend backend) {
        trace.add("start:" + backend.name());
    }

    @Override
    public void onLatencySample(String model, ChatBackend backend, long elapsedNanos) {
        trace.add("sample:" + backend.name());
    }

    @Override
    public void onRequestEnd(String model, ChatBackend backend, boolean success, ChatResponse response) {
        trace.add("end:" + backend.name() + ":" + success + ":" + (response == null ? "null" : response.id()));
    }
}
