# LINKEDIN — project summary draft

Paste-ready text is below (under 2000 chars). Picture ideas at the end.

## Summary

I built a double-entry ledger in Java 25, Spring Boot, and Postgres. It's the book of record for money moves: every move is a "posting," a set of debit/credit entries that must sum to zero, or the whole transaction aborts.

There are essentially two layers enforcing the balance, as far as I set it up.

1. The app validates it first (BigInteger math, integer minor units, no floats anywhere near money).
2. A deferred Postgres trigger checks it again at commit, so even raw SQL can't sneak in an unbalanced posting.

The part I liked most was the concurrency story. Postings run at SERIALIZABLE isolation, and I can show exactly why: I wrote a test harness at REPEATABLE READ where two withdrawals both read the same stale balance and both commit, landing a no-overdraft account at -6000. The deferred trigger can't catch it because it reads the same stale snapshot. At SERIALIZABLE, Postgres aborts one of them instead and the loser retries. A 50-thread storm against one account then lands exactly on the arithmetic sum.

Other things in there: idempotency keys (replay returns the original, conflicting bodies get 409), immutable history (the app's DB user can't UPDATE or DELETE anything, so corrections are reversal postings), and crash tests using real SIGKILLs. Every test ends with an audit that all entries sum to zero.

Honest limits: one Postgres by design, no load-test numbers yet, no FX or event streaming. Those are stretch goals, not claims.

https://github.com/chris-straka/Ledger

## Picture ideas

1. Green integration-test run (`./gradlew integrationTest`) — proof over prose.
2. Schema sketch: 3 tables (accounts, postings, entries) + deferred trigger + two DB roles. A 5-minute Excalidraw beats a paragraph.
3. Grafana dashboard from the observability profile (`docker compose --profile observability up`) mid posting-storm.
4. Before/after of the write-skew demo: -6000 at REPEATABLE READ vs. 201+409 at SERIALIZABLE. Strongest single image if you render it as one slide.
