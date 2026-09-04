# Open work

Reviewed 2026-09-04. Ledger rewrite backlog, ordered by PORT.md phases. This file is a
backlog, not a history of completed work.

## Phase 1 — Java skeleton and local loop (in progress)

- Pin exact Spring Boot 4.1.x patch and Gradle 9.x wrapper version.
- Minimal Boot application: validated config, liveness/readiness with honest semantics.
- Production Dockerfile (non-root).
- Rebuilt Compose (Postgres, role bootstrap, migration wiring, app), Tiltfile, Makefile.
- Gate: wrapper validation, formatting, unit smoke test,
  `docker compose config --quiet`, app startup, Tilt readiness.

## Phase 2 — Schema, roles, database defenses

- Flyway migrations: grants, currency reference, journal tables, deferred triggers.
- Testcontainers bootstrap on the same Postgres image, runtime-role connections.
- Direct-JDBC commit tests before any posting API.

## Phase 3 — Pure domain and account slice

- Money/currency/ID types, normal-side math, posting draft validation (no Spring).
- Account create/get/balance endpoints.

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
