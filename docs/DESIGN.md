# DESIGN

## 1. Signed journal arithmetic vs. normal-side account balances

Entries store an unsigned amount plus a side (debit/credit).
Every posting and the whole ledger must have conservation (must total zero).

Account balances come from adding up its entries.
The normal side of a balance is whatever side makes the value go up.
(ASSET/EXPENSE: debits − credits; everything else: credits − debits).

Two SQL views (v_account_balance, v_conservation) and one table, no drift is possible.

- `PostingBalanceTest` proves balanced postings commit and unbalanced ones fail at commit.

- `OverdraftTest` proves DENY balances hold at commit (exact-to-zero allowed, ALLOW may go negative).

### Dropped Alternatives

1. Storing a signed amount per entry instead of an unsigned amount with a side

- A signed amount merges side and magnitude into one number: +100 might be a debit of 100 or a credit of −100. The schema records the same value either way, so no check — not "debits equal credits", not the normal-side balance math — has anything left to grip.
- A withdrawal's legs negated (+100/−100 booked as −100/+100) still totals zero: the books now describe a deposit, and no arithmetic check notices.
- Two mistakes canceling out: the sign flipped on one leg, an equal-and-opposite error on another — the posting totals zero with both entries wrong.

2. A mutable balance column instead of computing balances from entries on read

- A second source of truth to keep in sync with the journal (see §3).

## 2. Integer minor units and overflow-safe aggregation

Money is `long` minor units in Java and `bigint` in Postgres; the wire form is a base-10 string.
Sums use `BigInteger`, and Postgres `sum(bigint)` is read as scale-zero text into `BigInteger`.
A `double`, `float`, or amount-bearing `BigDecimal` anywhere in the money path is a defect.

- `PostingDraft` proves posting totals are judged in `BigInteger`, exactly past `long` range
  (`PostingDraftTest.totalsBeyondLongRangeStillJudgeExactly`).
- `PostingBalanceTest.hugeAmountsNearLongLimitCommitExactly` proves near-limit amounts commit
  exactly at the database.

### Dropped Alternatives

1. JSON numbers for 64-bit amounts

- Common clients lose precision past 2^53, so the wire form is a base-10 string parsed by
  `EntryAmount.parse`.

2. `long` accumulation

- Wrap-around can bless an unbalanced posting as balanced; sums stay in `BigInteger`.

## 3. Derived balances vs. a materialized projection

V1 has no balance column: `GET .../balance` recomputes from entries in one statement. A
materialized projection is stretch goal 1, gated on a benchmark proving derived reads are the
bottleneck — and even then the journal stays authoritative with a continuous equality proof.

- `AccountRepository.balanceOf` proves the balance derives from entries in one statement.
- `AccountApiTest.balanceDerivesFromEntries` proves the endpoint returns that derived value.
- `ConcurrencyTest.concurrentPostingsSumExactly` proves no lost update is structural, not locked.

### Dropped Alternatives

1. A `balance` column updated alongside entries

- A second source of truth to keep in sync with the journal.
- A lost-update and skew surface on every hot account.

## 4. JDBC vs. JPA for an append-only, SQL-constrained model

`JdbcClient` for queries, `JdbcTemplate` for entry batches, Flyway for schema. The valuable code
is SQL text, the transaction boundary, the trigger, and the grants — an ORM would hide all four
and import mutation/cascade semantics the ledger forbids.

- `AccountRepository` and `PostingRepository` prove the persistence boundary is explicit SQL:
  `JdbcClient` for queries, `JdbcTemplate` for entry batches.
- `DomainIsolationTest` proves the domain has no JDBC; no `ddl-auto`, H2, or entity annotation
  anywhere.

### Dropped Alternatives

1. Hibernate/JPA

- Hides the SQL text, the transaction boundary, the trigger, and the grants.
- Imports mutation/cascade semantics the ledger forbids.

2. Spring Data REST, generated DDL

- The schema stays reviewed SQL in Flyway instead of generated output.

## 5. Deferred constraint triggers and the immutable declared count

