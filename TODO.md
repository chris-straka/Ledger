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

## Phase 4 — Atomic posting and reads (done)

- SHA-256 semantic fingerprints; unique key arbitrates races, never
  check-then-insert; replay returns 200 + flag, mismatch is 409.
- Fresh SERIALIZABLE transaction per attempt, retry loop outside the
  template, full-jitter backoff, 503 after five attempts.
- Deferred-trigger rejections translate to 409/422, never 500.
- 23 unit + 58 integration tests green (incl. 20-way replay race);
  live worked example matches PORT.md to the minor unit.

## Phase 5 — Overdraft and concurrency proof (done)

- 50-thread exact-sum posting storm (client retries on 503 per contract),
  10-way different-payload key election, overdraft race rounds until a
  recorded serialization retry fires: one 201, one 409, final 2000.
- REPEATABLE READ harness in a disposable database exhibits the write skew
  (−6000 on DENY) that SERIALIZABLE prevents; trigger alone is insufficient.
- 62 integration tests green with per-currency conservation after every test.

## Phase 6 — Exact reversals (done)

- Server-generated inverse postings; replay/conflict semantics per key;
  double-reversal refused via the unique slot, reversal-of-reversal refused
  by kind, fraud fails at commit, spent history can refuse via overdraft.
- 7 reversal tests green (API + raw JDBC); resume claims promoted to proven.

## Phase 7 — Crash/ambiguous-response harness (done)

- Profile-gated pause points (no-ops in production); isolated Compose
  project, ports, and volumes; SIGKILL at both windows; replay recovery.
- `make crash-test` passes: 0 rows after kill-before-commit with reusable
  key, exact-once replay after kill-after-commit, conservation zero.

## Phase 8 — Observability, CI, interview demo (done)

- Micrometer outcomes/retries/latency with live Prometheus scrape + one
  Grafana dashboard under `--profile observability` (defaults uncalibrated).
- CI: wrapper validation, spotless/test/integrationTest/build, compose
  config, script syntax, isolated crash job.
- `make demo` passes 11 asserted steps; `make verify` audits the dev DB.
- README/DESIGN.md/INTERVIEW.md written from passing evidence; stale
  telemetry docs removed with one uuidv7-index note preserved.
- `tilt ci` builds and boots the stack but reports teardown SIGTERM (143)
  as failure; `tilt up` remains the dev loop, unproven headless.

## Source material (un-audited)

- `docs/` is carried over from the previous project and is NOT ledger documentation.
  Extract only verified Postgres/observability/failure-testing reasoning into
  `docs/DESIGN.md` during Phase 8; delete the rest.
- `resume.md` is user-owned; update ledger claims only after the proof passes.
