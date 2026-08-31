# AGENTS.md — Janus agent onboarding

> Janus, the two-faced Roman god of gateways. One face speaks OpenAI; the other
> speaks Anthropic. Behind both faces, Janus routes to any provider,
> load-balances across models, governs every key, and prices every token.

This file is the onboarding contract for agents working in this repository.
It restates what the repo already enforces. When a rule here and a file
disagree, the file wins. Prefer [`README.md`](./README.md) + `docs/` for
product truth.

## Mission

Build and maintain a self-hosted, dual-protocol (OpenAI + Anthropic) LLM proxy
gateway in **Java 25 + GraalVM native-image (Micronaut 5)**. The project ships
a **single self-contained native executable** with sub-100 ms startup, runs
with zero dependencies (in-memory store) or with PostgreSQL for multi-node
state, and is configurable purely via TOML — no code changes to add
providers, models, or policies.

## Read first

1. `README.md` — feature surface, quickstart, endpoints, modules.
2. `config.toml` — annotated config reference (every `[janus.*]` section).
3. `docs/*.md` — topic guides.
4. This file — hard rules.

## Hard rules

- **Module boundaries are enforced.** `janus-core` depends on nothing
  internal; `janus-provider` and `janus-router` depend on `core` only;
  `janus-store` depends on `core` only (third-party JDBC is fine);
  `janus-gateway` wires provider + router + store; `janus-cli` depends on
  `gateway`. No module may import another's internals. Micrometer and
  Micronaut stay out of `core` / `store` / `router` / `provider`.
  **ArchUnit** re-checks this on every `./gradlew build`.
- **Test-first.** Behavior changes start with a failing test. Wire-format
  translation is fixture-based (`janus-core/src/test/resources/fixtures/`);
  CI never touches the network. Capture (`JANUS_FIXTURE_CAPTURE=1`, tag
  `capture`) is excluded from the default `test` task.
- **Native-image discipline.** No runtime reflection, no dynamic
  classloading, no ORM. Jackson config and `ServiceLoader` registrations
  are explicit and drift-guarded. Prefer records and sealed types.
- **Secrets never live in config files.** Config references env-var *names*,
  never values. Virtual keys are hashed at rest; the full `sk-janus-…`
  string appears exactly once (the `POST /key/generate` response).
- **Privacy contract.** `/metrics` labels are `face` / coarse `status` /
  `key_id` / `provider` only — never prompt text, response text, model
  alias, or request id.
- **Stay in scope.** The admin *API* (`/key/*`) is in; an admin dashboard,
  teams/orgs, and `/v1/embeddings` are out unless an explicit product-scope
  decision says otherwise. `/v1/responses` is a stateless face
  ([`docs/responses.md`](./docs/responses.md)); stored retrieval is deferred.
- **Conventional commits.** `feat:`, `fix:`, `test:`, `docs:`, `chore:`,
  `refactor:`. `main` is the only long-lived branch.
- **`./gradlew build` is the gate.** Spotless (Palantir), `-Xlint:all
  -Werror`, all module tests. If spotless fails, run `./gradlew
  spotlessApply` and rebuild. Never disable a warning to make the build
  green.

## Done for a task

- `export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null)}";./gradlew build --no-daemon` exits 0.
- New behavior has a failing-test → code → green history.
- The affected smoke harness under `scripts/smoke/` is green (offline /
  fake-upstream only).
- Docs updated (`config.toml`, topic docs, README, CHANGELOG).
- No `src/` change outside the task; module boundaries hold.
- Committed with a conventional message.

## Out of scope unless asked

- Admin dashboard UI, teams/orgs, `/v1/embeddings`.
- Version bumps / release tags without an explicit release task.
- Editing `.env` (secrets).
- Creating `CLAUDE.md`, `CONTRIBUTING.md`, or `PRIVACY.md`.
- Calling real external LLM APIs in unit tests. Live provider tests are
  opt-in (`JANUS_LIVE=1`, see `scripts/live-provider/`).
