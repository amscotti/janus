# Governance

> Janus, the Roman god of gates, is also the god of beginnings and of **keys**. This
> guide is the operator-facing reference for the governance surface:
> virtual keys, master-keyed administration, per-key rate limits and budgets, exact
> cost accounting, and the Tier-1 Prometheus metrics contract. It is the written
> companion to the `[janus.keys]` / `[janus.pricing]` / `[janus.limits]` config
> comments in `config.toml` and the javadocs of `Governance`, `KeyAuthFilter`,
> `AdminKeysController`, `GovernanceFactory`, `MetricsRecorder` and
> `MicrometerMetricsRecorder` (the source of truth).

## Quickstart

```bash
# 1. Set the master key (NEVER in TOML — env-reference only).
export JANUS_MASTER_KEY="$(openssl rand -hex 24)"

# 2. Boot with a config that opts in to auth ([janus.keys] master-key-env = "JANUS_MASTER_KEY").
./gradlew :janus-cli:run --args='--config /abs/path/config.toml'

# 3. Create a virtual key with the master key.
curl -X POST http://127.0.0.1:8080/key/generate \
 -H "x-api-key: $JANUS_MASTER_KEY" \
 -H 'Content-Type: application/json' \
 -d '{"name":"ci","models":["deepseek-v4-flash"],"budget_usd":5.00,"rpm":100,"tpm":100000}'
# → {"key":"sk-janus-<prefix>-<secret>","key_id":"…","name":"ci",…} (shown ONCE)

# 4. Use the virtual key on the model routes (Bearer or x-api-key; both faces' SDKs).
curl http://127.0.0.1:8080/v1/chat/completions \
 -H "x-api-key: sk-janus-<prefix>-<secret>" -H 'Content-Type: application/json' \
 -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"hello"}]}'
```

**Auth posture (hardened): ON with a key, or explicitly OFF.** With `auth = "on"`
(the default) a boot whose `master-key-env` resolves no key **fails fast** — a
forgotten env var must not silently run an unauthenticated admin API (it mints keys).
Auth-off remains a first-class posture for development/benchmarks via an explicit
`[janus.keys] auth = "off"` line in the config (loudly logged; it wins even when a
key resolves).

## Admin API

All admin routes are OpenAI-styled JSON, master-key-authed (`Authorization: Bearer` or
`x-api-key` — both accepted). A virtual key on an admin route, a wrong master key, or
a missing credential → `401 authentication_error`. The admin plane is
**default-deny on every HTTP method** under `/key*` (not just the registered verbs).
Repeated wrong-master-key attempts are **throttled**: 10 failures inside a 60 s window
lock the admin plane for the remainder of the window — further attempts get
`429 rate_limit_error` + `Retry-After` (a success resets the counter, so an operator's
occasional typos never accumulate into a lockout; missing-credential requests carry no
key material and never count).

| Route | Body | Response |
|---|---|---|
| `POST /key/generate` | `{"models": [...], "name":..., "budget_usd":..., "budget_duration":..., "rpm":..., "tpm":..., "duration":...}` — all optional; absent `models` = allow-all; absent caps = **no cap**; `budget_duration` (seconds, ≤ 10 years; e.g. `2592000` = 30 days) makes the budget a **reset window** (absent = lifetime budget); `duration` (seconds) sets `expires_at` from the store clock, absent = never expires | `200` `{"key": "sk-janus-…", "key_id": …, "name": …, "models": […], "budget_duration": …, "created_at": …, "expires_at": …}` — the full key is **shown exactly once** |
| `POST /key/delete` | exactly one of `{"key_id": …}` or `{"key": <full key string, resolved via its prefix>}` | `200` `{"key_id": …, "deleted": true\|false}` (idempotent; `false` for an unknown id) — `key_id` is **omitted entirely** (never `null`) when the `key` form resolves to no key (unknown prefix or wrong secret), so a client must not assume the field exists on a `deleted:false` response; a `key_id`-form delete always echoes the requested id, even an unknown one |
| `GET /key/list` | — | `200` `{"keys": [{id, prefix, name, models, status, created_at, expires_at, last_used_at, budget_usd, budget_duration, rpm, tpm}]}` — **redacted**: no full key, no hash, no salt |

