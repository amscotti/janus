# Routing

> Janus, the Roman god of gates, has two faces — one inward toward the client, one
> outward toward the providers. The **router** is the inward-facing machinery behind both
> API faces: it maps every model alias to one or more provider backends, picks a backend
> per request, retries transient failures with backoff, falls back across backends in
> config order, tracks upstream health, and hard-stops a misbehaving upstream with a
> circuit breaker. This guide documents the configuration surface (`[janus.router]` +
> multi-backend `[[janus.model-list]]` aliases), the semantics of each selection
> strategy, the retry/fallback/health/breaker interplay, and knob-by-knob tuning
> guidance. Cross-references: `AGENTS.md` (module boundaries), the README module table,
> and `docs/adding-a-provider.md` (the provider SPI + the `ProviderException` retryable
> matrix the retry policy consumes).

## What routing does

A model alias in `[[janus.model-list]]` is a **routing key**: callers send
`model = "deepseek-v4-flash"` and the router resolves it to one or more backends.
Two entries with the same `name` are **two backends in one ordered candidate list**
for that alias (`ModelListFactory` rejects two entries for one alias whose resolved
backend names collide — the same provider twice, or two different custom names both
falling back to the fixed-name `anthropic` wire-format family; the router's
constructor enforces the same rule directly, so duplicate backends never silently
collapse onto the config-order-first candidate). The candidate
list drives everything this page describes:

- **Selection** — one backend is picked per request by the configured `strategy`.
- **Retries** — a transient failure (429/5xx/network/timeout) is retried with
 exponential backoff + jitter, up to `max-retries` retries *after* the first attempt.
- **Fallback chains** — retries walk the candidate list in config order (first untried
 healthy backend), so a failing primary rolls to the next provider.
- **Health** — consecutive failures flip an upstream unhealthy for a cooldown period,
 after which one trial attempt is allowed (passive recovery).
- **Circuit breaker** — a harder, explicit closed → open → half-open state machine per
 upstream that *refuses* dispatch (except its single probe) while health *ranks*
 candidates.

**Streaming boundary.** Retry and fallback are legal only *before*
the first chunk flushes. `stream` runs the attempt loop while opening the connection;
once a backend's stream is delivered, it is never retried or failed over. The breaker
still watches the open stream: a stream that dies before its first chunk counts as a
connect failure; one that dies mid-stream is transient (it never trips the breaker).

## Config reference

The full `[janus.router]` block. **Kebab-case spellings are the documented keys**;
under a plain section Micronaut normalizes `_` and `-` equivalently, so `max_retries`
also works — but the array-of-tables keys in `[[janus.model-list]]` do **not**
normalize: `api-key-env`/`base-url` are binding, `api_key_env` silently binds null.

| TOML key | Conceptual key | Type | Default | Meaning | Unit |
|---|---|---|---|---|---|
| `strategy` | `strategy` | string | `"round-robin"` | Selection strategy (`round-robin` \| `least-inflight` \| `latency-based` \| `cost-based` \| `weighted` \| `session-affinity`) | — |
| `latency-alpha` | `latencyAlpha` | float | `0.3` | EMA smoothing factor for `latency-based`; must be in `(0, 1]` | — |
| `weights` | `weights` | inline table | `{}` | `weighted`-strategy weights, keyed by backend (provider) name | — |
| `max-retries` | `maxRetries` | int | `2` | Retries *after* the first attempt (≤ `max-retries` + 1 tries total); `0` = one attempt | — |
| `backoff-base-ms` | `backoffBaseMs` | int | `200` | Exponential backoff base | ms |
| `backoff-max-ms` | `backoffMaxMs` | int | `2000` | Backoff cap | ms |
| `jitter` | `jitter` | float | `0.2` | Fractional jitter of the capped delay; must be in `[0, 1]` | — |
| `allowed-fails` | `allowedFails` | int | `3` | Consecutive failures before an upstream flips unhealthy | — |
| `cooldown-time` | `cooldownTime` | int | `10` | Probation before one trial attempt (passive health) | **seconds** |
| `breaker-failure-threshold` | `breakerFailureThreshold` | int | `5` | Failures in the rolling window before OPEN (default 5) | — |
| `breaker-window-seconds` | `breakerWindowSeconds` | int | `60` | Rolling failure window | **seconds** |
| `breaker-cooldown-seconds` | `breakerCooldownSeconds` | int | `30` | OPEN → half-open cooldown | **seconds** |

