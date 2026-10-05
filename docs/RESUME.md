# RESUME — claims with proof

Each bullet is a resume-safe claim. The proof column names the test or
script that backs it, so no bullet outruns the code. Numbers below are
the actual test parameters, not roundings.

## The one-line pitch

Double-entry ledger (Java 25, Spring Boot, Postgres): every money move
is an atomic, balanced, idempotent posting; eight invariants, each with
a failing-without-the-fix test against real Postgres (Testcontainers).

## Claims

1. **Balance enforced in two layers, not trusted to the app.**
   Postings validate in the domain (BigInteger, no floats) and again in
   a deferred Postgres trigger, so raw SQL can't bypass the rule.
   Proof: `PostingBalanceTest` attacks the trigger with raw SQL.
   Interview angle: why a CHECK can't do this (one row vs. the set).

2. **SERIALIZABLE with a demonstrated reason, not a default.**
   Production postings run SERIALIZABLE to kill the overdraft
   write-skew: two concurrent withdrawals that both read the old
   balance and both commit. A REPEATABLE READ harness exhibits the
   anomaly (DENY account lands −6000); the same schedule under
   SERIALIZABLE aborts one with 40001 and the loser retries.
   Proof: `ConcurrencyTest` (`repeatableReadLosesTheOverdraftRace`,
   overdraft race ends 201 + 409, final 2000).
   Note: SERIALIZABLE prevents the skew, it doesn't "order" saves —
   say _abort-and-retry_, not _out-of-order_.

3. **50-thread exact-sum concurrency proof.**
   Fifty threads hammer one account; the final balance equals the
   arithmetic sum exactly (5000). No lost updates, no locks managed
   by hand — Postgres picks the serialization loser.
   Proof: `ConcurrencyTest` (50 writers, start barrier, pooled
   connections, client 503 retry per contract).

4. **Idempotency by unique key, never check-then-insert.**
   Same key + same body replays the original (200 + flag); same key +
   different body is 409; only committed postings consume keys, so a
   lost response is recovered by retrying the key.
   Proof: `PostingApiTest` (sequential replay, mismatch 409,
   20-way concurrent replay makes exactly one posting).

5. **Immutability enforced by the database, not by convention.**
   The app role holds INSERT/SELECT only on journal tables; BEFORE
   triggers abort UPDATE/DELETE even if grants are ever widened.
   Corrections are reversal postings, never edits.
   Proof: `ImmutabilityTest` (connects as the app role, expects
   42501/25001), `ReversalApiTest` (7 tests incl. fraud-at-commit).

6. **Crash atomicity proven with SIGKILL, not asserted.**
   Kill before commit leaves zero rows and a reusable key; kill after
   commit but before the response replays exactly once via the key.
   Proof: `crash-recovery.sh` (`make crash-test`), isolated Compose
   project with profile-gated pause points.

7. **Per-currency conservation audited after every test.**
   All entries sum to zero per currency, ledger-wide, checked after
   each integration test including concurrency and crash runs.
   Proof: `LedgerIntegrationTest` base audit + `verify.sh`.

8. **Explicit overdraft policy per account.**
   ALLOW/DENY declared at creation; DENY aborts overdrafting postings
   with 409, enforced in app and by deferred constraint.
   Proof: `OverdraftTest`, `PostingApiTest` (overspend 409).

9. **No ORM**
   Raw JDBC: the tx boundary, deferred triggers, and role separation
   need explicit control an ORM would fight (managed entities,
   cascades). Trade-offs live in `DESIGN.md`.

10. **Money as integer minor units end to end.**
    `long`/`BigInteger` everywhere; a float in the money path is
    treated as a defect. Balances derived from entries in one
    statement — no balance column, nothing to drift.
    Proof: `PostingDraftTest`, `CurrencyCodeTest`, ArchUnit domain
    boundary (`DomainIsolationTest`).

11. **Measured under load, tail explained.**
    2000 balanced postings from 16 concurrent clients: 655-1207
    postings/s, p50 1-3 ms, p95 70-117 ms, conservation exactly zero
    after every run (Apple M4 Mac mini, local Postgres 18.6, 3 runs).
    The p95 is the SERIALIZABLE retry loop; 1-5 of 2000 exhaust retries
    and return 503, safe to retry on the same key.
    Proof: `scripts/load-test.sh 2000 16 spread` against
    `scripts/run-local.sh`.

## What isn't claimed

- No capacity claim: the load numbers are one laptop, loopback Postgres,
  other work on the machine.
- No multi-node story (one Postgres, by design).
- No compliance/regulatory claim of any kind.
- No FX, no Kafka/outbox — listed stretch goals in `TODO.md`, not done.
