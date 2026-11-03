package io.amscotti.janus.provider;

import io.amscotti.janus.core.model.DecisionRequest;
import io.amscotti.janus.core.model.DecisionResponse;

/**
 * Decision-model provider SPI — the System One analogue of {@link
 * ProviderAdapter}: an upstream that evaluates typed questions about a state
 * and returns typed answers with probabilities, never generated text
 * (docs/system-one.md). An adapter translates the canonical decision types
 * to/from its wire dialect ({@code /v1/systemone} or OpenAI Decisions) and
 * performs the upstream HTTP call.
 *
 * <p>The contract mirrors the chat SPI: {@link #name} is the stable router
 * key, {@link #baseUrl} is normalized (adapter appends its versioned path,
 * full-endpoint bases are dispatched verbatim), {@link #auth} describes
 * upstream authentication, and the composition root supplies every argument
 * — the SPI never reads environment variables or configuration files.
 * Failures surface as {@link ProviderException} with the shared type
 * vocabulary; canonical validation failures surface as {@code
 * DecisionCodecException} (caller bugs). {@code meta} passes through
 * untouched, and implementations are stateless and thread-safe. There is no
 * streaming form: decision calls are single non-streaming exchanges.
 */
public interface DecisionAdapter {

    /** Router key: stable, unique, lower-case. */
    String name();

    /** Normalized upstream base URL — no trailing slash, trailing {@code /v1} stripped. */
    String baseUrl();

    /** Authentication scheme + credential for the upstream. */
    ProviderAuth auth();

    /** One decision evaluation: all of the request's questions over its state. */
    DecisionResponse decide(DecisionRequest request);
}