A row CHECK cannot see sibling rows, so posting integrity is a `CONSTRAINT TRIGGER ... DEFERRABLE
INITIALLY DEFERRED` firing from both header and entry inserts and judging the commit-time state:
count match, exact `1..n` entries, ≥2 accounts, zero signed sum, DENY balances, reversal rules. The
immutable declared `entry_count` seals the posting: even a later balanced pair breaks the count.
IDs default to `uuidv7()`, which keeps the PK index append-ordered (the heap itself stays
unordered — an index is not the table).

- `PostingBalanceTest` proves balanced postings commit and unbalanced ones fail at commit.
- `PostingClosureTest` proves the count seal holds: nothing appends after commit.
- `scripts/demo.sh` step 6 proves the seal end to end on a live database.

### Dropped Alternatives

1. Row CHECKs as cross-row proof

- A row CHECK cannot see sibling rows; only the commit-time trigger sees the whole posting.

2. Insert-only tables without the count seal

- Without the declared count a posting stays appendable: a later balanced pair rewrites history
  and nothing fails.

## 6. Serializable isolation, write skew, retries, hot accounts

The anomaly is overdraft write skew: two transactions read 10,000, each approves 8,000 through
disjoint inserts, both commit at REPEATABLE READ leaving −6,000 (demonstrated, not theorized).
Production posts at SERIALIZABLE, so PostgreSQL aborts one attempt with 40001 and the whole
transaction — decision included — retries with full-jitter backoff, stopping after five with an
explicit 503. No JVM locks (they cannot protect two instances).

- `ConcurrencyTest.repeatableReadLosesTheOverdraftRace` proves the hole: REPEATABLE READ
  commits two overdraft approvals at −6,000.
- `ConcurrencyTest.overdraftRaceCommitsOneAndRejectsOne` proves the fix: one 201, one 409,
  with a recorded serialization retry (`AttemptRecorder`).
- `PostingService` proves the retry protocol: the whole transaction — decision included —
  retries with full-jitter backoff, stopping after five with an explicit 503.

### Dropped Alternatives

1. READ COMMITTED + `SELECT ... FOR UPDATE`

- Kept only as the documented tuning if serialization pressure ever measures too high.

2. Unbounded retries

- Retry load stays bounded at ~600ms worst case instead of retrying forever.

3. Async submit — answer HTTP 202 at once, queue the posting, have the client poll

- Frees the request thread during retries, but buys lasting hazards: out-of-order commits,
  redelivery doubles, and §7's key, which rediscovers one request's winner, cannot collapse two
  queued copies of the same request.
