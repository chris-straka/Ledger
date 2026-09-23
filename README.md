# Ledger

A double-entry ledger in **Java 25 + Spring Boot + Postgres**. Accounts classify immutable
entries; a _posting_ is the atomic set of entries that moves value. Balances are derived from
entries — no balance column exists. Nothing is ever updated in place or deleted; corrections are
exact reversal postings.

## How it works

An account is one row — Cash, Rent Expense, Customer Deposits. Its type
(asset, liability, equity, revenue, expense) decides whether a debit or a credit
raises its balance.

Money moves only in postings: matching debit and credit entries written in one
tx. Paying $500 rent writes a $500 debit to Rent Expense and a $500
credit to Cash. Amounts are integer minor units.

Rows are never updated or deleted; mistakes are reversed with a reversal posting.

Rules are in [AGENTS.md](AGENTS.md), trade-offs in [docs/DESIGN.md](docs/DESIGN.md), interview notes in [docs/INTERVIEW.md](docs/INTERVIEW.md).

The schema is [`V1__journal_schema.sql`](src/main/resources/db/migration/V1__journal_schema.sql): three tables accounts, postings, entries.

## Guarantees and their proof

### Every posting balances

Every posting balances in its own currency: debits equal credits.
Enforced twice: once in `PostingDraft` (BigInteger), and again by a deferred Postgres trigger.
`PostingBalanceTest` attacks the trigger with raw SQL, bypassing our app.

### Conservation

All entries sum to zero per currency.
Audited in `LedgerIntegrationTest`, and against a live DB by `scripts/verify.sh`.

### No UPDATE/DELETE on postings, entries, and accounts

The app connects to Postgres as `ledger_app`, a DB user that Postgres forbids
from updating or deleting rows in those tables (so even buggy app code can't
edit history). If the app is ever granted wider permissions by mistake, triggers
that run before updates/deletes abort the attempt anyway.
`ImmutabilityTest` connects as `ledger_app` to prove the DB refuses.

Corrections are additive
Reversals are inverse postings made with a new idempotency key.

### One currency per posting

Composite foreign keys tie posting, account, and entry currency together.
`CurrencyTest` proves a mismatch fails.

### Idempotent posting creation

Each posting carries a unique idempotency key plus a fingerprint of the request.
Replaying a key returns the original posting instead of writing entries twice;
a different body under the same key is an HTTP 409 conflict.
Proven three ways: posting the same key twice in a row returns the same posting
with no new rows; 20 identical requests fired at once produce exactly one posting
(the unique key picks the winner); and reusing a key for different amounts is
rejected with 409.

### Concurrent postings sum exactly

A 50-thread posting storm against one account lands exactly on the arithmetic sum.

### Explicit overdraft policy

An overdraft is a posting that would drive an account balance below zero.
Each account declares ALLOW/DENY for overdrafting at account creation.
DENY aborts postings (409) if they overdraft.
Enforced in the app and by a deferred constraint.

A REPEATABLE READ demo fires two 8,000 withdrawals at 10,000 and leaves -6,000.
So real postings run SERIALIZABLE instead (see `docs/DESIGN.md` §3).

### Crash atomicity

`scripts/crash-recovery.sh` SIGKILLs the app at two points. If the kill lands
before commit, Postgres rolls the open tx back, so no partial posting
survives — no header without entries, no entries without a header. If it lands
after commit but before the HTTP response is sent, the client retries the same key and gets
the committed posting back with no second write.

## Quickstart

```sh
cp .env.example .env
make upd        # build, migrate (owner), start app (runtime role)
make demo       # isolated 11-step walkthrough; asserts every claim above
make verify     # non-empty closure + conservation audit of the dev DB
make crash-test # isolated SIGKILL harness
make backup     # pg_dump to backups/ (gitignored); DUMP=... make restore replays + audits
make test integration-test
```

## Observability

`/actuator/health` (liveness) and `/actuator/health/readiness` (JVM + bounded Postgres check);
Prometheus exposition with low-cardinality `ledger.postings{kind,outcome}`,
`ledger.posting.commit` latency, and retry gauges. Optional local Grafana:
`docker compose --profile observability up`. Integrity audit is an explicit command (`make
verify`), never a scrape.
