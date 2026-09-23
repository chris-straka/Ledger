# DESIGN

## 1. Why raw SQL?

An ORM has managed entities, automatic updates, and cascades by default. 
I needed more control to set the tx boundary, BEFORE commit triggers and grants.
Bringing in an ORM to disable the features it comes with felt like a bad choice.

## 2. Why UUIDv7?

I could've used random UUIDs but they scatter writes across the index.
v7 starts with a timestamp so new rows land at the end.
Same uniqueness, cheaper inserts.

## 3. Why SERIALIZABLE?

SERIALIZABLE stops two transactions deciding on the same stale read.

Say the balance is 500 and two 500 withdrawals come in.
Without it, both read 500 and both apply. The account lands at −500.
SERIALIZABLE aborts one instead. The loser retries, sees the fresh balance, and gets refused.

## 4. Why idempotency keys?

A retried request must never post twice.
The key decides who won: same body replays the original, different body gets a 409.
Only committed postings consume keys, so a lost response is answered by retrying the same key.

- `PostingFingerprint` proves equal bodies hash equal and different bodies hash different
  (`PostingFingerprintTest`).
- `PostingService` proves losers read the winner instead of doubling it.
- `PostingApiTest` proves replay returns the original (HTTP 200 + flag), conflict returns
  409, and a 20-way race commits exactly once (`scripts/demo.sh` steps 3–4).

### Dropped Alternatives

1. `ON CONFLICT DO UPDATE`

- Postings are never updated. The key elects a winner; it never merges one.

2. Same key + different body returning the original

- That would hand back someone else's posting. 409 instead.

## 5. Why two database roles?

Even if the app goes rogue, it can't rewrite history. It lacks the rights.
The owner migrates and nothing else. The app can only read and insert listed columns.
Triggers backstop the grants. Two honest limits stay: the owner can dismantle it all, and raw SQL can skip the §3 protocol.

- `ImmutabilityTest` proves the DB refuses UPDATE/DELETE on journal tables, even via
  the owner path.
- `RuntimeRoleTest` proves `ledger_app` can only do its listed grants.
- `spring.flyway.enabled: false` plus the one-shot Compose `migrate` proves the app never
  migrates. `V1__journal_schema.sql` and `docker/postgres/00-roles.sh` hold the grants.

### Dropped Alternatives

1. One superuser everywhere

- One leaked credential dismantles everything. Migration and runtime stay separate.

2. App-run migrations

- The runtime role would need DDL (the right to change table shapes). It doesn't get it. The owner migrates once.

## 6. Why reversals?

A correction must never rewrite history.
So it appends a mirror posting: same lines, opposite sides, built by the server.
One per posting, overdraft rules still apply, reversing a reversal is refused.

- `ReversalApiTest` proves corrections are server-derived inverse postings (7 tests). A
  double reversal is refused, and reversal-of-reversal is refused by kind.
- `PostingService` proves each inverse entry matches (`scripts/demo.sh` step 9).

### Dropped Alternatives

1. Edits, deletes, status flags

- History would be rewritable. A correction is a new posting; the old one stands.

2. Client-supplied reversal entries

- Clients can't just label anything a reversal. The server builds the mirror, and overdraft rules still apply.

## 7. Why is all that other stuff out?

Each would need a rule I can't prove yet: rounding, delivery, authorization, multi-node truth, legal claims.
The README says so plainly.
The way back in is one stretch goal at a time, each with its own rule and test.

- `README.md` non-goals prove the boundary is stated plainly.
- `AGENTS.md` proves the contract defended instead: eight invariants, each a test.
- `TODO.md` proves the only way back in: the stretch list.

## 8. Why one entries table?

Opening an account must not touch the schema.
One table each for accounts, postings, entries, shared by everyone.
Per-account tables would mean DDL per account and the ledger sum scattered across N tables.

- `V1__journal_schema.sql` proves three tables cover every account.
- `v_conservation` proves the whole-ledger sum reads one table, never N.

### Dropped Alternatives

1. Per-account/per-currency entry tables

- Opening an account would mean running DDL.
- The ledger sum would scatter across N tables.
- Every constraint would need one copy per table.

## 9. Why four parts per area?

The rules stay testable with no framework, and each feature lives in one place.
Application and domain decide; the edges only translate.
Layer-by-kind would scatter one feature across three directories.

- `PostingService` vs. `PostingDraft` proves the split: running the use case vs. stating the
  rules.
- `DomainIsolationTest` proves the domain has no Spring, JDBC, or validation. No framework
  stereotype inside any `domain` package.

### Dropped Alternatives

1. Layer-by-kind packages (`controllers`, `services`, `repositories`)

- One feature scatters across three directories. Here each area keeps its four parts together.

2. Framework types leaking into `domain`

- The rules would depend on the framework. They stay independent instead.
