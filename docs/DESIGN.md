# DESIGN

## 1. Signed journal arithmetic vs. normal-side account balances

The side stays next to the amount so the database can check it and sign flips can't hide.
Entries store an unsigned amount plus a side (debit/credit).
Every posting and the whole ledger must have conservation (must total zero).

Account balances come from adding up its entries.
The normal side of a balance is whatever side makes the value go up.
(ASSET/EXPENSE: debits − credits; everything else: credits − debits).

Two SQL views (v_account_balance, v_conservation) and one table, so no drift is possible.

- `PostingBalanceTest` proves balanced postings commit and unbalanced ones fail at commit.
- `OverdraftTest` proves DENY balances hold at commit (exact-to-zero allowed, ALLOW may go negative).

1. Storing a signed amount per entry instead of an unsigned amount with a side

- Signed amounts hide the side inside the number, so the DB can't check it.
- Mistakes can cancel out: flip the signs on both legs and the posting still totals zero. A withdrawal can change to a deposit and nothing notices.

## 2. Integer minor units and overflow-safe aggregation

Not one cent lost to rounding or overflow. Ever.
Amounts are integer minor units — cents, not dollars (10000 is 100.00).
Java carries them as `long` and Postgres as `bigint`.
The API sends them as base-10 strings, so no client can round them.

Totals use `BigInteger` and PG `sum(bigint)` -> text, so no overflows/rounds.

- `PostingDraft` proves totals stay exact past `long` range
- `PostingBalanceTest` proves near-limit amounts commit exactly.

## 3. Derived balances vs. a materialized projection

Reads are cheap enough that I add up entries fresh. No stored copy to drift.
Currently, `GET .../balance` adds up the account's entries on every read.

The alternative is a stored balance column, written in the same tx as the entries.
The journal would stay authoritative, and I'd need to prove that the stored value matches the derived one.

Materialized -> saves a view into a col
Projection -> same data looked at differently

I don't have a reason to switch to a materialized projection (no bottleneck/benchmark so far)

- `AccountRepository` proves the balance comes from entries in one statement.
- `AccountApiTest` proves the endpoint returns that value.
- `ConcurrencyTest` proves lost updates are impossible by structure, not by locking.

### Dropped Alternatives

1. A `balance` col updated alongside entries

- Two copies of the balance would need to stay in sync.
- Every hot account would gain a lost-update and skew surface.

## 4. JDBC vs. JPA for an append-only, SQL-constrained model

The important parts stay readable as SQL text. That is on purpose.
DB access is plain JDBC: `JdbcClient` for queries, `JdbcTemplate` for entry batches.
The schema is versioned SQL via Flyway. That is on purpose. The parts that matter are SQL
text, the transaction boundary, the commit-time trigger (§5), and the grants (§8). 

An ORM hides all four behind generated queries. It has managed entities, automatic updates, 
and cascades for defaults. Paying for all of that and then disabling it felt wrong.

- `AccountRepository` and `PostingRepository` prove the persistence boundary is explicit SQL.
- `DomainIsolationTest` proves the domain has no JDBC. No `ddl-auto`, H2, or entity
  annotation anywhere.

## 5. Commit-time checks and the sealed posting

A posting is several rows that are only valid together.
My Java checks can be skipped by anyone writing straight to the database.
So the database checks every commit itself, no matter who wrote the rows.
A PGSQL row CHECK only sees its own row, so it can't judge whether 2 rows balance. 
A constraint trigger runs at commit time instead, once all of the posting's rows are in.
It checks the count, the numbering, the accounts, the zero sum, overdrafts (see §6),
and the reversal rules. The declared `entry_count` never changes.
That seals the posting (a later balanced pair breaks the count and fails).

IDs default to `uuidv7()`, which keeps the primary-key index in insertion order. 
The table itself stays unordered.

- `PostingBalanceTest` proves balanced postings commit and unbalanced ones fail.
- `PostingClosureTest` proves nothing appends after commit.
- `scripts/demo.sh` step 6 proves the seal on a live DB.

## 6. Serializable isolation, write skew, retries

Write skew -> two transactions that each look fine alone but break the rules together.

A DENY account must hold even when two spends race it.

Each account either rejects overdrafts (DENY) or allows them (ALLOW).
Picture a DENY account holding 10,000. Two transactions each approve an 8,000 spend.
Each reads the balance, sees enough, and writes its own new rows.
At REPEATABLE READ both commit and the account sits at −6,000.
Neither saw the other's rows, so no check fired.
I post at SERIALIZABLE instead, so Postgres aborts one of them (40001).
The whole transaction runs again from scratch, decision included, with backoff between tries.
After five tries it stops and answers 503 with a Retry-After 1s, so the caller retries later.
No JVM locks. A lock in one app instance can't stop another instance.

- `ConcurrencyTest` proves the hole: two approvals commit at −6,000.
- `ConcurrencyTest` proves the fix: one 201, one 409, with a recorded retry
  (`AttemptRecorder`).