Malformed bodies / missing delete identifiers / non-positive caps → `400
invalid_request_error` (the admin API's invalid-request path). A `budget_usd` too
small to represent even one micro-USD (it rounds to `0`, the "no cap" sentinel) and
one too large to fit a `long` micro-USD are rejected too, as is a `budget_duration`
outside `(0, 315360000]` seconds (10 years — above that the derived window epoch
collides with the lifetime row, so the bound makes the alias unreachable).

## Key lifecycle

- **Generation.** `sk-janus-<prefix>-<secret>` keys are created only via
 `POST /key/generate` with the master key. The store keeps only a **salted hash**
 (`KeyHash`); the full string exists in the generate response and nowhere else —
 `GET /key/list` and `POST /key/delete` never echo it, and no key material is ever
 logged. Hash comparison is timing-safe (unit-pinned by `KeyHashTest`).
- **Authentication.** Model routes (`/v1/chat/completions`, `/v1/messages`,
 `/v1/responses`) require a virtual key (Bearer or `x-api-key`). Authentication is
 one atomic store transition:
 verify hash → status → expiry → `lastUsedAt` update — a racing revoke either lands
 before (403) or after (the check already passed); never a torn state.
- **Rejection envelopes.** Missing / invalid / expired key → `401
 authentication_error` (clients: "bad key"). **Revoked key or scope-denied → `403
 permission_error`** — clients distinguish a bad key from a key taken away;
 403 matches a more informative envelope. Both faces carry the same wire types
 (`ErrorMapper` / `AnthropicErrorMapper`).
- **Scopes.** The `models` list on generate scopes the key **by model alias** — a key
 scoped to `["deepseek-v4-flash"]` calling any other alias → `403 permission_error`. A
 key whose scope is the empty/absent list is allow-all. Scope checks run post-auth in
 the controllers, on the streaming and non-streaming branches alike.
- **Revocation is immediate.** A revoked key's full string stops authenticating on the
 next request (no cache TTL); in-flight requests that authenticated before the revoke
 complete.

## Rate limits

Per-key `rpm` (requests/minute) and `tpm` (tokens/minute) caps are set on
`POST /key/generate`. **A null cap means "no cap", not zero** — `rpm: 0` is rejected
at generate with `400 invalid_request_error`. Enforcement is key-scoped and pre-dispatch; a key with no
caps (or no key at all) is never limited.

| Cap | Enforcement | Denial |
|---|---|---|
| `rpm` | consume-on-allow on the limiter | `429 rate_limit_error` + `Retry-After` |
| `tpm` | **conservative non-consuming pre-check** — the estimate prices **both sides** exactly like the budget gate: an output reserve (`max_tokens` ?? pricing-row `default-max-tokens` ?? 4096) **plus** a prompt estimate from the request's message content (sum of UTF-16 chars ÷ 4, the same heuristic the budget reserve applies); denied if `current + estimate > cap` BEFORE dispatch; real tokens accumulate at finalize (so the pre-check trips on the request *after* the one that crossed for actuals). **A request that omits `max_tokens` is estimated at the row's `default-max-tokens`** (4096 when the row omits it too), and a prompt-heavy request is priced by its prompt estimate up front — so a small-`tpm` key denies even a request whose real usage would be tiny, and sizing `tpm` for output tokens alone under-provisions prompt-heavy workloads — documented conservative semantics, pinned | `429 rate_limit_error` + `Retry-After` |

**Windows.** `[janus.limits] window` selects the limiter:

- `"fixed"` (default) — aligned 60-second windows (`FixedWindowRateLimiter`);
 `Retry-After` is **exact**: the seconds until the next aligned window end
 (`60 − now % 60`), matching the server's decision at its own clock.
- `"sliding"` — token bucket (`TokenBucketRateLimiter`, refill by rate). **The
 written decision:** the sliding variant's TPM `Retry-After` is the
 **conservative aligned-window value** (seconds until the next aligned 60s window
 end) rather than the true deficit ÷ rate refill seconds — `wouldExceed` surfaces
 only a boolean, so the aligned value is the documented upper bound (never
 under-promises; the bucket may refill sooner). RPM `Retry-After` in sliding mode is
 the limiter's own refill-derived value, present and in `[1, 60]`.

 **`"sliding"` is a token bucket, not a true sliding window.** Capacity = the limit
 with a continuous `limit/60`-per-second refill: a burst of up to `limit` is
 admitted, then the bucket re-admits only at the refill rate. A real sliding-window
 counter (e.g. weighted previous+current window) instead re-admits bursts
 as requests *age out* of the window. The difference is visible at small caps: with
 `rpm = 2` a token bucket denies the 3rd request for ~30s and then re-admits 1 per
 30s, whereas a true sliding window would re-admit a request as soon as one of the
 earlier two aged out, and the fixed window resets every aligned minute. Operators
 sizing caps should read `"sliding"` as a smoothed/leaky bucket (steady-rate
 recovery), not a true sliding-window counter that re-admits bursts as
 requests age out.

Throttled requests are denied **before dispatch** — the upstream never sees them
(proven live by the flat-counter assertions).

## Pricing

`[[janus.pricing.models]]` rows price model **aliases** (the name the client sends) in
USD per 1,000 tokens. Kebab-case element keys ONLY. Example rows:

| model | input-per-1k | output-per-1k | cache-read-per-1k | cache-creation-per-1k | default-max-tokens |
|---|---|---|---|---|---|
| `deepseek-v4-flash` | 0.00044 | 0.00132 | 0.000014 | 0 (omitted) | 4096 |
| `grok-4.6` | 0.002 | 0.006 | 0.0005 | 0 (omitted) | 4096 |

Vendors publish USD **per 1M** tokens. Janus stores **USD per 1K**, so divide the
published figure by 1,000 (`$0.44/1M` → `0.00044`). DeepSeek V4 Flash peak is
`$0.44 / $1.32` per 1M (Aug 2026; off-peak is half). xAI publishes Grok 4.6 at
`$2.00 / $0.50` cached / `$6.00` per 1M below 200k prompt. GPT-5.6 is `$4 / $20`
per 1M with a 272k long-context doubling.

Set `[janus.pricing] require-priced = true` to fail boot when a model-list alias
has no row, and to 400 a request for an unpriced alias instead of metering at $0.
Rows are keyed by `name`, so **a duplicate `name` across two
`[[janus.pricing.models]]` rows is a boot error** — the second row would otherwise
silently replace the first's rates (an operator editing the table cannot see which
rates survived). Alias rows and backend-override rows share the one name space, so
a collision between those is rejected too.

