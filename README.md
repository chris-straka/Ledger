# Ledger

A double-entry ledger in **Java 25 + Spring Boot + Postgres**. Accounts classify immutable
entries; a *posting* is the atomic set of entries that moves value. Balances are derived from
entries — no balance column exists. Nothing is ever updated in place or deleted; corrections are
exact reversal postings.

The contract is [AGENTS.md](AGENTS.md); the rewrite brief is [PORT.md](PORT.md); the decisions
are [docs/DESIGN.md](docs/DESIGN.md); the defense is [docs/INTERVIEW.md](docs/INTERVIEW.md).

## Guarantees and their proof

| Claim | Proof |
| --- | --- |
| Every posting balances (debits = credits, per posting, per currency) | Domain `PostingDraft` (BigInteger) + deferred trigger; `PostingBalanceTest` bypasses Java |
| Conservation: all entries sum to zero per currency, after every test | Shared audit in `LedgerIntegrationTest`; `scripts/verify.sh` |
| No UPDATE/DELETE on journal tables | Revoked grants (42501) + immutable triggers; `ImmutabilityTest` as `ledger_app` |
| One currency per posting | Composite FKs; `CurrencyTest` |
| Idempotent posting creation | Fingerprint + unique key; sequential, 20-way, and mixed-payload races |
| Concurrent postings sum exactly | 50-thread storm; serializable overdraft race with recorded retry |
| Explicit overdraft policy | Per-account ALLOW/DENY in app + deferred check; weaker-isolation anomaly demo |
| Crash atomicity | `scripts/crash-recovery.sh`: SIGKILL before commit leaves nothing, after commit replays once |
| Corrections are additive | Server-generated inverse postings; `ReversalApiTest` incl. raw fraud rejection |

## Quickstart

```sh
cp .env.example .env
make upd        # build, migrate (owner), start app (runtime role)
make demo       # isolated 11-step walkthrough; asserts every claim above
make verify     # non-empty closure + conservation audit of the dev database
make crash-test # isolated SIGKILL harness
make test integration-test
```

## API

- `POST /v1/accounts` → 201 + `Location`; `GET /v1/accounts/{id}`; `GET /v1/accounts/{id}/balance`
  (derived, integer string).
- `POST /v1/postings` (requires `Idempotency-Key`) → 201; replay → 200 + `Idempotency-Replayed`;
  key conflict → 409; overdraft → 409; invalid → 422.
- `GET /v1/postings/{id}`; `POST /v1/postings/{id}/reversals` (new key; server-built inverse).
- Errors are `application/problem+json` with a stable `code` and trace id. No update/delete routes.

## Non-goals (stated plainly)

No payment processing, settlement, or card authorization. No fraud/AML monitoring. No wallet
product or accounting UI. No multi-tenancy, multi-region, or multi-node consensus. No Kafka
pipeline (outbox is a stretch goal). No PCI/SOC 2/regulatory/tax claim of any kind. No throughput
numbers without a reproducible benchmark. Local database least privilege is not a complete
application security model, and dev ports bind to `127.0.0.1`.

## Observability

`/actuator/health` (liveness) and `/actuator/health/readiness` (JVM + bounded Postgres check);
Prometheus exposition with low-cardinality `ledger.postings{kind,outcome}`,
`ledger.posting.commit` latency, and retry gauges. Optional local Grafana:
`docker compose --profile observability up`. Integrity audit is an explicit command (`make
verify`), never a scrape.