- `PostingService` proves the retry protocol: whole-transaction retries, five attempts, then 503.

### Dropped Alternatives

1. READ COMMITTED + `SELECT ... FOR UPDATE`

- My fallback if serializable ever gets too slow. Not needed yet.

2. Unbounded retries

- Five tries, about 600ms worst case, then stop. Forever is not a retry policy.

3. Async submit — answer HTTP 202 at once, queue the posting, have the client poll

- It would free the request thread during retries. But commits could land out of order, and a
  redelivered request could post twice: the §7 key can't pick one winner out of two queued copies.
- So retries stay synchronous: the client retries on 503, and the service retries the commit.

## 7. Idempotency keys and the lost response

Idempotency key -> a client-supplied id so a retry never posts twice.

A retried request must never post twice. The key decides who won, not application code.

Every post carries one, backed by a UNIQUE constraint.
Two requests racing with the same key: one insert wins.
The loser rolls back, reads the winner outside its own transaction, and compares fingerprints.
Fingerprint -> a SHA-256 hash over what the posting says (kind, description, instant, entries in order).
Same body means the loser just replays the original (HTTP 200 + a replay flag).
Different body means 409. The key picks a winner, it never merges.
Only committed postings consume keys.
So when a response dies on the wire, the client retries with the same key and gets the original back instead of posting twice.

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

## 8. Two database roles and what each may touch

Even if the app goes rogue, it can't rewrite history. It lacks the rights.
`ledger_owner` runs migrations and nothing else.
The app connects as `ledger_app`: connect, use the schema, read, and insert on a listed set of columns.
No UPDATE, no DELETE, no TRUNCATE. Revoked, not just absent.
Backstop -> triggers that reject any UPDATE/DELETE even if someone hands out a bad grant.
Two honest limits: the owner can dismantle all of this, and overdraft safety assumes every
writer plays by the §6 protocol, which raw SQL can sidestep.

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

## 9. Reversals instead of edits

A correction never edits history. It appends a new posting that mirrors the original line
for line: same account, amount, and currency, opposite side.
The server builds those mirror entries. Clients just name the posting.
The §5 trigger checks each mirror line against its original.
One reversal per posting, enforced by a unique slot. Reversing a reversal is refused.
Overdraft rules still apply, so undoing spent money can itself be refused.

- `ReversalApiTest` proves corrections are server-derived inverse postings (7 tests). A
  double reversal is refused, and reversal-of-reversal is refused by kind.
- `PostingService` proves each inverse entry matches (`scripts/demo.sh` step 9).

### Dropped Alternatives

1. Edits, deletes, status flags

- History would be rewritable. A correction is a new posting; the old one stands.

2. Client-supplied reversal entries

- Clients can't just label anything a reversal. The server builds the mirror, and overdraft rules still apply.

## 10. What stays out and why

Each of the five would need a rule I can't prove yet. FX needs a rounding policy. Kafka
needs delivery promises. Payments need authorization correctness. Kubernetes needs
multi-node truth. Compliance needs legal claims. The README says so plainly.
The way back in is one stretch goal at a time from TODO.md, each with its own rule and test.

- `README.md` non-goals prove the boundary is stated plainly.
- `AGENTS.md` proves the contract defended instead: eight invariants, each a test.
- `TODO.md` proves the only way back in: the stretch list.

## 11. One entries table for all accounts

Opening an account must not touch the schema.
Tables define kinds, not owners. Accounts, postings, entries: one table each, shared by everyone.
Opening an account inserts a row. It never changes the table shapes (never DDL).

- `V1__journal_schema.sql` proves three tables cover every account.
- `v_conservation` proves the whole-ledger sum reads one table, never N.

### Dropped Alternatives

1. Per-account/per-currency entry tables

- Opening an account would mean running DDL.
- The ledger sum would scatter across N tables.
- Every constraint would need one copy per table.
- (See §3 for the balances-table version of the same mistake.)

## 12. Four parts per area, rules in the middle

The rules stay testable with no framework, and each feature lives in one place.
Each area (accounts, postings) splits four ways.
`api` takes HTTP in and translates it.
`application` runs the job: transactions, idempotency, retries.
`domain` holds the rules with no framework types.
`persistence` speaks JDBC on the way out.
Decisions live in `application` plus `domain`. The edges only translate.

- `PostingService` vs. `PostingDraft` proves the split: running the use case vs. stating the
  rules.
- `DomainIsolationTest` proves the domain has no Spring, JDBC, or validation. No framework
  stereotype inside any `domain` package.

### Dropped Alternatives

1. Layer-by-kind packages (`controllers`, `services`, `repositories`)

- One feature scatters across three directories. Here each area keeps its four parts together.

2. Framework types leaking into `domain`

- The rules would depend on the framework. They stay independent instead.