> **Units are the #1 footgun.** `backoff-*-ms` are millis; `cooldown-time` and the
> `breaker-*-seconds` keys are **seconds** (seconds, not millis). The factory
> converts cooldown seconds to millis at `PassiveUpstreamHealth` construction
> (`cooldownMillis = seconds * 1000`, pinned by `LoadBalancerFactoryTest`). A
> seconds/millis mixup silently changes recovery speed by 1000×.

An **absent `[janus.router]` section is a valid boot state** — the factory applies the
documented defaults. Default-on is deliberate: retryable failures
are now retried with backoff even on single-backend configs; `max-retries = 0` restores
exactly-one-attempt behavior. Out-of-range values fail at **boot**, not on first request
(alpha, retry bounds, `allowed-fails`, breaker knobs are validated in the strategy/
health/breaker constructors).

A commented `[janus.router]` block with all defaults lives in `config.toml` (repo
root). Multi-backend aliases need no router-section change — just repeat the
`[[janus.model-list]]` entry:

```toml
[[janus.model-list]]
name = "deepseek-v4-flash"
provider = "deepseek"
api-key-env = "DEEPSEEK_API_KEY"

[[janus.model-list]]
name = "deepseek-v4-flash"
provider = "anthropic"
api-key-env = "ANTHROPIC_API_KEY"

[janus.router]
strategy = "weighted"
weights = { deepseek = 3, anthropic = 1 }
```

## Strategy semantics

Six strategies, selected by `strategy` (each documented in the javadocs; the
config keys are the `LoadBalancer.name` values). All are stateless-or-thread-safe and
native-image clean (the gateway's `LoadBalancerFactory` switch constructs them by direct
reference — no reflection).

| Strategy | Pick rule | State | Tie-break | When to choose |
|---|---|---|---|---|
| `round-robin` | Sequential cycle per model alias | One counter per alias | n/a (exact cycle) | Default; even spread, no state about quality |
| `least-inflight` | Fewest requests currently being served | Per-backend in-flight count | Lowest index in config order | Heterogeneous upstreams with slow tails; latency-sensitive batch work |
| `latency-based` | Lowest EMA of success latencies (TTFT for streams, total for completions) | Per-backend EMA (`alpha`-smoothed) | Lowest index | Long-running steady state where latency is the cost; see alpha tuning below |
| `cost-based` | Lowest cumulative cost from actual usage — spend-equalizing (the cheaper backend is picked more, the expensive one is still served until spend converges) | Per-backend cumulative micro-USD | Lowest index | Real pricing is live via `pricingCost` (`PriceTable`, alias row with per-backend override); an empty table ties to config order (boot warning) |
| `weighted` | Random pick proportional to operator weights | Weights (static) | Probability by weight | Operator knows the desired split (e.g. 75/25 across providers) |
| `session-affinity` | Rendezvous (HRW) hash of the request's session id — same conversation, same backend | None on the HRW path (inner round-robin counters for sessionless requests) | Highest hash score; score tie → config order | Provider prompt caches (Anthropic `cache_control`, DeepSeek context caching) make consecutive turns of one conversation cheapest on the backend that already has the context |

Two soft spots worth knowing:

- **Latency exploration rule.** A candidate with *no latency sample yet* is preferred in
 config order — a never-tried upstream must get traffic before it can be scored. With
 `latency-based`, a brand-new backend drains traffic from everyone until sampled, then
 competes on its EMA. A candidate whose last sample is at least `cooldown-time` old is
 re-explored too (the freshness rule is wired from the health cooldown): a backend that
 spent a health/breaker cooldown out of rotation competes on a *stale* EMA on recovery
 otherwise — re-exploring forces a fresh sample before it competes again, so a
 recovered-but-slow upstream is not starved by its stale history. (Both behaviors are
 pinned by `LatencyBasedLoadBalancerTest.unsampledCandidatesPreferredInConfigOrder` and
 `LatencyBasedLoadBalancerTest.staleSampleIsReExploredBeforeCompetingOnItsStaleEma`.)
