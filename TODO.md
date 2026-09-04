# Open work

Reviewed 2026-09-04. Ledger rewrite backlog, ordered by PORT.md phases. This file is a
backlog, not a history of completed work.

## Phase 1 — Java skeleton and local loop (done)

- Pinned Spring Boot 4.1.1, Gradle 9.7.1 wrapper, Postgres 18.6, Flyway 13.5.0.
- Minimal Boot application (Flyway disabled: app holds only runtime creds),
  liveness + DB-backed readiness, non-root Dockerfile.
- Rebuilt Compose (Postgres, role bootstrap, one-shot Flyway migrate, app),
  Tiltfile, Makefile, `.env.example`.
- Gate passed: `spotlessCheck`, unit smoke test, `build`,
  `docker compose config --quiet`, real `up` with V1–V3 applied and readiness UP.

## Phase 2 — Schema, roles, database defenses (done)

- Flyway V1 schema/keys/views/seeds, V2 runtime grants + immutable triggers,
  V3 deferred posting/overdraft/reversal validation.
- Testcontainers bootstrap on postgres:18.6 with owner/app roles; all tests
  connect as `ledger_app` with explicit commits.
- 40 direct-JDBC commit tests green: balance, checks, currency, raw
  overdraft, closure, immutability (42501 + 25001 backstop), role identity.
  Conservation audited after every test.

## Phase 3 — Pure domain and account slice (done)

- Money/currency/ID types, normal-side math, BigInteger posting draft
  validation (no Spring); ArchUnit guards the domain boundary.
- Account create/get/balance endpoints with ProblemDetail errors; balances
  derived in one statement and returned as integer strings.
- 19 unit tests + 8 HTTP slice tests green; live `up` proves create, get,
  zero balance, and duplicate 409.

## Phase 4 — Atomic posting and reads

- Fingerprinting, serializable transactions, replay/conflict semantics.

## Phase 5 — Overdraft and concurrency proof

- Whole-transaction retry, N-thread tests, weaker-isolation anomaly demonstration.

## Phase 6 — Exact reversals

## Phase 7 — Crash/ambiguous-response harness

## Phase 8 — Observability, CI, interview demo

- Rebuilt Grafana stack, Java CI workflows, `make demo`, README/DESIGN/INTERVIEW from evidence.

## Source material (un-audited)

- `docs/` is carried over from the previous project and is NOT ledger documentation.
  Extract only verified Postgres/observability/failure-testing reasoning into
  `docs/DESIGN.md` during Phase 8; delete the rest.
- `resume.md` is user-owned; update ledger claims only after the proof passes.