Successful non-streaming responses include `X-Janus-Cost-*-Micro-Usd` headers
(input, output, cache-read, cache-creation, search, total). Integers only — no
model alias or prompt text.

**Long-context tier.** Some vendors (xAI Grok, others) charge a higher rate once
the **prompt** reaches a threshold — commonly 200k tokens — and apply that higher
rate to the **whole request**, not just the tokens past the floor. Set
`long-context-threshold` plus `long-input-per-1k` / `long-output-per-1k` (and
optional `long-cache-read-per-1k` / `long-cache-creation-per-1k`) on the same
row. Threshold `0` or omitted disables the feature. Preflight uses the prompt
estimate to pick a tier; settle re-resolves from actual `usage.prompt_tokens`.
A threshold with every `long-*` rate at 0 is a boot error.

```toml
[[janus.pricing.models]]
name = "grok-4.6"
input-per-1k = 0.002
output-per-1k = 0.006
cache-read-per-1k = 0.0005
long-context-threshold = 200000
long-input-per-1k = 0.004 # $4 / 1M
long-output-per-1k = 0.012 # $12 / 1M
long-cache-read-per-1k = 0.001 # $1 / 1M
```

- **Conversation content in logs.** Prompts, completions, tool arguments and image
 payloads never appear in log output by default — the string form of the canonical
 records prints structure only (models, message counts, stop reasons, token-usage
 presence), so a log line stays diagnosable without the conversation. Metrics, call
 records, token counts and costs are always logged. Operators can enable content
 logging for local debugging with `[janus.privacy] log-content = true` (a boot
 warning fires while it is on).
