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

- Signed amounts hide the side inside the number, so the DB can't check it.
- Mistakes can cancel out: flip the signs on both legs and the posting still totals zero. A withdrawal can change to a deposit and nothing notices.

2. A mutable balance column instead of computing balances from entries on read

- Two copies of the balance would need to stay in sync (see §3).

## 2. Integer minor units and overflow-safe aggregation

Amounts are integer minor units — cents, not dollars (10000 is 100.00).
Java carries them as `long` and Postgres as `bigint`. 
The API sends them as base-10 strings, so no client can round them. 

Totals use `BigInteger` and PG `sum(bigint)` -> text, so no overflows/rounds. 

- `PostingDraft` proves totals stay exact past `long` range
- `PostingBalanceTest` proves near-limit amounts commit exactly.

### Dropped Alternatives

1. JSON numbers for 64-bit amounts

- Past 2^53, common clients round JSON numbers.

2. Summing debit and credit totals with `long` 

- Entries near `Long.MAX` would overflow and wrap

## 3. Derived balances vs. a materialized projection

`GET .../balance` adds up the account's entries on every read. 
The alternative is a stored balance column, written in the same transaction as the entries. 
The journal would stay authoritative, with a standing proof that the stored value matches the derived one. 
That is stretch goal 1 in `TODO.md`. It waits on a benchmark showing derived reads are the bottleneck.

- `AccountRepository` proves the balance comes from entries in one statement.
- `AccountApiTest` proves the endpoint returns that value.
- `ConcurrencyTest` proves lost updates are impossible by structure, not by locking.

### Dropped Alternatives

1. A `balance` column updated alongside entries

- Two copies of the balance would need to stay in sync.
- Every hot account would gain a lost-update and skew surface.

## 4. JDBC vs. JPA for an append-only, SQL-constrained model

DB access is plain JDBC: `JdbcClient` for queries, `JdbcTemplate` for entry batches.
The schema is versioned SQL via Flyway. That is on purpose. The parts that matter are SQL
text, the transaction boundary, the commit-time trigger (§5), and the grants (§8). An ORM
would hide all four behind generated queries, and it brings update/cascade behavior the
ledger forbids.

- `AccountRepository` and `PostingRepository` prove the persistence boundary is explicit SQL.
- `DomainIsolationTest` proves the domain has no JDBC. No `ddl-auto`, H2, or entity
  annotation anywhere.

### Dropped Alternatives

1. Hibernate/JPA

- It hides the SQL, the transaction boundary, the trigger, and the grants.
- It brings update/cascade behavior the ledger forbids.

2. Spring Data REST, generated DDL

- The schema stays hand-reviewed SQL instead of generated output.

## 5. Deferred constraint triggers and the immutable declared count

A posting is several rows that are only valid together. A row CHECK sees only its own row,
so it cannot judge whether entries balance. A constraint trigger runs at commit time instead,
once all of the posting's rows are in. It checks the count against the declared `entry_count`,
the numbering is exactly `1..n`, at least two accounts take part, the sum is zero, DENY
accounts (see §6) are not overdrawn, and the reversal rules hold. The declared `entry_count`
never changes. That seals the posting: even a later balanced pair breaks the count and fails.
IDs default to `uuidv7()`, which keeps the primary-key index in insertion order. The table
itself stays unordered.

- `PostingBalanceTest` proves balanced postings commit and unbalanced ones fail.
- `PostingClosureTest` proves nothing appends after commit.
- `scripts/demo.sh` step 6 proves the seal on a live DB.

### Dropped Alternatives

1. Row CHECKs as cross-row proof

- A row CHECK cannot see sibling rows. Only the commit-time trigger sees the whole posting.

2. Insert-only tables without the count seal

- Without the declared count a posting stays open. A later balanced pair rewrites history and
  nothing fails.

## 6. Serializable isolation, write skew, retries, hot accounts

Each account either rejects overdrafts (DENY) or allows them (ALLOW). The danger is write
skew. Picture a DENY account holding 10,000. Two transactions each read the balance. Each
approves spending 8,000 through its own new rows. At REPEATABLE READ both commit, leaving
−6,000. Neither saw the other's rows, so no check fired. A test demonstrates that failure.
Production posts at SERIALIZABLE, so PostgreSQL aborts one attempt (SQLSTATE 40001). The
whole transaction, decision included, retries from scratch with full-jitter backoff. After
five attempts it gives up with an explicit 503 and a Retry-After, so the caller retries
later. No JVM locks: they cannot protect two app instances from each other.

- `ConcurrencyTest` proves the hole: two approvals commit at −6,000.
- `ConcurrencyTest` proves the fix: one 201, one 409, with a recorded retry
  (`AttemptRecorder`).
- `PostingService` proves the retry protocol: whole-transaction retries, five attempts, then 503.

### Dropped Alternatives

1. READ COMMITTED + `SELECT ... FOR UPDATE`

- Only the documented fallback if serialization pressure ever measures too high.

2. Unbounded retries

- Retries stop after five, bounded at ~600ms worst case, instead of running forever.

3. Async submit — answer HTTP 202 at once, queue the posting, have the client poll

- It would free the request thread during retries. But commits could land out of order, and a
  redelivered request could post twice: §7's key cannot collapse two queued copies into one
  winner.
- So retries stay synchronous: the client retries on 503, and the service retries the commit.

## 7. Semantic idempotency and the lost-response case