- **`alpha` tuning.** `ema = alpha * sample + (1 - alpha) * ema`. High `alpha` (near 1)
 reacts to the latest sample (jittery picks); low `alpha` smooths slow shifts (stale
 picks). `0.3` is the default; raise it if upstream latency changes fast, lower it if
 picks flap.
- **Weighted pool exclusion.** A listed backend whose weight is missing or ≤ 0 is
  *excluded from the pool*; if every candidate is excluded the pick falls back to the
  first candidate in config order. Boot warns about backends without positive weights
  (`LoadBalancerFactory.warnAboutWeights`) — silent starvation is the footgun. Weight
  keys that match no listed backend are warned too. (The exclusion and the
  all-excluded → first-candidate fallback are pinned by
  `WeightedLoadBalancerTest.zeroAndMissingWeightsAreExcluded` and
  `WeightedLoadBalancerTest.allWeightsZeroOrAbsentFallsBackToFirstAvailable`.)

### Session affinity

Under balanced routing, consecutive turns of one conversation scatter across backends,
defeating provider prompt caches (Anthropic `cache_control`, DeepSeek context caching) —
the cheapest tokens are the ones the provider already has. `session-affinity` makes the
client opt a conversation into backend stickiness:

- **Header contract.** Send `x-janus-session-id: <id>` on any chat/messages/responses
  request (lowercase-hyphen like the `anthropic-beta` inbound precedent; header lookup
  is case-insensitive, the value is trimmed, blank counts as absent). The gateway folds
  it into the gateway-internal `ChatRequest.meta` under `janus.session-id` on **all
  three faces** — it is routing input only: never serialized, never logged, never
  forwarded upstream (the whitelisted-reader rule on the meta contract).
- **Selection rule.** Rendezvous (highest-random-weight) hashing: every candidate is
  scored with FNV-1a 32-bit over `sessionId + "|" + backend.name`; the highest score
  wins; a score tie keeps the config-order-first candidate. Rationale: HRW is stateless
  and node-independent — both nodes of a two-gateway cluster compute the same pick with
  zero new shared state — and *consistent*: removing an unhealthy backend moves only
  the sessions that hashed to it (a `hash mod N` stickiness would reshuffle every
  session on any membership change). The pick sequence for fixed ids is deterministic
  and unit-pinned.
- **Fallback.** No session id (absent/blank header) → an inner round-robin picks, so a
  mixed client population behaves exactly like the default strategy.
- **Weights are ignored** under `session-affinity` — the hash decides every pick;
  configuring both draws a boot warning (remove the weights, or use `weighted`).
- **Retry interplay.** The sticky pick applies at attempt 0; retries walk the candidate
  list in config order like every other strategy (the existing fallback-chain
  contract). Because the affinity pick only ever chooses within the pool the router
  hands it (post-health-filter, post-breaker-filter, narrowed on claim contention), a
  degraded backend simply drops out of the hash's candidate set — exactly HRW over a
  smaller set — and rejoins deterministically when it recovers.

## Retries & fallback chains

Per request, the attempt budget is `max-retries + 1` tries. Attempt 0 is the
load-balancer pick; each retry walks the candidate list deterministically — first
untried *healthy* backend in config order (all tried → re-pick). Backoff:
`min(base * 2^n, max)` for the `n`-th retry (attempt 0 = the delay before the first
retry), plus fractional jitter: the actual delay lies in
`[capped, capped + jitter * capped]` (`0` jitter = deterministic).

**Retryable matrix** — the classifier (`ProviderRetryClassifier` bridging
`ProviderException.retryable`) says retry for:

