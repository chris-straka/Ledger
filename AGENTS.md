# Ledger

A double-entry ledger in **Java 25 + Spring Boot + Postgres**.
Balances are derived per account from entries. Money moves
by _postings_ — sets of entries that must sum to zero. Nothing is ever updated in place or deleted.

The project is meant to be demonstrated and defended in an interview. Prefer a smaller claim the
code can prove over a larger claim hidden behind comments.

## The accounting contract

This project _is_ its invariants. Each one is a test, and none is optional.

1. **Every posting balances.** Debits equal credits, per posting, per currency. Enforced in the
   domain _and_ by a DB constraint so application code cannot bypass it.
2. **Conservation.** Across the whole ledger, all entry amounts sum to exactly zero. Assert after
   every test, including concurrency and crash tests.
3. **Immutability.** No `UPDATE` or `DELETE` on `entry` or `posting`, ever. Grants are revoked and
   a test proves the DB refuses.
4. **No mixed currencies** within a posting, period. One currency per account, one per
   posting, enforced by composite foreign keys. Explicit FX postings are a post-V1 stretch
   goal, not a V1 exception.
5. **Idempotency.** Replaying a posting with the same key creates no second set of entries and\
   returns the original posting.
6. **Concurrency.** N threads posting against one account produce a final balance exactly equal to
   the arithmetic sum. No lost updates.
7. **Overdraft policy is explicit** per account — permitted or rejected, never accidental.
8. **Crash safety.** A kill mid-posting leaves either all entries of that posting or none.

## Rules that protect the contract

- Money is **integer minor units** (`long` / `BigInteger`). A `double` or `float` anywhere in the
  money path is a defect, not a style preference.
- A balance is derived from entries. If a materialised balance column exists, it is written in the
  same transaction as the entries, and a test proves it always equals the derived value.
- Corrections are reversal postings. Never an edit.
- State the isolation level explicitly and say which anomaly it prevents. Show the test that would
  fail at a weaker level.
- Integration tests use Testcontainers with real Postgres. An in-memory DB does not enforce
  the constraints that make invariants 1 and 3 true, so testing against one proves nothing.

## Non-goals

Say these plainly in the README; do not let the code drift toward them.

- No payment provider integration, card authorisation, or settlement. This is the book of record.
- No multi-node consensus. One Postgres, one source of truth.
- No regulatory or compliance claim of any kind.
- No streaming pipeline. Kafka is peripheral here and belongs only in the outbox stretch goal.

## Comments and design notes

Explanatory comments are teaching material for the portfolio walkthrough. Preserve useful reasoning
when editing nearby code. If a comment is wrong, correct the fact rather than leaving it as
archaeology. Rejected alternatives and longer trade-off discussions belong in `docs/DESIGN.md`.
`TODO.md` contains only open work.

## Working with Muse

Commit+push is automatic, not a question: after making requested repo edits, commit and
push the touched files to `main` immediately, every time, without asking first and without
announcing the intent to ask. Never leave your own changes uncommitted for review. Always sweep
the user's in-progress work in the touched files into the same commit: one commit containing
both, never a commit that leaves their hunks behind.

## Required checks

```sh
./gradlew build
./gradlew test              # fast unit/domain tests, no Docker needed
./gradlew integrationTest   # Testcontainers Postgres, incl. the concurrency test for invariant 6
docker compose config --quiet
```