- **Hosted web-search billing.** Models routed with hosted `web_search` may
 carry a `web-search-per-1k` rate (USD per 1K searches — Anthropic bills searches per
 1k besides result tokens, which arrive as ordinary input tokens). Each
 `web_search_call` on a non-streaming response bills one search at settle;
 pre-dispatch estimates price tokens only (the search count is unknowable upfront).
- **Accounting is exact integer micro-USD** (1 USD = 1,000,000 micro-USD; the
 `Pricing.total_micro_usd` rationale): `cost = (prompt × inputPer1K + completion ×
 outputPer1K + cacheRead × cacheReadPer1K + cacheCreation × cacheCreationPer1K) ×
 1,000,000 / 1,000`, rounded half-up at the half-micro boundary — long arithmetic,
 no BigDecimal, native-image clean. Example: usage `14` prompt / `12` completion
 at 0.14 / 0.28 per 1K = `0.00196 + 0.00336 = 0.00532` USD = **5320 micro-USD
 exactly**, which is what `/metrics` scrapes.
- Float USD is display-only; the ledger stores micro-USD integers. Unknown models
 meter at **zero rate** and log once (never fail the request).
- `default-max-tokens` is the TPM-estimate / budget-reserve fallback when a request
 omits `max_tokens` (gateway fallback 4096 when the row also omits it).

## Budgets

Per-key `budget_usd` caps are set on `POST /key/generate` (null = no cap). Budgets
are enforced with an **atomic reserve → settle → release** ledger
(`InMemorySpendLedger`/`PgSpendLedger`, one entry per key per budget window —
lifetime keys have a single window-0 entry; see "Reset windows" below):

- **Reserve (pre-dispatch).** `total = settled + pending + estimate`; the estimate
 prices **both sides**: an output reserve (`estimateTokens × outputPer1K`) **plus**
 a prompt estimate derived from the request's message content (sum of UTF-16 chars
 / 4 × `max(inputPer1K, cacheReadPer1K, cacheCreationPer1K)` — a prompt token bills
 at exactly one of the three rates, so the max is the worst case; a cache-creation
 prompt is the most expensive Anthropic rate, 1.25× input). Pricing the prompt up
 front means a prompt-heavy request cannot drive spend arbitrarily past the cap
 before the next request is denied. It is a heuristic, not a bound — a pathological
 chars-per-token ratio can still overshoot once; `settle` corrects the reservation
 to actual. If `total ≥ cap` → `429 rate_limit_error` with **no `Retry-After`** — a
 budget does not refill on a timer — and the request never reaches the upstream.
- **Settle (post-dispatch).** the reservation is corrected to the actual micro-USD
 cost (a large reservation that settles far below releases the difference) and the
 spend entry is recorded.
- **Release (streams).** an aborted stream releases its reservation (never a leaked
 denial).
- **Concurrency bound.** the reserve's increment-then-check is atomic per key, so a
 parallel burst against a hard cap admits exactly the reservations that fit and the
 settled spend never exceeds **cap + one request** (proven live by the racing
 leg; unit-pinned by `InMemorySpendLedgerTest`).
- **Soft cap.** `[janus.limits] soft-cap-fraction = 0.8` (default): once
 `settled + pending ≥ cap × fraction`, non-streaming successes carry
 `X-Janus-Budget-Warning: soft` + `X-Janus-Budget-Used-Micro-Usd` and fire the
 `budget_exceeded` notifier event (`tier: soft`, key-scoped). **Streams: notifier-
 only** — SSE headers are already sent, so no header is possible.
- **Notifier dedup.** `DedupNotifier` fires `budget_exceeded`
 **once per key per 60-second window** — a key parked over the soft line no longer
 spams one WARN per request (16 soft-crossing successes → exactly 1 event, proven
 live by the gate). The reservation-time spurious-warning half (a large reservation
 that settles far below can warn on a near-zero actual) is **accepted and
 documented** — budget-aligned (soft fires on the reserve-time total); the dedup
 bounds its cost. The notifier is logger-only by default (`WARN`); set
 `notifier-webhook-url` for a webhook sink. **Prune on delete:** a successful
 `POST /key/delete` calls `Notifier.forgetKey`, dropping the key's dedup-window
 entry — dedup state no longer grows unboundedly across key churn, and a key that is
 deleted and re-created fires `budget_exceeded` again in the same window.