| Failure | Retry? |
|---|---|
| HTTP 429 / rate-limit frame | yes |
| HTTP 5xx (incl. Anthropic 529 overloaded) | yes |
| Transport (connect refused/reset, DNS,...) | yes |
| Timeout (no response within the request timeout) | yes |
| HTTP 401/403 (auth) | **no** |
| Other 4xx / 3xx | **no** |
| Malformed upstream payload (codec/SSE framing) | **no** |
| Unknown exceptions / `Error` / client-side validation | **no** |

Never retry on 4xx/auth — a credentials or request-shape problem will not fix itself.
Earlier retryable failures are attached as **suppressed exceptions** to the final thrown
error, so the whole chain is debuggable at the client's error envelope. Interruption
during backoff aborts the chain (shutdown signal, not a retry condition).

## Health & circuit breaker

Two mechanisms consume the same per-attempt failure events but play different roles —
**soft filter vs hard state machine** — and their knobs must not fight:

- **`PassiveUpstreamHealth`.** `allowed-fails` consecutive failures flip an
 upstream unhealthy; after `cooldown-time` seconds of probation one *trial attempt* is
 admitted — the single trial is claimed atomically **at dispatch time on the picked
 backend only** (a concurrent burst after cooldown sends at most one request to the
 still-degraded backend, and an admitted-but-unpicked candidate keeps its trial, so a
 strategy that keeps preferring another backend cannot starve the degraded one out of
 its recovery probes — the same check/claim decoupling the breaker uses); a trial
 success recovers it, a trial failure re-cooldowns it, and a terminal outcome that is
 neither — a non-retryable client error (4xx/auth), or a stream abandoned before its
 first chunk — **releases the claimed trial** without an outcome (the same settlement
 the breaker's half-open probe gets), so the slot never stays busy for an extra
 cooldown window. Cooldown is
 lazy (checked when candidates are filtered — no scheduler thread). All-unhealthy
 fails **open**: stale health never hard-fails a request. The optional active
 `/health` probe seam exists; the real HTTP probe is future work (the backend has no
 base URL by design) and the default is passive-only.
- **`CircuitBreaker`.** `breaker-failure-threshold` failures within the
 rolling `breaker-window-seconds` open the breaker; OPEN denies dispatch until
 `breaker-cooldown-seconds` elapse, then admits **exactly one** half-open probe (claimed
 atomically at dispatch). Probe success closes; probe failure re-opens with a fresh
 cooldown. All-blocked fails open to a single probe, whose outcome re-trips or recovers
. Failures *before the first stream chunk* count;
 mid-stream failures are transient. **Disabling:** set `breaker-failure-threshold = 0`
 to turn the breaker off (the health layer stays on; the window/cooldown keys are then
 ignored). This is the only supported disable — a threshold `< 0` is a boot error.

**Which failures count.** Health and the breaker consume the same per-attempt failure
events, and both count only **retryable (transport-class)** failures — 429/5xx/network/
timeout. Client-driven non-retryable errors (4xx/auth/bad-payload) are the client's fault,
never an upstream degradation, so a burst of client 400s or 401s must not soft-exclude a
healthy upstream or trip its breaker; they are propagated (per the retry matrix) and only
the load balancer's end hook sees them (slot release). This holds on the streaming path
too: health records a stream's success only at the **first consumed chunk** (never at
connect), so a stream that *connects* then dies before the first chunk counts as a
failure for both — the connect-path success is not allowed to mask a zero-chunk death;
a failure *after* the first chunk is transient for the breaker and stays health-neutral
(the backend demonstrably delivered bytes).

**Why both?** Health ranks (drops unhealthy candidates, remembers across cooldowns);
the breaker refuses (a hard trip that survives a single success would require many
failures to re-open). Tune `allowed-fails` lower than `breaker-failure-threshold` if you
want soft exclusion to act first; keep them consistent so the two don't fight (e.g.
`3`/`5` as defaulted).

## Tuning guidance

Start here (the defaults):

```toml
strategy = "round-robin"
latency-alpha = 0.3
max-retries = 2
backoff-base-ms = 200
backoff-max-ms = 2000
jitter = 0.2
allowed-fails = 3
cooldown-time = 10
breaker-failure-threshold = 5
breaker-window-seconds = 60
breaker-cooldown-seconds = 30
```

