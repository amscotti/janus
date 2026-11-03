package io.amscotti.janus.gateway;

import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.router.DecisionBackend;
import java.util.ArrayList;
import java.util.List;

/** Gateway test double for the decision plane: canned response/failure + call record. */
final class FakeDecisionBackend implements DecisionBackend {

    private final String name;
    private final boolean vision;
    private DecisionResponse response;
    private RuntimeException failure;
    final List<DecisionRequest> calls = new ArrayList<>();

    FakeDecisionBackend(String name) {
        this(name, false);
    }

    FakeDecisionBackend(String name, boolean vision) {
        this.name = name;
        this.vision = vision;
    }

    /** Full reset — call log AND canned state, so test order never leaks between cases. */
    void reset() {
        calls.clear();
        response = null;
        failure = null;
    }

    void decideReturns(DecisionResponse response) {
        this.response = response;
        this.failure = null;
    }

    void decideFails(RuntimeException failure) {
        this.failure = failure;
        this.response = null;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String baseUrl() {
        return "http://fake/" + name;
    }

    @Override
    public boolean supportsImages() {
        return vision;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        calls.add(request);
        if (failure != null) {
            throw failure;
        }
        if (response == null) {
            throw new IllegalStateException("FakeDecisionBackend has no canned response — call decideReturns first");
        }
        return response;
    }
}
