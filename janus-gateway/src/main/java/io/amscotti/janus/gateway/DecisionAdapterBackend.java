package io.amscotti.janus.gateway;

import io.amscotti.janus.core.codec.DecisionCodecException;
import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;
import io.amscotti.janus.provider.DecisionAdapter;
import io.amscotti.janus.router.DecisionBackend;
import java.util.Objects;

/**
 * Gateway adaptation of a {@link DecisionAdapter} to the router's {@link
 * DecisionBackend} seam — the decision analogue of {@code
 * ProviderAdapterChatBackend}. Carries the per-entry decisions the adapter
 * must not know about:
 *
 * <ul>
 * <li>the <b>backend name</b> the router/call ledger report (the entry's
 * provider name for the generic systemone family; the fixed family name for
 * openai-decisions);
 * <li>the <b>upstream-model remap</b>: the canonical {@code model} is the
 * client alias, so an entry's configured upstream id is stamped here; the
 * response is stamped back to the client alias (the remap is server-side);
 * <li>the <b>vision capability</b>: {@link #supportsImages()} carries the entry's
 * {@code vision} flag so the router routes image evidence to capable candidates
 * only; the dispatch-time gate below stays as a fail-closed backstop (direct
 * construction cannot bypass it) and deliberately names no backend — the
 * client must learn the capability gap, not the operator's pool topology.
 * </ul>
 */
final class DecisionAdapterBackend implements DecisionBackend {

    private final DecisionAdapter adapter;
    private final String backendName;
    private final String upstreamModel;
    private final boolean vision;

    DecisionAdapterBackend(DecisionAdapter adapter, String backendName, String upstreamModel, boolean vision) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.backendName = Objects.requireNonNull(backendName, "backendName");
        this.upstreamModel = Objects.requireNonNull(upstreamModel, "upstreamModel");
        this.vision = vision;
    }

    @Override
    public String name() {
        return backendName;
    }

    @Override
    public String baseUrl() {
        return adapter.baseUrl();
    }

    @Override
    public boolean supportsImages() {
        return vision;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        if (!vision && request.hasImageEvidence()) {
            // Backstop: the router already filters image requests to capable
            // candidates — this guards direct construction. No backend identity
            // in the message (the chat faces keep routing topology server-side).
            throw new DecisionCodecException(
                    DecisionCodecException.TYPE_INVALID_REQUEST,
                    "images are not supported by this decision backend for model \"" + request.model()
                            + "\" (set vision = true on the decision-model-list entry only for"
                            + " image-capable models)");
        }
        DecisionRequest outbound = upstreamModel.equals(request.model()) ? request : request.withModel(upstreamModel);
        DecisionResponse response = adapter.decide(outbound);
        // The client alias is the stable routing/scope/pricing identity: never echo
        // the operator's upstream-model remap (or a pooled sibling's id) back.
        return new DecisionResponse(
                request.model(), response.answers(), response.usage(), response.extras(), response.meta());
    }
}
