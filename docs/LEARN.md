# Learn double-entry ledgers from this repo

A guided path through the code: the problem, the ideas that make the books
impossible to unbalance, the order to read files in, experiments with
predicted outcomes, and the questions an interviewer will ask.

## 1. The problem

Every company that holds money for someone else (a bank, a wallet, a
marketplace paying sellers, a payroll provider) needs one place that says,
to the cent, who owns what and how it got there. Spreadsheets and a
`balance` column drift: a crash between two updates, a retried request, or
two concurrent withdrawals each reading the same old balance is enough.

A double-entry ledger fixes this with structure instead of care. Money only
moves in _postings_: sets of entries whose debits equal their credits, so
the whole ledger always sums to zero. Balances are derived from entries,
never stored. Nothing is edited; mistakes are corrected with a reversal.
This repo is that idea with the guarantees pushed down into Postgres, where
application bugs cannot reach them. The eight invariants are in
[AGENTS.md](../AGENTS.md); the trade-offs are in [DESIGN.md](DESIGN.md).

## 2. Concepts you need

**Debits, credits, and normal sides.** Each account has a type (asset,
liability, equity, revenue, expense). Assets and expenses grow with debits,
the rest with credits. Internally a debit is `+` and a credit is `-`, so a
balanced posting sums to zero. See `EntrySide` and `AccountType` in
`src/main/java/dev/straka/ledger/account/domain/`.

**A posting balances, checked twice.** `PostingDraft`
([PostingDraft.java:35](../src/main/java/dev/straka/ledger/posting/domain/PostingDraft.java))
sums debits and credits in `BigInteger` and rejects a mismatch. The same
rule runs again in a deferred constraint trigger
([V1\_\_journal_schema.sql:113](../src/main/resources/db/migration/V1__journal_schema.sql),
wired at line 234) that fires at commit, so raw SQL cannot bypass it.

**Deferred constraint triggers.** A posting is one header row plus N entry
rows. Checked row by row, the first entry is always unbalanced. Marking the
trigger `DEFERRABLE INITIALLY DEFERRED` moves the check to `COMMIT`, when the
whole set is visible.

**Derived balances.** There is no balance column. `v_account_balance`
([V1:78](../src/main/resources/db/migration/V1__journal_schema.sql)) sums
entries; `v_conservation` (line 94) proves every currency sums to zero.

**Immutability by privilege, then by trigger.** The app connects as
`ledger_app`, which has `SELECT` and column-limited `INSERT` only (grants at
V1 line 244). If someone grants more by mistake, `ledger_forbid_mutation`
(line 264) still refuses any `UPDATE` or `DELETE` with SQLSTATE 25001.

**SERIALIZABLE against write skew.** Two withdrawals that each read a 10,000
balance and each insert disjoint rows both commit under REPEATABLE READ and
leave the account at -6,000. SERIALIZABLE makes Postgres abort one with
SQLSTATE 40001; `runSerializable`
([PostingService.java:203](../src/main/java/dev/straka/ledger/posting/application/PostingService.java))
retries it up to 5 times with capped backoff, and the retry sees the new
balance and gets a 409.

**Idempotency by unique key plus fingerprint.** Each posting carries the
client's `Idempotency-Key` (unique in the table) and a SHA-256 fingerprint
of the request (`PostingFingerprint`). A replay with the same body returns
the original posting with `200`; a different body under the same key is
`409`. Only committed postings consume keys.

**Reversals, not edits.** `PostingService.reverse` (line 151) writes the
exact inverse as a new posting linked to the original. Reversing twice, or
reversing a reversal, is refused.

## 3. Reading order

1. `src/main/resources/db/migration/V1__journal_schema.sql` — the whole
   contract in one file: tables, views, the deferred check, grants,
   immutability triggers. Read it top to bottom once.
2. `account/domain/` — `AccountType`, `EntrySide`, `OverdraftPolicy`,
   `CurrencyCode`: small value types.
3. `posting/domain/PostingDraft.java`, `PostingFingerprint.java`,
   `IdempotencyKey.java` — pure validation, no Spring.
4. `posting/persistence/PostingRepository.java` — raw JDBC inserts and
   reads; no ORM by design (DESIGN.md §1).
5. `posting/application/PostingService.java` — `post`, `reverse`,
   `runSerializable`, `rejectOverdraft`, and how trigger errors become 409s.