Knob-by-knob tradeoffs:

- **`max-retries` vs latency budget.** Each retry adds backoff delay *before* the next
 attempt. 2 retries with the defaults cap total worst-case waiting at
 200 + 400 (+jitter) ms before the third try — plenty for a 429 storm,
 too much for a sub-100 ms interactive budget. Raise `backoff-base-ms` for slow
 upstreams, lower `max-retries`/`backoff-max-ms` for latency-sensitive faces.
- **`allowed-fails` vs `breaker-failure-threshold`.** `allowed-fails` decides how fast a
 flaky upstream is soft-excluded; the breaker decides when a *pattern* hard-stops it.
 If `allowed-fails` ≥ `breaker-failure-threshold` the breaker acts first and health
 never gets to rank — keep `allowed-fails` below the breaker threshold (3 vs 5).
- **`cooldown-time` (seconds!)** sets recovery speed: short → fast trials, more
 failures against a still-broken upstream; long → slow recovery, fewer wasted probes.
 10 s is a good middle for a gateway.
- **`latency-alpha`** responsiveness, as above. Start at `0.3`; move toward `0.1` if
 picks flap, toward `0.7` if upstream latency shifts fast.
- **`jitter`** 0.2 spreads retry bursts; `0` is deterministic (thundering-herd risk
 across clients retrying the same upstream).

Common footguns:

- **Weights silently starving a backend.** A `weighted` strategy with a missing/zero
 weight excludes that backend — check the boot warning before wondering why one
 provider never sees traffic.
- **Seconds vs millis.** `cooldown-time` and `breaker-*` are seconds; `backoff-*` are
 millis. A 10 vs 10,000 mixup changes recovery by 1000×.
- **Single-provider aliases never exercise fallback.** Retries still help (429/5xx
 retried on the *same* backend), but failover needs ≥ 2 different providers per alias —
 two `[[janus.model-list]]` rows that share a `name` and use different providers.
- **`max-retries = 0`** restores the one-attempt behavior exactly.

## Observability & troubleshooting

What to look for when traffic misbehaves:

- **404 `model_not_found`** — alias typo, or the `model-list` is empty/absent (an empty
 router lists `[]` at `GET /v1/models`). Check the config keys: array-of-tables keys
 are `name`/`provider`/`api-key-env`/`base-url` (kebab; underscore spellings silently
 bind null).
- **Upstream 401s** — missing env key: the boot warning names the entry and the env var
 (`ModelListFactory`); the adapter sends no `Authorization` header when the secret is
 blank.
- **One-sided traffic under `weighted`** — check the weight warnings: a backend without
 a positive weight is excluded (fallback keeps the *first* candidate in the pool, not
 a random one).
- **Breaker state transitions** — the breaker is a per-upstream state machine:
 CLOSED → OPEN after `breaker-failure-threshold` failures in the window, OPEN → HALF_OPEN
 after the cooldown, HALF_OPEN → CLOSED on probe success / OPEN on probe failure.
 Traffic to an OPEN backend is refused (except the single probe); a healthy-looking
 but breaker-tripped backend is visible as one-sided routing with no 5xx. The state is
 per-process state — no dashboard, but the state machine is deterministic
 and testable (`CircuitBreakerTest`).
- **All-upstreams-down** — health fails open (requests still go out as probes) and the
 breaker fails open to a single probe: the system prefers a request that fails to a
 hard 503, by design. Metrics for this state are on `/metrics`.

## Cross-references

- `docs/adding-a-provider.md` — the provider SPI, `ProviderException` types + the
 retryable matrix the classifier consumes, and how to add an upstream.
- Module boundaries (`AGENTS.md`): the router depends only on core; all TOML parsing,
 env reads and strategy/breaker construction live in the gateway composition root
 (`janus-gateway`), and `janus-router` is consumed, not modified.
- The routing smoke (`scripts/smoke/routing/`) exercises the two-provider failover
 drills this page describes — 500/429/hang over real sockets.
