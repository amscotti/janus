package io.amscotti.janus.router;

import java.util.ArrayList;
import java.util.List;

/**
 * test double: records every health hook call in {@link #trace} and leaves candidate
 * lists untouched (no filtering — filtering behavior belongs to the real
 * {@link PassiveUpstreamHealth} tests). Lets {@link RouterResilientTest} pin that the
 * router feeds every failure/success back to the health state and filters per attempt.
 */
final class RecordingUpstreamHealth implements UpstreamHealth {

    final List<String> trace = new ArrayList<>();

    @Override
    public void recordFailure(ChatBackend backend) {
        trace.add("failure:" + backend.name());
    }

    @Override
    public void recordSuccess(ChatBackend backend) {
        trace.add("success:" + backend.name());
    }

    @Override
    public void releaseTrial(ChatBackend backend) {
        trace.add("release:" + backend.name());
    }

    @Override
    public List<ChatBackend> healthy(List<ChatBackend> candidates) {
        trace.add("healthy");
        return candidates;
    }

    @Override
    public boolean passivelyHealthy(ChatBackend backend) {
        return true; // no filtering in this double — everything dispatch-eligible
    }
}
