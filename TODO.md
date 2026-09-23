# Open work

Ledger backlog: stretch goals only, at most one at a time, each with its
own invariant/test row.

## Stretch goals (open — at most one at a time, each with its own invariant/test row)

- Materialized balance projection, only after a benchmark proves derived reads are the
  problem. Updated in the posting transaction with a standing equality proof; the journal
  stays authoritative.
- Property-based state-machine tests (jqwik): posting sequences, reversals, replays, with
  invariants audited after each committed step.
- FX postings: two independently balanced currency legs joined by an exchange op, exact rate,
  explicit rounding/residual account. Zero signed sum per currency; a rate never licenses
  adding unlike minor units.
- Historical statements/as-of balances: effective-time ordering, late/backdated events,
  stable keyset pagination, performance evidence first.
- Transactional outbox: immutable event intent with the posting, at-least-once publish,
  stable event IDs, crash/replay proof. Kafka enters here, never before.
- Load testing with published hardware, data shape, concurrency, percentiles, and retry
  rate. One laptop result is not a capacity claim.

## Source material (un-audited)

- `docs/` is carried over from the previous project and is NOT ledger documentation.
  Extract only verified Postgres/observability/failure-testing reasoning into
  `docs/DESIGN.md` during Phase 8; delete the rest.
- `resume.md` is user-owned; update ledger claims only after the proof passes.
