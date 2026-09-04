# Ledger

A double-entry ledger in **Java 25 + Spring Boot + Postgres**. Under construction.

Accounts classify immutable entries. A posting is the atomic set of entries that moves
value; postings must balance (debits equal credits) per posting, per currency. Nothing is
ever updated in place or deleted — corrections are reversal postings.

The contract is defined in [AGENTS.md](AGENTS.md) and the rewrite brief in [PORT.md](PORT.md).
Until the implementation phases in `PORT.md` are done, assume no accounting claim below is
proven.

## Status

Scaffolding (PORT.md Phase 0–1). No ledger API exists yet.

## Quickstart (target)

```sh
cp .env.example .env
make up
```

Further paths (`make dev`, `make demo`, `make verify`) will exist once their phases land.
See [TODO.md](TODO.md) for open work.