6. `posting/api/PostingController.java`, `support/error/ProblemAdvice.java`
   — HTTP shapes and RFC 9457 problem responses.
7. `src/integrationTest/.../LedgerDB.java` — how every test bootstraps the
   owner and app roles and audits conservation after each test.
8. `ConcurrencyTest.java`, `PostingBalanceTest.java`,
   `ImmutabilityTest.java` — the invariants as executable claims.
9. `scripts/crash-recovery.sh`, `scripts/load-test.sh` — the system under
   SIGKILL and under load.

## 4. Exercises

Set up a disposable Postgres once (Homebrew shown; any Postgres 18 works):
`initdb -D /tmp/pgl -U postgres --auth=trust && pg_ctl -D /tmp/pgl -o "-p 55432" start`.
Integration tests: `LEDGER_TEST_ADMIN_URL=jdbc:postgresql://localhost:55432/postgres ./gradlew integrationTest`.
A server: `LEDGER_PG_ADMIN_URL=postgres://postgres@localhost:55432/postgres ./scripts/run-local.sh`.

1. **Two layers.** Delete the `debits.equals(credits)` rejection in
   `PostingDraft`. Predict which suites fail: unit, integration, or both.
   <details><summary>Answer</summary>Only the unit suite: two
   `PostingDraftTest` cases fail (`unbalancedDraftIsRejected`,
   `totalsBeyondLongRangeStillJudgeExactly`). All 75 integration tests
   still pass, including the API ones, because the deferred trigger rejects
   the unbalanced commit on its own and the service turns that into the
   same client error. That is the point of checking twice.</details>

2. **Weaken the isolation level.** In `PostingService`, change
   `ISOLATION_SERIALIZABLE` to `ISOLATION_REPEATABLE_READ` and run the
   integration suite. Predict which test fails and what it reports.
   <details><summary>Answer</summary>Exactly one of 75 fails:
   `ConcurrencyTest.overdraftRaceCommitsOneAndRejectsOne`, with
   `expected: <[201, 409]> but was: <[201]>`. Both racing withdrawals
   commit (the set of statuses collapses to one value), because each
   transaction's overdraft check, including the deferred trigger, reads its
   own stale snapshot. Write skew is not prevented by REPEATABLE READ in
   Postgres.</details>

3. **Replay versus conflict.** With the server running, create two `USD`
   accounts, then POST the same posting twice with `Idempotency-Key: k1`,
   then a third time with a different amount. Predict the three status codes.
   <details><summary>Answer</summary>`201`, `200` with the same
   `postingId`, then `409`. Row counts in `ledger_posting` and
   `ledger_entry` do not change on the replay.</details>

4. **Try to edit history.** Connect as the app role
   (`psql postgres://ledger_app@localhost:55432/ledger`) and run
   `UPDATE ledger_entry SET amount_minor_units = 1`. Then try the same as
   `ledger_owner`. Predict both errors.
   <details><summary>Answer</summary>As `ledger_app`: `permission denied`
   (SQLSTATE 42501), because the grant does not exist. As the owner, who
   does have the privilege: `ledger immutable: UPDATE on ledger_entry is
   forbidden` (SQLSTATE 25001) from the row trigger. Note that `TRUNCATE`
   fires no row trigger, so the owner can still wipe the tables; DESIGN.md
   §5 says this limit out loud.</details>

5. **False conflicts under SERIALIZABLE.** Run
   `./scripts/load-test.sh 2000 16 spread`: 32 accounts, random pairs.
   Postings mostly touch different accounts, yet the run reports hundreds
   of serialization retries. While it runs, query
   `SELECT locktype, relation::regclass, count(*) FROM pg_locks WHERE mode = 'SIReadLock' GROUP BY 1, 2;`.
   Why do unrelated postings conflict?
   <details><summary>Answer</summary>Postgres's serializable snapshot
   isolation records reads as predicate locks, and many are page-level, not
   row-level: you will see `page` locks on
   `ledger_posting_idempotency_key_key` (every posting first looks up its
   key, which is not there yet) and on `ledger_entry_posting_entry`. Every
   concurrent posting inserts into those same index pages, so Postgres sees
   read/write dependencies between transactions that share no account and
   aborts some to stay safe. It prefers false positives to missed
   anomalies. That is why the p95 is ~100 ms while the p50 is ~2 ms, and why
   a few postings exhaust their 5 attempts and get a retryable 503.</details>

