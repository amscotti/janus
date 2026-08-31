# Responses face

`POST /v1/responses` is a third ingress face. It decodes the OpenAI Responses
wire shape into the same canonical `ChatRequest` the chat faces use, then the
existing pipeline (auth, governance, router, providers) runs unchanged. The
response is encoded back as a Responses object (JSON or SSE).

This face is **stateless**. Janus does not store responses or replay them later.

## What is implemented

| Request | Behavior |
|---|---|
| `input`, `instructions`, `tools`, `text.format`, `reasoning`, `max_output_tokens`, sampling params | Mapped into the canonical request |
| Function tools + `function_call` / `function_call_output` items | Agent round-trip (the client resends prior output) |
| `stream: true` | Responses SSE event grammar; `response.completed` carries usage |
| Anthropic-format upstreams | Cross-format through the canonical model |
| Hosted `web_search` | Anthropic-upstream only; a chat-completions upstream is a typed 400 |

`store` omitted is treated as `false`. Ingress forces `include_usage` so a
completed response always has token counts.

Codex-shaped extras that are not hosted tools (`additional_tools` input items,
`namespace` tool wrappers) are skipped rather than rejected.

## What returns a named 400

| Field / item | Why |
|---|---|
| `store: true` | No stored retrieval |
| `previous_response_id` | No stored retrieval |
| `background` | No async jobs |
| Unsupported hosted tools | Only `web_search` is hosted, and only toward Anthropic |

`GET /v1/responses/{id}` and `DELETE /v1/responses/{id}` return the same
envelope 404 — there is nothing to fetch.

## Metrics and auth

The face label is `responses`. Virtual-key auth, scopes, RPM/TPM, budgets, and
exact cost accounting apply the same way they do on the chat faces.

## Not implemented

Stateful retrieval, stored conversation history, and hosted tools other than
Anthropic `web_search` are not implemented.
