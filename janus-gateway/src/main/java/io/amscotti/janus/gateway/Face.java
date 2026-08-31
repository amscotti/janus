package io.amscotti.janus.gateway;

import java.util.Optional;

/**
 * The ingress faces: the one
 * place that knows which normalized request paths belong to which face. Consolidates
 * what was previously four scattered copies of path↔face knowledge —
 * {@code KeyAuthFilter.MODEL_ROUTES}, {@code KeyAuthFilter.faceOf},
 * {@code GatewayExceptionHandler}'s Anthropic path test, and the controllers'
 * {@code FACE} label constants — so a third face ({@code /v1/responses}) changes one
 * file instead of four, and the filter's auth vocabulary and the handler's envelope
 * selection can never drift apart.
 *
 * <p><b>Matching styles.</b> The two chat faces are <b>exact</b> paths gated on
 * {@code POST} (a method the router 404s must not get a filter 401); the
 * Responses face is a <b>prefix</b> ({@code /v1/responses*}) gated on
 * <b>every</b> method — its {@code GET/DELETE /{id}} stub routes sit under the same
 * prefix and must not fall into the auth-exempt bucket (real OpenAI 401s them).
 *
 * <p>Deliberately NOT a registry: no factories, no DI table, no envelope knowledge —
 * {@code n} faces with labels and route matching is all the filter/handler need
 * (the architecture review's extraction-decision: light enum only).
 */
enum Face {

    /** The OpenAI chat-completions face ({@code POST /v1/chat/completions}). */
    OPENAI("openai", "/v1/chat/completions", Match.EXACT),

    /** The Anthropic messages face ({@code POST /v1/messages}). */
    ANTHROPIC("anthropic", "/v1/messages", Match.EXACT),

    /** The OpenAI Responses face ({@code /v1/responses*}, any method). */
    RESPONSES("responses", "/v1/responses", Match.PREFIX);

    private enum Match {
        EXACT,
        PREFIX
    }

    private final String label;
    private final String route;
    private final Match match;

    Face(String label, String route, Match match) {
        this.label = label;
        this.route = route;
        this.match = match;
    }

    /** The Tier-1 {@code face} metrics label (never a model alias or request id). */
    String label() {
        return label;
    }

    /** The route this face owns (exact for chat faces; the prefix for Responses). */
    String route() {
        return route;
    }

    /** Whether this face requires a virtual key for the given (already normalized) path. */
    boolean requiresVirtualKey(String normalizedPath) {
        return switch (match) {
            case EXACT -> normalizedPath.equals(route);
            // Prefix boundary: the route itself or a sub-path beneath it — never
            // `/v1/responsesfoo` (the same boundary discipline as the filter's /key
            // branch: equals(route) || startsWith(route + "/")).
            case PREFIX -> normalizedPath.equals(route) || normalizedPath.startsWith(route + "/");
        };
    }

    /** Whether the exact-path faces additionally gate on POST (the Responses prefix does not). */
    boolean gatesOnPostOnly() {
        return match == Match.EXACT;
    }

    /** The face owning a normalized path, if any (null-handling: absent → empty). */
    static Optional<Face> of(String normalizedPath) {
        if (normalizedPath == null) {
            return Optional.empty();
        }
        for (Face face : values()) {
            if (face.requiresVirtualKey(normalizedPath)) {
                return Optional.of(face);
            }
        }
        return Optional.empty();
    }
}