Every post request carries a client-supplied idempotency key with a UNIQUE constraint
behind it. The key decides who wins, not application code. If two requests race with the same
key, one insert wins. The loser rolls back, reads the winner outside its own transaction, and
compares a versioned SHA-256 fingerprint over (kind, description, instant, ordered entries).
Same body: the loser replays the original (HTTP 200 + a replay flag). Different body: 409
conflict. Only committed postings consume keys. So when a response is lost on the wire, the
client's retry finds the original instead of posting twice.

- `PostingFingerprint` proves equal bodies hash equal and different bodies hash different
  (`PostingFingerprintTest`).
- `PostingService` proves losers read the winner instead of doubling it.
- `PostingApiTest` proves replay returns the original (HTTP 200 + flag), conflict returns
  409, and a 20-way race commits exactly once (`scripts/demo.sh` steps 3–4).

### Dropped Alternatives

1. `ON CONFLICT DO UPDATE`

- Postings are never updated. The key elects a winner; it never merges one.

2. Same-key/different-body returning the original

- That would return an unrelated posting for a different body. It conflicts with 409
  instead.

## 8. Migration owner vs. runtime role and the threat boundary

Two DB roles split the boundary. `ledger_owner` runs schema migrations and nothing
else. The app connects as `ledger_app`. It gets CONNECT, schema USAGE, SELECT, and INSERTs on
an explicit column list (generated IDs and timestamps excluded). It is explicitly revoked
UPDATE, DELETE, and TRUNCATE. As a backstop, triggers on the journal tables reject any
UPDATE/DELETE even if someone mis-issues a grant (proven via the owner path). Two honest
limits: the owner role can dismantle all of this, and overdraft safety assumes every writer
follows the SERIALIZABLE protocol (§6), which a raw-SQL caller can sidestep. Credentials are
not a public API.

- `ImmutabilityTest` proves the DB refuses UPDATE/DELETE on journal tables, even via
  the owner path.
- `RuntimeRoleTest` proves `ledger_app` can only do its listed grants.
- `spring.flyway.enabled: false` plus the one-shot Compose `migrate` proves the app never
  migrates. `V1__journal_schema.sql` and `docker/postgres/00-roles.sh` hold the grants.

### Dropped Alternatives

1. One superuser identity everywhere

- One leaked credential would dismantle everything. Migration and runtime stay separate.

2. App-run migrations

- The runtime role would need DDL. Instead `ledger_owner` migrates once.

## 9. Exact reversal rather than edit/delete

A correction never edits history. It appends a new posting whose entries mirror the
original one-for-one: same account, amount, and currency, opposite side. The server derives
those inverse entries itself. Clients only name the posting to reverse. The commit-time
trigger (§5) checks each inverse entry against its original. One unique slot permits a single
V1 reversal per posting. Reversing a reversal is refused by kind. Overdraft policy still
applies, so undoing spent history can itself be refused.

- `ReversalApiTest` proves corrections are server-derived inverse postings (7 tests). A
  double reversal is refused, and reversal-of-reversal is refused by kind.
- `PostingService` proves each inverse entry matches (`scripts/demo.sh` step 9).

### Dropped Alternatives

1. Edits, deletes, mutable status flags

- History would be rewritable. Instead a correction is a new posting and the old one stands.

2. Client-supplied reversal entries

- Clients cannot label arbitrary postings as reversals. The server derives the inverse
  entries, and overdraft policy still applies.

## 10. Why FX, Kafka, payments, Kubernetes, and compliance are outside V1

Each of the five would add an invariant this project cannot yet prove. FX needs a rounding
policy. Kafka needs delivery semantics. Payments need authorization correctness. Kubernetes
needs multi-node truth. Compliance needs legal claims. The README non-goals say so plainly.
The only way back in is the stretch list in TODO.md: one at a time, each with its own
invariant and test.

- `README.md` non-goals prove the boundary is stated plainly.
- `AGENTS.md` proves the contract defended instead: eight invariants, each a test.
- `TODO.md` proves the only way back in: the stretch list.

### Dropped Alternatives

1. FX

- It needs a rounding policy the project cannot yet prove.

2. Kafka

- It needs delivery semantics the project cannot yet prove.

3. Payments

- They need authorization correctness the project cannot yet prove.

4. Kubernetes

- It needs multi-node truth the project cannot yet prove.

5. Compliance

- It needs legal claims, which the project never makes.

## 11. One entries table for all accounts

Tables define kinds, not owners. Accounts, postings, and entries get one table each,
shared by every account. Opening an account inserts a row. It never runs DDL.

- `V1__journal_schema.sql` proves three tables cover every account.
- `v_conservation` proves the whole-ledger sum reads one table, never N.

### Dropped Alternatives

1. Per-account/per-currency entry tables

- Opening an account would mean running DDL.
- The ledger sum would scatter across N tables.
- Every constraint would need one copy per table.
- (See §3 for the balances-table version of the same mistake.)

## 12. Hexagonal package layout: use cases in application, rules in domain, adapters at the edges

Each business area (accounts, postings) is split four ways. `api` takes HTTP in and
translates it. `application` runs the use case: transactions, idempotency, retries. `domain`
holds the rules with no framework types. `persistence` speaks JDBC on the way out. All
decisions live in `application` plus `domain`. Controllers and repositories only translate at
the edges.

- `PostingService` vs. `PostingDraft` proves the split: running the use case vs. stating the
  rules.
- `DomainIsolationTest` proves the domain has no Spring, JDBC, or validation. No framework
  stereotype inside any `domain` package.

### Dropped Alternatives

1. Layer-by-kind packages (`controllers`, `services`, `repositories`)

- One feature would scatter across three directories. Here each area keeps its four parts
  together.

2. Framework types leaking into `domain`

- The rules would depend on the framework. They stay independent instead.
