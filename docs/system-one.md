# System One decision models

Janus gateways **decision models** (System One models) next to chat models.
A decision model never generates text: it evaluates typed questions about a
`state` and returns typed answers with probabilities — built for the
branching inside agents (routing, eligibility, verification, triage,
escalation), where a chat completion is slow, costly, and uncalibrated.

Jev (TypeSafe) set the API layout, and Janus adopts it as its one client
dialect for decisions:

```
POST /v1/systemone
{"model":"d3","state":{...},"questions":{
  "refund":{"type":"noul","instructions":"Is the customer asking for money back?"},
  "team":{"type":"choice","instructions":"Which team?","criteria":{"billing":"Charges","technical":"Bugs"}},
  "urgency":{"type":"score","instructions":"How urgent?","criteria":["Can wait","This week","Today"]}}}
→ {"model":"d3","answers":{
    "refund":{"type":"noul","noul":0.97},
    "team":{"type":"choice","choice":"billing","probabilities":{"billing":0.9,"technical":0.1},"confidence":0.8},
    "urgency":{"type":"score","score":1.4,"legend":{"0":"Can wait",...},"probabilities":{...},"confidence":0.3}},
   "usage":{"input_tokens":414,"output_tokens":0}}
```

**Drop-in:** an app written against the Jev API (or the TypeSafe SDK)
changes its base URL to Janus and its key to a Janus virtual key, then
selects the backend by `model` id — Jev, a local Ollama decision model, or
OpenAI, from the same code. Janus applies virtual-key auth/scopes,
RPM/TPM/budgets, pricing, load balancing, and failover on that path, and
translates only where a provider's dialect differs.

TypeSafe SDK compatibility: request bodies may be **zstd-compressed** (the
SDK default for bodies ≥ 1 KiB) or **multipart/form-data** — the JSON body as
the `request` part and each image as a file part. Janus decompresses zstd
under the same 32 MiB ceiling and inlines multipart file parts into their
`assets` as `data_base64` + `format` before dispatch; the upstream call is
always JSON. Errors still use the OpenAI envelope, not TypeSafe's `{detail}`
shape; the beta image/Box headers are forwarded/derived; structured
`instructions`/`criteria` values are supported.

## The primitives

| Type | Asks | Criteria | Answer |
|---|---|---|---|
| `noul` | Is this true? | optional `{"true":…, "false":…}` | `noul`: P(true), 0–1 |
| `choice` | Which one? | `{option: description}` (≥1 at the face; OpenAI 2–255) | choice + probabilities + confidence |
| `score` | How much? | ordered level descriptions (≥1 at the face; OpenAI 2–10) | weighted 0-based score + legend + probabilities + confidence |
| `box` | Where in the image? | none (TypeSafe beta) | `box`: `{top,bottom,left,right}` + confidence |

`instructions` and every criteria value may be a string, object, array, or
null — TypeSafe's `EntryType` accepts structure, and the `systemone` leg
passes it through. The OpenAI leg has no structured fields, so it folds
structure into the instructions/description text (documented loss).

`state` is a string, a JSON object, or a JSON array; numbers/booleans and
`stream`/`box` capability flags are typed 400s at the face. There is no
streaming. An upstream that declines a question answers
`{"type":"refusal"}` for it; note strict Jev-shaped SDKs drop unknown answer
types, so a refusal reads as a missing key there.

## Images and the TypeSafe danger zone

Image evidence is one canonical concept with three wire shapes. Any image
evidence — `images[]`, TypeSafe `assets`, or a Box question — routes to
`vision = true` entries only; a mixed pool deterministically degrades to its
image-capable members, and an alias with no capable entry is a typed 400
before dispatch.

