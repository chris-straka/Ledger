# DESIGN.md — ledger decision records

Each record names the choice, the rejected alternative, and the executable proof. Paths are
relative to the repo root.

## 1. Signed journal arithmetic vs. normal-side account balances

Entries store an unsigned amount plus a side; the journal sums DEBIT as + and CREDIT as −, so
every posting and the whole ledger must total zero. Account balances are the same entries read
through the account type's normal side (ASSET/EXPENSE: debits − credits; everything else:
credits − debits). Two views, one row set — nothing can drift.

- Rejected: storing a signed amount per entry (hides side errors) or a mutable balance column
  (a second source of truth).
- Proof: `src/main/resources/db/migration/V1__journal_schema.sql` (`v_account_balance`,
  `v_conservation`); `PostingBalanceTest`, `OverdraftTest`.

## 2. Integer minor units and overflow-safe aggregation

Money is `long` minor units in Java and `bigint` in Postgres; the wire form is a base-10 string.
Sums use `BigInteger`, and Postgres `sum(bigint)` is read as scale-zero text into `BigInteger`.
A `double`, `float`, or amount-bearing `BigDecimal` anywhere in the money path is a defect.

- Rejected: JSON numbers for 64-bit amounts (precision loss in common clients) and `long`
  accumulation (wrap-around can bless an unbalanced posting).
- Proof: `EntryAmount.parse`, `PostingDraft` (BigInteger totals);
  `PostingDraftTest.totalsBeyondLongRangeStillJudgeExactly`;
  `PostingBalanceTest.hugeAmountsNearLongLimitCommitExactly`.

## 3. Derived balances vs. a materialized projection

V1 has no balance column: `GET .../balance` recomputes from entries in one statement. A
materialized projection is stretch goal 1, gated on a benchmark proving derived reads are the
bottleneck — and even then the journal stays authoritative with a continuous equality proof.

- Rejected: a `balance` column updated alongside entries (lost-update and skew surface).
- Proof: `AccountRepository.balanceOf`; `AccountApiTest.balanceDerivesFromEntries`;
  `ConcurrencyTest.concurrentPostingsSumExactly` (no lost update is structural, not locked).

## 4. JDBC vs. JPA for an append-only, SQL-constrained model

`JdbcClient` for queries, `JdbcTemplate` for entry batches, Flyway for schema. The valuable code
is SQL text, the transaction boundary, the trigger, and the grants — an ORM would hide all four
and import mutation/cascade semantics the ledger forbids.

- Rejected: Hibernate/JPA, Spring Data REST, generated DDL.
- Proof: `AccountRepository`, `PostingRepository`; `DomainIsolationTest` (domain has no JDBC);
  no `ddl-auto`, H2, or entity annotation anywhere.

## 5. Deferred constraint triggers and the immutable declared count

A row CHECK cannot see sibling rows, so posting integrity is a `CONSTRAINT TRIGGER ... DEFERRABLE
INITIALLY DEFERRED` firing from both header and entry inserts and judging the commit-time state:
count match, exact `1..n` entries, ≥2 accounts, zero signed sum, DENY balances, reversal rules. The
immutable declared `entry_count` seals the posting: even a later balanced pair breaks the count.

- Rejected: row CHECKs as cross-row proof; insert-only tables without the count seal (appendable).
- Proof: `V1__journal_schema.sql` (deferred triggers); `PostingBalanceTest`, `PostingClosureTest`;
  `scripts/demo.sh` step 6. IDs default to `uuidv7()`, which keeps the PK index
  append-ordered (the heap itself stays unordered — an index is not the table).

## 6. Serializable isolation, write skew, retries, hot accounts

The anomaly is overdraft write skew: two transactions read 10,000, each approves 8,000 through
disjoint inserts, both commit at REPEATABLE READ leaving −6,000 (demonstrated, not theorized).
Production posts at SERIALIZABLE, so PostgreSQL aborts one attempt with 40001 and the whole
transaction — decision included — retries with full-jitter backoff, stopping after five with an
explicit 503. No JVM locks (they cannot protect two instances).

- Rejected: READ COMMITTED + `SELECT ... FOR UPDATE` (documented as the likely tuning if
  serialization pressure ever measures too high) and unbounded retries.
