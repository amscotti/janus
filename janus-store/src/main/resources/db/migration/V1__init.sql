-- V1: Janus store schema — DDL only, no data. Idempotent
-- (CREATE ... IF NOT EXISTS) so a re-run is harmless even without the
-- schema_migrations version table. The PostgresCallStore decomposes the
-- 19-method CallStore union into four JDBC pieces sharing these tables:
--   keys           → PgKeyStore       (prefix-indexed, salt + hash only)
--   rate_limits    → PgRateLimiter    (fixed-window counters, window in PK)
--   spend          → PgSpendLedger    (all-time settled + pending totals)
--   spend_entries  → PgSpendLedger    (the LedgerEntry ring, bigserial seq)
--   calls          → PgCallLedger     (the CallRecord ring, bigserial seq)
--   store_meta     → PgCallLedger     (the global monotonic dropped counter)
--   schema_migrations → SchemaMigration (version tracking, no Flyway)

CREATE TABLE IF NOT EXISTS keys (
    id text PRIMARY KEY,
    prefix text NOT NULL UNIQUE,
    salt bytea NOT NULL,
    secret_hash bytea NOT NULL,
    owner text,
    models text[] NOT NULL DEFAULT '{}',
    status text NOT NULL,
    created_at bigint NOT NULL,
    expires_at bigint,
    last_used_at bigint,
    budget_usd double precision,
    rpm int,
    tpm int
);

-- No explicit index on keys.prefix: the UNIQUE constraint above backs the O(1)
-- findByPrefix lookup with its own index — a separate keys_prefix_idx would be a
-- byte-for-byte duplicate (write amplification on every insert, no plan benefit).

-- Fixed-window counters: the PK carries the aligned window start, so a stale
-- window's row is simply ignored (an insert into the current window wins) —
-- the in-memory "rollover resets" semantic preserved by the window-in-PK.
CREATE TABLE IF NOT EXISTS rate_limits (
    key_id text NOT NULL,
    dimension text NOT NULL,
    window_start bigint NOT NULL,
    count bigint NOT NULL,
    PRIMARY KEY (key_id, dimension, window_start)
);

CREATE TABLE IF NOT EXISTS spend (
    key_id text PRIMARY KEY,
    settled bigint NOT NULL DEFAULT 0,
    pending bigint NOT NULL DEFAULT 0
);

-- The LedgerEntry ring: bigserial seq is the stable newest-first order
-- (retention prunes by seq DESC, mirroring the in-memory ArrayDeque).
CREATE TABLE IF NOT EXISTS spend_entries (
    key_id text NOT NULL,
    seq bigserial,
    at_epoch_millis bigint NOT NULL,
    micro_usd bigint NOT NULL,
    PRIMARY KEY (key_id, seq)
);

-- The CallRecord ring: key_id is NOT NULL with the '' sentinel for auth-off
-- records (mirrors InMemoryCallStore.AUTH_OFF_SENTINEL; the parity contract
-- pins recentCalls(null, n) ≡ recentCalls("", n)). Ordering is
-- (at_epoch_millis, seq) — seq BIGSERIAL is the stable tie-break for
-- same-millisecond timestamps (the contract's concurrency smoke).
CREATE TABLE IF NOT EXISTS calls (
    seq bigserial,
    request_id text NOT NULL,
    key_id text NOT NULL,
    model text,
    provider text,
    prompt_tokens bigint NOT NULL,
    completion_tokens bigint NOT NULL,
    total_tokens bigint NOT NULL,
    cache_creation_tokens bigint,
    cache_read_tokens bigint,
    cost_micro_usd bigint NOT NULL,
    duration_millis bigint NOT NULL,
    stream boolean NOT NULL,
    status text NOT NULL,
    at_epoch_millis bigint NOT NULL,
    PRIMARY KEY (seq)
);

CREATE INDEX IF NOT EXISTS calls_key_at_seq_idx ON calls (key_id, at_epoch_millis DESC, seq DESC);

-- Global monotonic counters (the dropped overflow counter).
CREATE TABLE IF NOT EXISTS store_meta (
    key text PRIMARY KEY,
    value bigint NOT NULL
);

CREATE TABLE IF NOT EXISTS schema_migrations (
    version int PRIMARY KEY,
    applied_at bigint NOT NULL
);