6. **Hot account.** Run `./scripts/load-test.sh 1000 16 hot` (every posting
   debits the same account). Predict whether throughput drops versus
   `spread`.
   <details><summary>Answer</summary>Measured here it did not: about 800
   postings/s with fewer retries than `spread`. The posting path never
   updates an account row, so there is no row lock to queue on; contention
   is decided by the same shared index pages in both modes. A design with a
   stored balance column would serialize on that row instead.</details>

7. **Outbox (hard, a listed stretch goal).** Add an `ledger_outbox` table
   and insert one event row inside the posting transaction. Which
   invariant tests must you extend, and what key should the event carry?
   <details><summary>Answer</summary>The event must commit with the
   posting or not at all (invariant 8, crash atomicity), so extend the
   crash harness to assert "posting exists iff its outbox row exists". The
   event id should be stable per posting (the posting id), so a relay that
   redelivers it lets consumers dedupe in an inbox, the pattern cdc-pipe
   implements.</details>

## 5. Interview questions

1. **Why derive balances instead of storing them?**
   <details><summary>Answer</summary>A stored balance is a second copy
   that can drift from the entries. Derived balances cannot disagree with
   the journal. The cost is read time; the TODO keeps a materialized
   projection behind a benchmark that proves reads are the problem.</details>

2. **Why can't a CHECK constraint enforce "the posting balances"?**
   <details><summary>Answer</summary>A CHECK sees one row. Balance is a
   property of a set of rows written across several statements, so it
   needs a constraint trigger deferred to commit.</details>

3. **Which anomaly does SERIALIZABLE prevent here, and how do you know?**
   <details><summary>Answer</summary>Overdraft write skew: two
   transactions read the same balance and insert disjoint rows.
   `ConcurrencyTest.repeatableReadLosesTheOverdraftRace` shows the anomaly
   at REPEATABLE READ; the production path aborts one and retries.</details>

4. **How do you make a retried POST safe?**
   <details><summary>Answer</summary>A unique idempotency key plus a
   request fingerprint. Same key and body replays the stored posting; same
   key, different body is 409. Concurrent identical requests elect one
   winner through the unique index, not a check-then-insert.</details>

5. **How do you correct a wrong posting?**
   <details><summary>Answer</summary>Post its exact inverse as a reversal
   linked to the original. Both stay visible, the audit trail is the
   journal itself, and reversing twice is refused.</details>

6. **What stops a bug in the app from rewriting history?**
   <details><summary>Answer</summary>The app's database role has no
   UPDATE or DELETE privilege on journal tables, and row triggers refuse
   them even for roles that do. `ImmutabilityTest` proves both.</details>

7. **What happens if the process dies mid-posting?**
   <details><summary>Answer</summary>The posting is one transaction, so
   Postgres rolls back an uncommitted one entirely. If the crash is after
   commit but before the response, the client retries the same key and
   gets the committed posting back. `scripts/crash-recovery.sh` kills at
   both points.</details>

8. **Your p95 is 50x your p50. Why?**
   <details><summary>Answer</summary>SERIALIZABLE retries with backoff.
   Most aborts are false positives from page-level predicate locks on
   shared indexes (exercise 5). Options: accept it, narrow the read set,
   or move the overdraft decision to an explicit account-row lock.</details>

## 6. Connections

- **[PaymentOrchestration](../../PaymentOrchestration)** decides whether a
  card payment happened; this ledger records it. The orchestrator is
  deliberately not a ledger, and this repo deliberately has no PSPs.
- **[cdc-pipe](../../cdc-pipe)** is the relay between them: outbox event
  out of the orchestrator, at-least-once delivery, and an idempotent sink.
  Using the event id as this API's `Idempotency-Key` is what makes a
  redelivered event book once.
- **[recon](../../recon)** reconciles this ledger's postings against the
  orchestrator's captures and the PSP's settlement file, and opens a break
  for every disagreement.
- **[stable-rail](../../stable-rail)** uses the same correction model for
  on-chain money: a USDC deposit is a provisional credit until final, and a
  reorg is an exact-inverse reversal.

Run the chain end to end with `recon/scripts/money-stack.sh`.