| Dialect | Client shape | What Janus sends upstream |
|---|---|---|
| `systemone` / Ollama / Clef (direct) | `images: ["<raw base64 PNG/JPEG/WebP>"]` | the same `images[]` array (raw base64 only; URLs/data-URLs are typed 400s) |
| TypeSafe (Jev), direct or `typesafe/jev-*` via OpenRouter | `assets` with `data_base64` **or** a multipart `request` part + image file parts; references in `state`/`instructions`/`criteria`; optional `box` questions | the same assets as JSON (`part` refs inlined to `data_base64`), plus `TypeSafe-Danger-Zone-Images: true` (forwarded from the client, or derived from an assets/Box body) |
| OpenRouter Clef/Luna facade | `state` array image part: `{"type":"image_url","image_url":{"url":"data:image/png;base64,…"}}` | the same `state` array (OpenRouter rejects top-level `images[]`/`assets` for these models) |
| OpenAI Decisions (direct) | `images: ["<raw base64>"]` | `input` content parts: `input_image` with a sniffed `data:image/...;base64,` URL |

Image parts nested in `state` are detected for `vision` routing as well as
`images[]`, TypeSafe `assets`, and Box questions. Multipart bodies are capped at 32 MiB total; each image part must be PNG or
JPEG (TypeSafe's asset formats), and every file part must be named by exactly
one asset's `part`. TypeSafe also enforces upstream image dimensions and
format limits (a 160×160 PNG was rejected live as `Invalid image dimensions`;
512×512 worked), and its 4xx is surfaced as a typed client error. TypeSafe's `TypeSafe-Danger-Zone-Long-Context: true`
header is forwarded when the client sends it. `TypeSafe-Danger-Zone-Images` is forwarded when sent and
is also derived from `assets` or a Box question so a drop-in SDK body never
reaches TypeSafe with the gate disabled. TypeSafe `assets` values are
base64/`part` image objects — Janus does not convert `images[]` into
`assets` (the asset reference must appear in the request text), so use the
dialect matching the backend. OpenAI question-level image instructions are passed through when the client
sends `instructions` as `input_text`/`input_image` parts; TypeSafe asset
references are not translated into OpenAI parts, so use shared `images[]` for
the OpenAI leg.

## Providers and dialects

One client dialect in, two wire dialects out. Only decision models belong
here — no classifier/reranker APIs and no chat-model emulation.

| Wire format (`wire-format`) | Upstreams | Path Janus calls |
|---|---|---|
| `systemone` | TypeSafe (Jev), OpenRouter, Ollama local + cloud, Cloudflare Clef, any `/v1/systemone` server | `{base}/v1/systemone` |
| `openai-decisions` | OpenAI Decisions (`gpt-6-luna`) | `{base}/v1/decisions` |

The `systemone` leg is near-passthrough: unmodeled top-level fields ride
both ways (Ollama's `prompt_eval_cached_count` reaches your client;
TypeSafe's `assets` map rides unchanged). Question/answer/usage nested
extras are not carried. Ollama serves the named local models — `d3`,
`clef`, `nimble-v3`, `tev1` — at `http://127.0.0.1:11434` with the
`decision` capability; `d3` and `clef` are the documented image-capable
local models (set `vision = true`). The same ids work hosted by pointing the
entry at the hosted base URL instead; Clef's id differs by host (`clef` on
Ollama Cloud, `clef:cloud` through a local Ollama, in-path on Cloudflare),
so use `upstream-model`.

Cloudflare's REST `/ai/run/` endpoint wraps the model output in
`{result, success, errors, messages}`; Janus unwraps `result` and treats
`success:false` as a typed upstream failure (502).

The `openai-decisions` leg is a real translation: `state`→`input`
(structured state becomes an `input_json` part, 256 KiB/32-level/10k-value
caps, typed 400 when exceeded), `noul`→`predicate` (true/false criteria
fold into the instructions text — documented loss; criteria keys other than
`"true"`/`"false"` are a typed 400 at the face), choice criteria→`choices[]`,
score criteria→`levels[]`, and answers map back by name (duplicate answer
names from upstream are typed 502s — one answer per question). Top-level
unmodeled fields (`temperature`, `user`, …) ride the OpenAI leg untouched;
they are the caller's responsibility (OpenAI may reject undocumented
fields). Boolean choice values are the one wire-type fold: the canonical
criteria map is string-keyed, so `true`/`false` values re-encode as the
strings `"true"`/`"false"`. OpenAI caps a request at 400 questions, 2–255
choice options, and 2–10 score levels; over-limit requests fail over to
another candidate, and are typed 400s when every candidate declines.
TypeSafe `assets` and Box questions are TypeSafe-only: the OpenAI adapter
declines them (typed 400 after failover), never silently drops them.

