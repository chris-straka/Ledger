# Ledger

A double-entry ledger in **Java 25 + Spring Boot + Postgres**. Under construction.

Accounts classify immutable entries. A posting is the atomic set of entries that moves
value; postings must balance (debits equal credits) per posting, per currency. Nothing is
ever updated in place or deleted — corrections are reversal postings.

The contract is defined in [AGENTS.md](AGENTS.md) and the rewrite brief in [PORT.md](PORT.md).
Until the implementation phases in `PORT.md` are done, assume no accounting claim below is
proven.

## Status

PORT.md Phases 0–2 done. The database enforces balanced, single-currency,
immutable postings with per-account overdraft policy (40 Testcontainers bypass
tests green); `docker compose up` migrates and serves readiness UP. No ledger
HTTP API exists yet — that is Phase 3–4.

## Quickstart (target)

```sh
cp .env.example .env
make up
```

Further paths (`make dev`, `make demo`, `make verify`) will exist once their phases land.
See [TODO.md](TODO.md) for open work.
