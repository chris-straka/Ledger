# LINKEDIN — project summary draft

Paste-ready text is below (under 2000 chars). Picture ideas at the end.

## Summary

Ledger — a double-entry book of record in Java 25, Spring Boot, and Postgres. Every money move is an atomic posting: a set of debit/credit entries that must sum to zero, enforced twice — in the domain layer (BigInteger math, integer minor units, no floats anywhere) and again by a deferred Postgres trigger, so even raw SQL can't write an unbalanced posting.

The interesting work was concurrency and correctness under failure. Postings run at SERIALIZABLE isolation, and I can show why: a REPEATABLE READ harness exhibits the overdraft write-skew (two withdrawals both reading the stale balance, landing a no-overdraft account at -6000) that SERIALIZABLE aborts instead. A 50-thread posting storm against one account lands exactly on the arithmetic sum — no lost updates, no hand-managed locks.

Idempotency comes from a unique key, never check-then-insert: replays return the original, conflicting bodies get 409, and a 20-way concurrent replay race produces exactly one posting. History is immutable by database enforcement — the app role can't UPDATE or DELETE journal rows, triggers backstop the grants, and corrections are reversal postings, never edits. Crash safety is proven with real SIGKILL tests: kill before commit leaves zero rows, kill after commit replays exactly once. Every test run ends with a conservation audit: all entries sum to zero.

No ORM (raw JDBC for explicit tx boundaries), no balance column (derived from entries, nothing to drift), tested against real Postgres via Testcontainers.

## Picture ideas

1. Green integration-test run (`./gradlew integrationTest`) — proof over prose.
2. Schema sketch: 3 tables (accounts, postings, entries) + deferred trigger + two DB roles. A 5-minute Excalidraw beats a paragraph.
3. Grafana dashboard from the observability profile (`docker compose --profile observability up`) mid posting-storm.
4. Before/after of the write-skew demo: -6000 at REPEATABLE READ vs. 201+409 at SERIALIZABLE. Strongest single image if you render it as one slide.