Where a backend's own limits are known to Janus, they are enforced per
picked backend, never globally. Other upstreams' own limits (local Clef
2–26 choice options vs its hosted form's 255; TypeSafe's lack of a
question-count limit) are the upstream's to reject: Janus forwards what its
structural validation (types, required members, primitive minimums — checked
once at the face) accepts.

## Configuration

Decisions live in their own tables so a name can serve chat and decisions
independently. Entries mirror `[[janus.model-list]]`: duplicate names form
one round-robin/failover pool. `base-url`/`api-key-env` merge entry >
`[janus.decision-providers.<name>]`; the three timeouts merge entry > block >
`[janus.timeouts]`. Secrets are env-var names only. Retry/backoff reuse
`[janus.router]`; the decision router round-robins (the chat `strategy`
does not apply) and walks fallbacks on retryable failures. Passive health and
the circuit breaker remain chat-only for now — decision calls are short and
idempotent, but a permanently failing pooled backend still costs one failed
attempt per rotation (remove it from config to stop that).

Retries can reach the upstream more than once; only the final response's input
is settled. Decision calls are short and idempotent by design, but a timeout
retry may still be billed by the operator while the client is charged once.
The 32 MiB global request-body ceiling applies to this face; TypeSafe `assets`
and OpenAI images consume it.

Configuration is validated at boot (a startup probe resolves the decision
router and price table). A bad provider, blank base URL, invalid
`wire-format`, dead block, or missing `require-priced` row refuses the node
instead of passing `/health` and failing the first request.

```toml
[[janus.decision-model-list]]
name = "jev-latest"            # what callers send
provider = "typesafe"
# upstream-model omitted → the alias is sent upstream verbatim

[[janus.decision-model-list]]
name = "d3"
provider = "ollama-local"
vision = true                  # d3 judges images (images[] dialect)

[[janus.decision-model-list]]
name = "clef"
provider = "cloudflare"
upstream-model = "clef"        # the id the upstream sees (see below)
vision = true

[[janus.decision-model-list]]
name = "gpt-6-luna"
provider = "openai-decisions"
vision = true

[janus.decision-providers.typesafe]
wire-format = "systemone"
base-url = "https://api.typesafe.ai"
api-key-env = "TYPESAFE_API_KEY"

[janus.decision-providers.ollama-local]
wire-format = "systemone"
base-url = "http://127.0.0.1:11434"
# no api-key-env → no Authorization header (local). Cold model loads can be
# slow: raise header-timeout-seconds here if the 60s default trips.

[janus.decision-providers.cloudflare]
wire-format = "systemone"
base-url = "https://api.cloudflare.com/client/v4/accounts/<account>/ai/run/@cf/cloudflare/clef"
api-key-env = "CLOUDFLARE_API_TOKEN"

[janus.decision-providers.openai-decisions]
wire-format = "openai-decisions"
api-key-env = "OPENAI_API_KEY"
# base-url defaults to https://api.openai.com

[janus.pricing]
[[janus.pricing.models]]       # decisions bill input tokens only
name = "jev-latest"
input-per-1k = 0.000042        # $0.042 / Mtok
output-per-1k = 0.0

[[janus.pricing.models]]
name = "clef"
input-per-1k = 0.00024         # $0.24 / Mtok hosted
output-per-1k = 0.0
```

Three deliberate differences from chat entries:

- **`upstream-model`** — the model id sent upstream. Chat Janus sends the
  client alias verbatim, but one decision model has different ids per host
  (Clef is `clef` on Ollama Cloud, `clef:cloud` through a local Ollama, and
  in-path on Cloudflare), so decision entries may remap. The client alias
  stays the stable routing, scope, and pricing key, and the response
  `model` echoes the alias (never the remap).