- **Webhook fallback:** a syntactically-invalid `notifier-webhook-url`
 (e.g. a space or a typo) no longer aborts the gateway boot — the factory validates
 the URL, logs the misconfiguration (never echoing the URL) and falls back to the
 logger-only notifier, and `WebhookNotifier` parses the URL lazily so a bad value
 can never raise into `notify`.

### Reset windows

A key created with **`budget_duration`** (seconds, set on `POST /key/generate`,
carried by `GET /key/list`) turns its `budget_usd` cap into a periodic allowance —
"$50/month per key" is `budget_usd: 50` + `budget_duration: 2592000`. Absent
`budget_duration` = the **lifetime** budget of 1.0, byte-identical semantics.

- **Window derivation.** `windowStart = floorDiv(nowSeconds, budgetDuration) ×
 budgetDuration` (the fixed-window rate-limiter arithmetic), aligned to the epoch.
 Each window's ledger state is keyed by `(key_id, window_start)` — one row per
 window in Postgres, one entry in memory (the PG primary-key shape on both backends,
 so a straddled reservation settles into the *same* window entry either way).
- **Rollover.** A reserve in a newer window starts that window at `settled = 0` —
 the cap refills **forward-only** (a stepped-back clock keeps the stored window).
 `settle`/`release` always target **the reservation's** window (the window start is
 threaded from the reserve result through the request lifecycle), so a reservation
 that settles after a rollover credits its own window.
- **Straddle limitation (documented, pinned).** A reservation straddling a boundary
 is invisible to the NEW window's admission guard: its actual cost counts against
 the reservation's window and the all-time total, never the new window. At most one
 in-flight request per key can so straddle.
