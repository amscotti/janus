-- V2: Budget reset windows. Idempotent on every ALTER (IF EXISTS /
-- IF NOT EXISTS, and the PK swap is DROP CONSTRAINT IF EXISTS → ADD) because the
-- test harness wipes schema_migrations between suites and re-applies every
-- migration file on each boot — V1 survives via IF NOT EXISTS and V2 must too.
--   spend          → window-scoped rows: window_start bigint NOT NULL DEFAULT 0
--                    (pre-V2 data backfills to window 0 = the lifetime row), PK
--                    becomes (key_id, window_start) — the rate_limits
--                    window-in-PK precedent. A lifetime key keeps its single
--                    window-0 row; a windowed key (keys.budget_duration) gets one
--                    row per aligned window, pruned to current + 2 prior.
--   keys           → budget_duration bigint (NULL = lifetime budget, seconds).

ALTER TABLE spend ADD COLUMN IF NOT EXISTS window_start bigint NOT NULL DEFAULT 0;

ALTER TABLE spend DROP CONSTRAINT IF EXISTS spend_pkey;

ALTER TABLE spend ADD PRIMARY KEY (key_id, window_start);

ALTER TABLE keys ADD COLUMN IF NOT EXISTS budget_duration bigint;