- **`vision`** — admits image evidence to this backend. Absent/false is a
  typed 400 before dispatch; set it only for image-capable models.
- **Run URLs** — a `base-url` containing `/ai/run/` or ending in
  `/systemone` is dispatched verbatim for the `systemone` leg; a base ending
  in `/decisions` is verbatim for the OpenAI leg. Otherwise Janus appends
  `/v1/systemone` / `/v1/decisions`. Query strings/fragments are rejected at
  boot.

Pricing reuses `[[janus.pricing.models]]` keyed by decision alias. Budgets
estimate pre-dispatch from the compact serialized request (state, questions,
extras — bytes/4, with each `images[]` entry or `assets` entry charged 1024
tokens) and settle to the upstream's reported input in exact micro-USD.
Decisions are input-only: the OpenAI leg's cached/cache-write input is
folded back into the input term, and output/cache ledger columns stay
0/null. A response without `usage` settles the pre-dispatch estimate rather
than $0. Unpriced decision models follow the unpriced-models rule (tracked,
zero, or denied); `require-priced = true` also covers decision aliases and
fails boot when a row is missing.

## Governance, metrics, privacy

`POST /v1/systemone` is a model route: when auth is on it requires a virtual
key (Bearer or `x-api-key`), scoped by decision alias, with the same
RPM/TPM/budget semantics and spend headers as chat. Key scopes and pricing are
**alias-based and shared across faces**: a key allowed chat alias `X` also
authorizes decision alias `X` (and vice versa), and one pricing row prices both.
Use distinct names if you need separate authorization or rates. Tier-1 metrics carry
`face="systemone"`; call-ledger rows carry `stream=false` (long-context/
cache/search columns stay 0/null). `state`, instructions, images, and assets
are user content under the same privacy contract as prompts: never in metric
labels, never in logs unless content logging is opted into, never persisted.

Errors use the OpenAI envelope on this face: malformed requests, unsupported
capability use, and per-backend cap failures are 400 `invalid_request_error`;
missing/invalid keys 401; scope denial 403; unknown decision aliases 404
`model_not_found`; oversized requests 413; unsupported content type or request
encoding 415;
budget/rate limits 429 (with `Retry-After` where applicable); upstream
failures 502; timeouts 504. Malformed **upstream** answer payloads are 502
`api_error`, not 500. Decision aliases do not appear in `GET /v1/models`
(that list stays chat-callable models); clients set `model` explicitly, as
Jev clients already do.

## Trying it locally

No provider key is needed with a local Ollama, but the gateway still needs a
master key and a virtual key by default. For a local-only smoke, either set
`[janus.keys] auth = "off"` or generate a key via `POST /key/generate`.

```bash
ollama pull tev1:0.8b        # smallest decision model (~1.5 GB)
# config:
# [[janus.decision-model-list]]
# name = "tev1:0.8b"
# provider = "ollama-local"
# [janus.decision-providers.ollama-local]
# wire-format = "systemone"
# base-url = "http://127.0.0.1:11434"
curl http://localhost:8080/v1/systemone \
  -H 'Content-Type: application/json' -H "x-api-key: $JANUS_KEY" -d '{
  "model":"tev1:0.8b","state":"I was charged twice.",
  "questions":{"refund":{"type":"noul","instructions":"Is the customer asking for money back?"}}}'
```

Swap `model` to any other configured decision alias (`d3`, `clef`,
`nimble-v3`, `jev-latest`, `gpt-6-luna`) — the request body does not change.
The offline gate is `scripts/smoke/decisions/run.sh` (fake System One +
Decisions upstreams; it covers drop-in, translation, failover, governance,
vision/image dialects, and the TypeSafe danger-zone headers; `--native` adds
the GraalVM leg, and `JANUS_LIVE=1` adds a direct local Ollama wire check).
Model licenses are the operator's concern: `nimble-v3` is CC BY-NC
(noncommercial); `d3` and Clef are Apache 2.0.