- Rejected: a third retry shape — async submit, where the API answers HTTP 202 at once,
  queues the posting, and has the client poll for the outcome. The two live options are
  client-side retry-after and the server-side synchronous retry above; async would free the
  request thread during backoff, but the wait it avoids is bounded at ~600ms worst case, while
  the queue buys lasting hazards: out-of-order commits, redelivery doubles, and §7's key, which
  rediscovers one request's winner, cannot collapse two queued copies of the same request.
- Proof: `PostingService`; `ConcurrencyTest.repeatableReadLosesTheOverdraftRace` (shows the hole)
  and `overdraftRaceCommitsOneAndRejectsOne` (shows the fix, with a recorded retry);
  `AttemptRecorder`.

## 7. Semantic idempotency and the lost-response case

The unique key — not check-then-insert — elects the winner: concurrent inserts race, one wins,
losers roll back, read the winner outside their transaction, and compare a versioned SHA-256 over
(kind, description, instant, ordered entries). Equal replays the original (HTTP 200 + flag);
different bodies conflict (409). Only committed postings consume keys, so a retry after a lost
response discovers the original instead of doubling it.

- Rejected: `ON CONFLICT DO UPDATE` (postings are never updated) and same-key/different-body
  returning an unrelated original.
- Proof: `PostingFingerprint`, `PostingService.resolveIdempotencyRace`;
  `PostingApiTest` replay/conflict tests, 20-way replay race; `scripts/demo.sh` steps 3–4.

## 8. Migration owner vs. runtime role and the threat boundary

`ledger_owner` migrates; `ledger_app` gets CONNECT, schema USAGE, SELECT, and column-list
INSERTs that exclude generated IDs/timestamps — and explicit revokes of UPDATE/DELETE/TRUNCATE.
Immutable-row triggers backstop a mis-issued grant (proven via the owner path). Stated honestly:
an owner can dismantle all of this; concurrent-overdraft safety further assumes the SERIALIZABLE
protocol, which a raw-SQL caller can sidestep. Credentials are not a public API.

- Rejected: one superuser identity everywhere; app-run migrations.
- Proof: `V1__journal_schema.sql` (grants), `docker/postgres/00-roles.sh`, one-shot Compose `migrate`,
  `spring.flyway.enabled: false`; `ImmutabilityTest`, `RuntimeRoleTest`.

## 9. Exact reversal rather than edit/delete

Corrections are new postings with server-derived inverse entries, validated entry-for-entry at commit
(same account/amount/currency, opposite side). One unique slot permits a single V1 reversal;
reversing a reversal is refused by kind; clients cannot label arbitrary postings as reversals;
overdraft policy still applies, so spent history can refuse to be undone.

- Rejected: edits, deletes, mutable status flags, client-supplied reversal entries.
- Proof: `PostingService.reverse`, V3 reversal block; `ReversalApiTest` (7 tests);
  `scripts/demo.sh` step 9.

## 10. Why FX, Kafka, payments, Kubernetes, and compliance are outside V1

Each would add an invariant the project cannot yet prove (rounding policy, delivery semantics,
authorization correctness, multi-node truth, legal claims). The README non-goals say so plainly,
and the only sanctioned re-entry is the stretch list in TODO.md — one at a time, each
with its own invariant/test row.

- Rejected: breadth that the code cannot defend in an interview.
- Proof: `README.md` non-goals; `AGENTS.md` contract; this file's silence on all five.

## 11. One entries table for all accounts

Tables define kinds, not owners: accounts, postings, and entries each get one table, and a new
account arrives as a row, never as DDL. A per-account (or per-currency) split would turn account
creation into schema migration, scatter the conservation sum across N tables, and duplicate
every constraint once per table.

- Rejected: per-account/per-currency entry tables (see also 3 for the balances-table version
  of the same second-source-of-truth mistake).
- Proof: `V1__journal_schema.sql` (three journal tables); `v_conservation` sums one table.

## 12. Hexagonal package layout: use cases in application, rules in domain, adapters at the edges

Each area is split four ways: `api` (HTTP in), `application` (use-case orchestration:
transactions, idempotency, retries), `domain` (rules, framework-free), `persistence` (JDBC out).
Controllers and repositories translate; all decisions live in `application` plus `domain`.

- Rejected: layer-by-kind packages (`controllers`, `services`, `repositories`) that scatter one
  feature across three directories, and framework types leaking into `domain`.
- Proof: `PostingService` (orchestration) vs. `PostingDraft` (rules); `DomainIsolationTest`
  (domain has no Spring, JDBC, or validation); no framework stereotype inside any `domain`
  package.