- The live shapes stay client-side retry (the 503's Retry-After) and server-side retry (above).

## 7. Semantic idempotency and the lost-response case

The unique key — not check-then-insert — elects the winner: concurrent inserts race, one wins,
losers roll back, read the winner outside their transaction, and compare a versioned SHA-256 over
(kind, description, instant, ordered entries). Equal replays the original (HTTP 200 + flag);
different bodies conflict (409). Only committed postings consume keys, so a retry after a lost
response discovers the original instead of doubling it.

- `PostingFingerprint` proves equal bodies hash equal and different bodies hash different
  (`PostingFingerprintTest`), so replay and conflict are exact comparisons.
- `PostingService.resolveIdempotencyRace` proves losers of the key race read the winner outside
  their transaction instead of doubling it.
- `PostingApiTest` proves replay returns the original (HTTP 200 + flag), conflict returns 409,
  and a 20-way replay race commits exactly once (`scripts/demo.sh` steps 3–4).

### Dropped Alternatives

1. `ON CONFLICT DO UPDATE`

- Postings are never updated; the key elects a winner, it never merges one.

2. Same-key/different-body returning the original

- Returns an unrelated posting for a different body; instead the body comparison conflicts
  with 409.

## 8. Migration owner vs. runtime role and the threat boundary

`ledger_owner` migrates; `ledger_app` gets CONNECT, schema USAGE, SELECT, and column-list
INSERTs that exclude generated IDs/timestamps — and explicit revokes of UPDATE/DELETE/TRUNCATE.
Immutable-row triggers backstop a mis-issued grant (proven via the owner path). Stated honestly:
an owner can dismantle all of this; concurrent-overdraft safety further assumes the SERIALIZABLE
protocol, which a raw-SQL caller can sidestep. Credentials are not a public API.

- `ImmutabilityTest` proves the database refuses UPDATE/DELETE on journal tables, even via
  the owner path that backstops a mis-issued grant.
- `RuntimeRoleTest` proves `ledger_app` can only do its listed grants: CONNECT, schema USAGE,
  SELECT, and column-list INSERTs excluding generated IDs/timestamps.
- `spring.flyway.enabled: false` plus the one-shot Compose `migrate` proves the app never
  migrates; `V1__journal_schema.sql` and `docker/postgres/00-roles.sh` hold the grants.

### Dropped Alternatives

1. One superuser identity everywhere

- No threat boundary between migration and runtime; one leaked credential dismantles everything.

2. App-run migrations

- The runtime role would need DDL; instead `ledger_owner` migrates once and the app holds only
  runtime creds.

## 9. Exact reversal rather than edit/delete

Corrections are new postings with server-derived inverse entries, validated entry-for-entry at commit
(same account/amount/currency, opposite side). One unique slot permits a single V1 reversal;
reversing a reversal is refused by kind; clients cannot label arbitrary postings as reversals;
overdraft policy still applies, so spent history can refuse to be undone.

- `ReversalApiTest` proves corrections are server-derived inverse postings (7 tests), a
  double reversal is refused via the unique slot, and reversal-of-reversal is refused by kind.
- `PostingService.reverse` with the V3 reversal block proves each inverse entry matches
  account/amount/currency with the opposite side (`scripts/demo.sh` step 9).

### Dropped Alternatives

1. Edits, deletes, mutable status flags

- History would be rewritable; instead a correction is a new posting and the old one stands.

2. Client-supplied reversal entries

- Clients cannot label arbitrary postings as reversals; the server derives the inverse entries,
  and overdraft policy still applies, so spent history can refuse to be undone.

## 10. Why FX, Kafka, payments, Kubernetes, and compliance are outside V1

Each would add an invariant the project cannot yet prove (rounding policy, delivery semantics,
authorization correctness, multi-node truth, legal claims). The README non-goals say so plainly,
and the only sanctioned re-entry is the stretch list in TODO.md — one at a time, each
with its own invariant/test row.

- `README.md` non-goals prove the boundary is stated plainly, not drifted around.
- `AGENTS.md` proves the contract this project defends instead: eight invariants, each a test.
- `TODO.md` proves the only sanctioned re-entry: the stretch list, one at a time, each with its
  own invariant/test row.

### Dropped Alternatives

1. FX

- Would add a rounding-policy invariant the project cannot yet prove.

2. Kafka

- Would add delivery-semantics invariants the project cannot yet prove.

3. Payments

- Would add authorization-correctness invariants the project cannot yet prove.

4. Kubernetes

- Would add multi-node-truth invariants the project cannot yet prove.

5. Compliance

- Would add legal claims of any kind, which the project never makes.

## 11. One entries table for all accounts

Tables define kinds, not owners: accounts, postings, and entries each get one table, and a new
account arrives as a row, never as DDL. A per-account (or per-currency) split would turn account
creation into schema migration, scatter the conservation sum across N tables, and duplicate
every constraint once per table.

- `V1__journal_schema.sql` proves three journal tables cover every account: a new account
  arrives as a row, never as DDL.
- `v_conservation` proves the whole-ledger sum reads one table, never N.

### Dropped Alternatives

1. Per-account/per-currency entry tables

- Account creation would become schema migration.
- The conservation sum would scatter across N tables.
- Every constraint would duplicate once per table.
- (See also §3 for the balances-table version of the same second-source-of-truth mistake.)

## 12. Hexagonal package layout: use cases in application, rules in domain, adapters at the edges

Each area is split four ways: `api` (HTTP in), `application` (use-case orchestration:
transactions, idempotency, retries), `domain` (rules, framework-free), `persistence` (JDBC out).
Controllers and repositories translate; all decisions live in `application` plus `domain`.

- `PostingService` vs. `PostingDraft` proves the split: orchestration in `application`,
  rules in `domain`.
- `DomainIsolationTest` proves the domain has no Spring, JDBC, or validation; no framework
  stereotype inside any `domain` package.

### Dropped Alternatives

1. Layer-by-kind packages (`controllers`, `services`, `repositories`)

- Scatters one feature across three directories; here each area keeps its four parts together.

2. Framework types leaking into `domain`

- Rules must stay framework-free so the accounting contract outlives the stack.
