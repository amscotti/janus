package io.amscotti.janus.core.codec;

import io.amscotti.janus.core.model.ChatRequest;
import io.amscotti.janus.core.model.ChatResponse;

/**
 * test-side adapter over the two codecs' shared request/response surface — the two
 * classes have identical method signatures but no common interface, and a ternary over
 * them would infer {@code Object}. The matrix/generator/idempotence tests select the
 * face/upstream codec by a boolean and dispatch through this.
 */
interface WireCodec {

    ChatRequest decodeRequest(String json);

    String encodeRequest(ChatRequest request);

    ChatResponse decodeResponse(String json);

    String encodeResponse(ChatResponse response);

    static WireCodec openAi(OpenAiMessageCodec codec) {
        return new WireCodec() {
            @Override
            public ChatRequest decodeRequest(String json) {
                return codec.decodeRequest(json);
            }

            @Override
            public String encodeRequest(ChatRequest request) {
                return codec.encodeRequest(request);
            }

            @Override
            public ChatResponse decodeResponse(String json) {
                return codec.decodeResponse(json);
            }

            @Override
            public String encodeResponse(ChatResponse response) {
                return codec.encodeResponse(response);
            }
        };
    }

    static WireCodec anthropic(AnthropicMessageCodec codec) {
        return new WireCodec() {
            @Override
            public ChatRequest decodeRequest(String json) {
                return codec.decodeRequest(json);
            }

            @Override
            public String encodeRequest(ChatRequest request) {
                return codec.encodeRequest(request);
            }

            @Override
            public ChatResponse decodeResponse(String json) {
                return codec.decodeResponse(json);
            }

            @Override
            public String encodeResponse(ChatResponse response) {
                return codec.encodeResponse(response);
            }
        };
    }

    /** The codec for {@code openAiWire} (OpenAI) or the Anthropic codec. */
    static WireCodec of(boolean openAiWire, OpenAiMessageCodec openAi, AnthropicMessageCodec anthropic) {
        return openAiWire ? openAi(openAi) : anthropic(anthropic);
    }
}
