# Benchmarks

Same-box comparison of the Janus JVM fat-jar / CLI path against the GraalVM
native image. Raw tool outputs live under `scripts/bench/.run/`; the runner is
`scripts/bench/run_bench.sh`.

## Methodology (recorded, reproducible)

- **Host:** Apple M1 Max (Darwin 25.6.0 arm64), GraalVM CE 25.0.2, Java 25.0.2, Gradle 9.6.1.
- **Load tool:** `/usr/sbin/ab` — warm-up request, then **1000 requests / 10 concurrent**
 non-streaming; **streaming counted separately** (20 concurrent SSE, all must complete).
 Tool precedence in the client is hey > wrk > ab > curl-loop; the producing tool is
 named per number (this committed run used `ab`).
- **Mock upstream:** the committed golden fake (`scripts/bench/fake_upstream.py`)
 serving the corpus (pinned usage 14/12 tokens) on `:9879` — the same
 mock for every leg, so the numbers compare the gateways, not the model APIs.
- **Auth posture:** Janus legs run with **auth off** (no master key in `config.bench.toml`)
  so the profile measures the proxy path only.
- **Postgres-store leg:** the JVM leg re-run with
  `[janus.store] type = "postgres"` (`scripts/bench/config.bench.pg.toml` — the
  bench shape with only the store block swapped, env-ref'd credentials supplied
  by the runner from a dockerized `postgres:16-alpine`): same mock, same profile,
  same auth posture — the only variable is the store. See
  [Postgres-store leg](#postgres-store-leg) below.
- **Latency:** p50/p95 from `ab`'s per-request histogram.
- **Startup:** cold = process spawn → first `/health` 200; warm = steady-state `/health`.
- **RSS:** `ps` sample post-warmup; native max-RSS also via `/usr/bin/time -l`.
- **Warm-vs-cold:** every leg runs a warm-up request before the timed run; streaming is
 timed separately from non-streaming (never mixed into one number).
- **Raw outputs** for every leg are committed under `scripts/bench/.run/` — a
 number here must be reproducible from the tool output, not remembered. Large
 install/boot logs (`*.log`) are **not** committed (gitignored); failure reasons
 stay in the bench runner output.

## Results (archives under `scripts/bench/.run/`)

The table below is recomputed from the committed `*.ab.raw.txt` archives.

| Build | Tool | Throughput (req/s) | p50 (ms) | p95 (ms) | Streams ok (/20) | Warm /health (ms) | RSS post-warmup (KiB) |
|---|---|---|---|---|---|---|---|
| **JVM** | ab | 1461.62 | 6.0 | 11.0 | 20/20 | 13.8 | 299 376 |
| **Native** | ab | 3375.90 | 2.0 | 3.0 | 20/20 | 12.7 | 130 768 |

Native startup: cold boot → `/health` **42.2 ms**; binary **86.1 MiB**
(90 238 088 bytes); lifetime max RSS **75 232 KiB**. JVM cold boot is 5.1 s
including the Gradle single-use daemon (the app itself starts in ~1 s).

### Reading the numbers

Native is about **2.3× JVM throughput** (3376 vs 1462 req/s) at about 44% of
the RSS. Cold start stays under 100 ms.

### Postgres-store leg

`run_bench.sh` also runs **Janus JVM + Postgres** — the same JVM boot
form, the same golden fake, the same 1000 req / 10 concurrent profile, with the
store swapped to `type = "postgres"` (production defaults: pool 10, call-ring
retention 1000). The leg exists to measure ONE delta: the synchronous
per-request call-ledger transaction (`PgCallLedger.recordCall`: advisory lock →
INSERT → prune DELETE → `store_meta` upsert) versus the in-memory ring. Compare
its row against the plain JVM row — same box, back-to-back legs, `ab` on both.

- **How to run:** `bash scripts/bench/run_bench.sh --skip-native` (docker
  required for the PG container; `--skip-pg` opts the leg out — a blocked leg
  is recorded as skipped-with-reason, never silently dropped).
- **How to read it:** the p50/p95 delta vs the JVM in-memory leg IS the
  per-request store-write cost. If the PG leg's p95 overhead is material (or a
  hot-key workload shows pool exhaustion), a batched-writer design is the next
  step.
- **Companion timer:** `janus_ledger_write_seconds` — an unlabeled
  Timer (percentile-histogram buckets + count/sum, the
  `janus_request_duration_seconds` shape) recorded around the store write in
  `Governance.writeCallRecord`, once per write **attempt** (success and
  contained failure alike, so a pool-exhaustion timeout lands in the tail where
  it is visible). Production deployments read the write-latency distribution
  straight off `/metrics` instead of inferring it from end-to-end latency.