- **Two read views.** `X-Janus-Budget-Used-Micro-Usd` and the soft-cap notifier
 payload report the **window** view (the current window's settled spend); the
 all-time view (`totalSpendByKey`) accumulates across windows and survives the
 retention prune (Postgres folds each pruned window row's settled into the key's
 window-0 accumulator row; memory keeps a per-key scalar). Windowed soft-cap events
 gain `window_reset_epoch_seconds` in the payload.
- **`Retry-After`.** A windowed hard-cap 429 carries `Retry-After` = seconds until
 the window resets; lifetime budget 429s keep the header-less shape.
- **Bounded retention.** Windowed spend rows are pruned to the current + 2 prior
 windows (a sampled write-path janitor, the `rate_limits` pattern) — bounded growth,
 exact totals.
- **Validation.** `budget_duration` must be a positive integer ≤ 315,360,000 s
 (10 years): a longer duration would derive window epoch 0 — the lifetime row —
 silently aliasing the key's windowed spend onto its lifetime total.

## Metrics

Prometheus exposition at **`GET /metrics`** (text/plain; version=0.0.4 — the path is
pinned in `application.toml`; `/prometheus` → 404). Tier-1 is always on by design —
there is no `[janus.metrics]` kill-switch. Series (see `MicrometerMetricsRecorder` for
the full table):

| Series | Type | Labels |
|---|---|---|
| `janus_requests_total` | Counter | `face` (openai\|anthropic\|responses\|admin — the last for the `/key/*` operations and master-key rejections on those routes) × `status` (coarse `2xx`\|`4xx`\|`5xx`; a stream ending with a mid-stream error frame/stall counts `5xx`, never `2xx`) |
| `janus_request_duration_seconds` | Timer | `face` — **percentile-histogram `_bucket` lines** (le=…, le="+Inf") **and** count/sum/max |
| `janus_ledger_write_seconds` | Timer | (unlabeled) — call-ledger store-write duration, percentile-histogram buckets + count/sum; once per write attempt (success and contained failure alike) |
| `janus_tokens_in_total` / `janus_tokens_out_total` | Counter | (unlabeled) |
| `janus_cost_micro_usd_total` | Counter | (unlabeled; exact integer micro-USD) |
| `janus_key_requests_total` / `janus_key_tokens_in_total` / `janus_key_tokens_out_total` / `janus_key_cost_micro_usd_total` | Counter | `key_id` |
| `janus_upstream_healthy` | Gauge | `provider` × `base_url` (1 = dispatch-eligible) |
| `janus_upstream_breaker_state` | Gauge | `provider` × `base_url` (0=CLOSED, 1=HALF_OPEN, 2=OPEN) |

- **Privacy guarantee (the privacy contract):** labels are `face` (openai/anthropic/responses/admin) / coarse
 `status` / `key_id` / `provider` ONLY (plus the per-instance `base_url` on the
 upstream gauges — a coarse identity; the label carries
 `scheme://host[:port][/path]` only, never the query or userinfo, so a credential
 embedded in a configured base URL can never reach an unauthenticated scrape) —
 never prompt text, response text, model alias or request id, in any series or
 HELP/TYPE line. The gate plants distinctive
 markers in a prompt and in the golden response body and asserts their absence from
 the live exposition (JVM + native).
- **`key_id` is the per-key label.** There is no team concept. The per-key
 series carry the opaque, non-secret, operator-created `key_id` — bounded
 cardinality (the key set is finite).
 **Series do not outlive the key:** a successful `POST /key/delete` calls
 `MetricsRecorder.forgetKey`, which removes the four `janus_key_*` counters for that
 `key_id` from the registry, so high key churn over a long process lifetime does not
 grow the exposition unboundedly. The unlabeled totals (`janus_tokens_*`,
 `janus_cost_*`, `janus_requests_total`) and the per-provider gauges carry no
 `key_id` label and are untouched — a revoked key's past traffic still counts toward
 the aggregates. Recorded in `config.toml` too.
- **Streams-record-zero note:** streams that exhaust without a terminal usage chunk
 (the client did not request `stream_options.include_usage`; Janus never forces it
 upstream, with **one face-scoped exception**: the Responses face
 forces `include_usage` at ingress decode so Responses-face streams
 always settle usage and never record a zero entry) record a **zero** tokens/cost
 entry; aborted streams record nothing.
- **Mid-stream failure status:** a stream that ends with a mid-stream error
 frame or a stall timeout records its **request** in the `5xx` bucket (never `2xx`),
 so a provider dying mid-flight does not inflate the SLO error-free rate; the
 `janus_requests_total` series and the call ledger (which writes `ERROR_UPSTREAM`)
 now agree.
- **Client-cancelled streams count 4xx, not 2xx:** a client abort before clean
 exhaustion records the terminal status **499** (nginx's client-closed-request
 convention; the coarse bucket folds it into `4xx`) — an aborted stream is not a
 successful response and must not inflate the success rate. A recorded error status
 or a clean exhaustion always wins over the 499 flip. The close the cancel triggers
 (whose settle/release is a JDBC round-trip with the Postgres store) runs on a fresh
 virtual thread, never the Netty event loop the disconnect arrives on — one aborting
 stream's bookkeeping never blocks the loop for every other connection.
- **Filter-level rejections:** `KeyAuthFilter` records rejected
 requests (face × 401/403/429) into `janus_requests_total`'s `4xx` bucket — filter-level
 denials never reach the controllers, and the bucket counts real proxy traffic. A
 **store failure on the auth path** (e.g. Postgres down in store mode) is metered the
 same way into the `5xx` bucket before the 500 envelope is returned, so a DB outage
 leaves metric evidence even though no controller ever ran. The recording is
 **best-effort** — a throwing recorder is logged and dropped, never allowed to replace
 the client's true envelope with its own failure. The call ledger is deliberately
 **not** written on this path: a pre-dispatch auth denial is a middleware rejection,
 not a call — the series counts the 4xx, the `calls` table has no row. The virtual-key
 decision itself runs on the **blocking executor** (never the Netty event loop — with
 the Postgres store it is a JDBC round-trip), offloaded from the filter via a deferred
 publisher; wire behavior is unchanged.
- **"Latency histogram" wording:** the duration Timer publishes
 percentile-histogram `_bucket` lines — not just the count/sum/max summary form.

## Config reference

```toml
# [janus.keys] — the governance switch
[janus.keys]
master-key-env = "JANUS_MASTER_KEY" # env var NAME, NEVER the value

# [janus.pricing] — per-alias USD-per-1K rows
[janus.pricing]
[[janus.pricing.models]]
name = "deepseek-v4-flash"
input-per-1k = 0.00044 # $0.44 / 1M (peak)
output-per-1k = 0.00132 # $1.32 / 1M (peak)
# cache-read-per-1k = 0.0000028 # DeepSeek cache-hit input; omit for zero
# default-max-tokens = 4096 # TPM/budget estimate fallback when max_tokens is absent
# long-context-threshold = 200000
# long-input-per-1k = 0.004
# long-output-per-1k = 0.012

# [janus.limits] — limiter variant + soft tier
[janus.limits]
window = "fixed" # "fixed" (default) | "sliding" (token bucket)
soft-cap-fraction = 0.8 # budget soft tier as a fraction of the hard cap
# notifier-webhook-url = "http://host:port/hook" # absent ⇒ logger-only notifier
# ledger-retention = 1000 # per-key spend-ledger ring entries, both backends
```

Defaults live in the factories (absent sections / null components): `GovernanceFactory`
applies the fixed-window limiter default, empty price table (zero-rate metering), 0.8 soft
fraction and logger-only notifier, while `CallStoreFactory` (where the store beans are
built) applies the 1000-entry `ledger-retention` default — the knob sizes the per-key
spend-ledger ring in **both** backends, independently of `[janus.store] retention` (the
call ring) — reproducing the ungoverned (auth-off) shape byte-identically. An
unknown `window` value and a non-positive `ledger-retention` fail fast at boot
(binding-time validation, the `[janus.store] retention` precedent — a typo'd ring size is
never silently swapped for the default).

## Security notes

- Master key and virtual-key secrets are **never** in TOML, logs, exception messages,
 or envelopes; the full key string appears only in the generate response. The gate's
 security leg greps every Janus log for a distinctive per-run master key and for a
 freshly generated virtual key — absent everywhere.
- Keys are **hashed at rest** (salted, timing-safe compare; unit-pinned by
 `KeyHashTest` / `InMemoryKeyStoreTest`).
- The auth surface is exactly: the model routes (`/v1/chat/completions`,
 `/v1/messages`, `/v1/responses`) require virtual keys; `/key/*` requires the
 master key; **`/health`, `/metrics`, `/v1/models` and every unlisted path are
 exempt** — the models list stays public metadata. A stricter default for
 unlisted paths is explicitly out of scope.
- Admin credentials work via both `Authorization: Bearer` and `x-api-key` (both faces'
 SDK conventions); a **virtual key on the admin API is rejected** (401).

## Auth-off (explicit)

Auth-off is an explicit `[janus.keys] auth = "off"` line — loudly logged, wins
even when a key resolves. Smoke configs that still need a keyless boot declare
it that way. A deployment that forgets `JANUS_MASTER_KEY` with the default
`auth = "on"` **fails fast** (see Quickstart). The gate's regression leg
boots with `auth = "off"` and proves both directions (auth-on tripwire +
auth-off passthrough).

## Envelope and header vocabulary

Janus uses `budget_usd`, `budget_duration`, `rpm`, `tpm`, `rate_limit_error`, and
`Retry-After`. There is no team concept (`key_id` is the metrics label). Revoked
keys are 403. Scopes and pricing are keyed by model alias. There is no
`max_parallel_requests` knob — the atomic reserve bounds overspend to cap + one
request. Limit 429s carry `Retry-After`; so do **windowed**-budget 429s (seconds
until the window resets) — only **lifetime** budget 429s are header-less. Soft-cap
warnings use `X-Janus-Budget-*`; for a windowed key `X-Janus-Budget-Used-Micro-Usd`
is the **current window's** settled spend, not the all-time total.
