# DESIGN

How this ledger works and why it is shaped this way. Each section states one decision, the
test that proves it holds, and the alternative that was dropped. Three terms carry the whole
doc: a **posting** is a small set of **entries** that moves money, an **entry** records one leg
(debit or credit) against one account, and the **journal** is the append-only store of all of
them. The one rule above all others: every posting must balance (debits equal credits), so the
whole ledger always totals zero.

Worked example (from `scripts/demo.sh`): opening capital of 10,000 is one posting with two
entries — cash DEBIT 10000, capital CREDIT 10000. Debits equal credits, so it commits; cash
then reads 10000 and capital reads 10000. Every section below defends one part of that flow.

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

Amounts are integer minor units — cents, not dollars: 10000 means 100.00. Java carries them
as `long`, Postgres as `bigint`, and the API wire form is a base-10 string (so no client can
round them). Totals are summed in `BigInteger`, and Postgres `sum(bigint)` is read back as
scale-zero text into `BigInteger`, so nothing overflows or rounds at any step. A `double`,
`float`, or amount-bearing `BigDecimal` anywhere in the money path is a defect.

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

V1 stores no balance anywhere: `GET .../balance` adds up the account's entries in a single
SQL statement on every read. A stored (materialized) balance is stretch goal 1 in `TODO.md`,
gated on a benchmark proving these derived reads are the bottleneck — and even then the
journal stays authoritative, with a standing proof that the stored value always equals the
derived one.

- `AccountRepository.balanceOf` proves the balance derives from entries in one statement.
- `AccountApiTest.balanceDerivesFromEntries` proves the endpoint returns that derived value.
- `ConcurrencyTest.concurrentPostingsSumExactly` proves no lost update is structural, not locked.

### Dropped Alternatives

1. A `balance` column updated alongside entries

- A second source of truth to keep in sync with the journal.
- A lost-update and skew surface on every hot account.

## 4. JDBC vs. JPA for an append-only, SQL-constrained model

Database access is plain JDBC (`JdbcClient` for queries, `JdbcTemplate` for entry batches)
and the schema is versioned SQL via Flyway. That is deliberate: the load-bearing parts of this
system are SQL text, the transaction boundary, the commit-time trigger (§5), and the grants
(§8) — an ORM would hide all four behind generated queries, and it would import update/cascade
semantics the ledger forbids.

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

A posting is several rows that are only valid together, but a row CHECK can only see its own
row — it cannot judge "do these entries balance?". So integrity is enforced by a constraint
trigger that runs at commit time, after all of the posting's rows are in: it checks the entry
count matches the declared `entry_count`, the entries are numbered exactly `1..n`, at least
two accounts take part, the signed sum is zero, DENY accounts are not overdrawn, and reversal
rules hold. The declared `entry_count` is immutable, which seals the posting shut: even a
later, perfectly balanced pair of entries breaks the count and fails. IDs default to
`uuidv7()`, which keeps the primary-key index append-ordered (the heap itself stays unordered
— an index is not the table).

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

The anomaly this section exists for is overdraft write skew. Picture a DENY account holding
10,000: two transactions each read the balance, each approves spending 8,000 through its own
new rows, and at REPEATABLE READ both commit — leaving −6,000. Neither transaction saw the
other's rows, so no check fired. That failure is demonstrated by a test, not theorized.
Production posts at SERIALIZABLE, so PostgreSQL aborts one of the two attempts (SQLSTATE
40001) and the whole transaction — the overdraft decision included — retries from scratch
with full-jitter backoff, giving up after five attempts with an explicit 503 (with a
Retry-After, so the caller retries later). No JVM locks: they cannot protect two app
instances from each other.

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

Every post request carries a client-supplied idempotency key with a UNIQUE constraint behind
it — the key, not application code, decides who wins. If two requests race with the same key,
one insert wins and the loser rolls back, reads the winner outside its own transaction, and
compares a versioned SHA-256 fingerprint over (kind, description, instant, ordered entries).
Same body: the loser replays the original (HTTP 200 + a replay flag). Different body: 409
conflict. Only committed postings consume keys, so when a response is lost on the wire, the
client's retry finds the original posting instead of posting twice.

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

Two database roles split the threat boundary. `ledger_owner` runs schema migrations and
nothing else. The app connects as `ledger_app`, which gets only CONNECT, schema USAGE, SELECT,
and INSERTs on an explicit column list (generated IDs and timestamps excluded) — plus explicit
revokes of UPDATE, DELETE, and TRUNCATE. As a backstop, triggers on the journal tables reject
any UPDATE/DELETE even if someone mis-issues a grant (proven via the owner path). Stated
honestly: the owner role can dismantle all of this, and overdraft safety further assumes every
writer follows the SERIALIZABLE protocol (§6), which a raw-SQL caller can sidestep. Credentials
are not a public API.

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

A correction never edits history — it appends a new posting whose entries mirror the
original one-for-one (same account, amount, and currency, opposite side), and the server
derives those inverse entries itself; clients only name the posting to reverse. The commit-time
trigger (§5) validates each inverse entry against its original. One unique slot permits a
single V1 reversal per posting, reversing a reversal is refused by kind, and overdraft policy
still applies — so undoing spent history can itself be refused.

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

Each of the five would add an invariant this project cannot yet prove — a rounding policy
for FX, delivery semantics for Kafka, authorization correctness for payments, multi-node truth
for Kubernetes, legal claims for compliance. The README non-goals say so plainly, and the only
sanctioned re-entry is the stretch list in TODO.md — one at a time, each with its own
invariant/test row.

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

Tables define kinds, not owners. There is one table each for accounts, postings, and
entries — shared by every account in the ledger — so opening an account inserts a row, never
runs DDL. Splitting entries per account (or per currency) would turn account creation into a
schema migration, scatter the conservation sum across N tables, and force every constraint to
be duplicated once per table.

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

Each business area (accounts, postings) is split four ways. `api` takes HTTP in and
translates it; `application` orchestrates the use case (transactions, idempotency, retries);
`domain` holds the rules with no framework types; `persistence` speaks JDBC on the way out.
All decisions live in `application` plus `domain` — controllers and repositories only
translate at the edges.

- `PostingService` vs. `PostingDraft` proves the split: orchestration in `application`,
  rules in `domain`.
- `DomainIsolationTest` proves the domain has no Spring, JDBC, or validation; no framework
  stereotype inside any `domain` package.

### Dropped Alternatives

1. Layer-by-kind packages (`controllers`, `services`, `repositories`)

- Scatters one feature across three directories; here each area keeps its four parts together.

2. Framework types leaking into `domain`

- Rules must stay framework-free so the accounting contract outlives the stack.