| Build | Tool | Throughput (req/s) | p50 (ms) | p95 (ms) | Streams ok (/20) |
|---|---|---|---|---|---|
| **JVM (in-memory, same run)** | ab | 218.83 | 34.0 | 126.0 | 20/20 |
| **JVM + Postgres store** | ab | 80.60 | 112.0 | 212.0 | 20/20 |

One run, 2026-08-28, same box back-to-back (raw: `scripts/bench/.run/jvm.ab.raw.txt`
/ `jvm-pg.ab.raw.txt`, local — `.run/` is gitignored). **Caveats before reading
the delta:** the box was heavily loaded during this run (the in-memory JVM leg
itself measured 219 req/s vs the 1462 req/s committed reference above — absolute
numbers are NOT comparable to the archive table), and the bench's auth-off
posture makes this the **worst-case hot-key shape**: auth-off call records all
carry the `""` sentinel key, so every per-request ledger transaction serializes
on ONE advisory lock (`hashtextextended("", 0)`). Under those conditions the
measured delta was material — p50 34→112 ms, p95 126→212 ms, throughput
219→81 req/s (≈12 ms serialized per write at 10 concurrent). Two follow-ups
before acting on the delta: re-run on an idle box,
and consider a keyed-leg variant (per-key lock spread) so the number reflects a
realistic key distribution rather than the single-ring sentinel.

**To evaluate once numbers exist:** the prune DELETE
inside the same transaction is a large fraction of the write cost. Pruning every
Kth insert per key (or on a growth cadence — e.g. only when a key's ring exceeds
retention + slack) keeps the ring "eventually exact" and cheapens every write,
but it weakens the exact `dropped` pins — acceptable only if `dropped`
accounting moves into the prune transaction and the store contract tests are
updated to the documented cadence. Evaluate numerically against this leg before
touching semantics; no decision until the p95 delta exists.

### Native production tuning

- **`-O2`** — explicit pin (was already the GraalVM effective default; now recorded in
 the build).
- **`-R:MaxHeapSize=256m`** — bounded runtime heap sized from measured RSS (~75 MiB
 native memory shape): the native-image default heap is a large fraction of physical
 RAM, and an LLM proxy with an in-memory key/spend store is happy with a small bounded
 heap. 256m = ~3.4× headroom over measured RSS.
- **Native store regression:** `scripts/smoke/store/drill_native.py` (postgres-store
 boot, keyed round-trip, exact 5320 µUSD scrape, `CallRecord` in the DB) stays green
 under the tuned binary — no store-class pruning, no HikariCP JDK-proxy breakage.
- **Reflect/resource audit:** the gateway `reflect-config.json` is now drift-guarded by
 `GatewayReflectConfigDriftGuardTest` (a failure means the image would break in
 production).

## Reproduce

```bash
export JAVA_HOME="$(mise where java)"
./gradlew :janus-gateway:nativeCompile
bash scripts/bench/run_bench.sh
```

The runner writes fresh RESULTS + raw outputs; numbers above are from the committed
run and may drift slightly on a loaded machine (the comparison legs are always run
back-to-back on the same box). The Postgres-store leg needs docker (skip with
`--skip-pg`); the JVM-vs-JVM+PG comparison is reproducible without the native
binary via `bash scripts/bench/run_bench.sh --skip-native`.

## Component-level microbenchmarks (JMH)

Beyond the end-to-end `ab` load tests above, janus-core ships an **opt-in** JMH
microbenchmark suite for the per-request hot paths: the OpenAI wire codec
(`OpenAiMessageCodec`) and the shared SSE frame splitter (`SseFrameSplitter`). It
answers "is this hot path slower?" rather than "how many req/s". Run it with:

```bash
./gradlew :janus-core:jmh
```

- **Scope:** 11 ops (request/response/chunk decode + encode, SSE frames /
 data payloads), ~1–2 min on a warm machine; wired in `janus-core/build.gradle`
 via `me.champeau.jmh`.
- **Inputs:** the committed fixtures under
 `janus-core/src/test/resources/fixtures/` — the same files the golden tests read,
 so benches measure real captured shapes, never synthetic toys.
- **Output:** machine-readable JSON plus the human-readable report under
 `janus-core/build/results/jmh/results.json` for before/after comparison.
- **Caveat:** scores are machine-local only — use them to spot a hot-path regression
 on the same box (bench → change → bench), never as cross-host claims.
- **Gate:** `./gradlew build` compiles the jmh sources (so codec/splitter API drift
 fails the gate) but never runs the benchmarks; the run stays opt-in.
