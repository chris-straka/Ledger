# Query Optimization Notes

Static review first, then measured. The index map below is read from
`src/main/resources/db/migration/V1__journal_schema.sql`; the timings in
"Measured" come from a scratch Postgres, not production.

## Measured (2026-09-11)

Methodology: Homebrew Postgres 18 on Apple silicon, fresh instance, V1
schema only (no V3 triggers), `VACUUM ANALYZE`, synthetic data — 50
accounts, 10,000 postings, 20,000 balanced entries (400 per account).
`EXPLAIN (ANALYZE, TIMING OFF)`, single runs, not benchmarks.

| Query                                   | Plan                                                           | Result               |
| --------------------------------------- | -------------------------------------------------------------- | -------------------- |
| Balance scan, one account (400 entries) | Bitmap Index Scan on `ledger_entry_account_posting`            | 0.272 ms execution   |
| Idempotent replay by key                | Index Scan on `ledger_posting_idempotency_key_key`, 1 row      | 0.027 ms execution   |
| Double-reversal check                   | Index Scan on `ledger_posting_reverses_posting_id_key`, 0 rows | index-backed, sub-ms |

Read: at this cardinality every hot path is index-backed with no seq scan
on a journal table (the only seq scan in the plans touches the 50-row
account table for the code lookup). Re-measure at production cardinality
before citing these numbers anywhere; they prove index usage, not scale.

## Access pattern → index map

| Query                                     | Path                                                                                               | Evidence          |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------- | ----------------- |
| Statement/balance scan per account        | `ledger_entry_account_posting ON ledger_entry (account_id, posting_id)` — the only secondary index | V1:104            |
| Idempotent create fast-path               | `idempotency_key varchar(128) NOT NULL UNIQUE` on `ledger_posting` — replay lookup before insert   | V1:52             |
| Replay dedup across key rotation          | `request_fingerprint bytea` + sha256 length check + `fingerprint_version`                          | V1:55-58          |
| One reversal per posting max              | `reverses_posting_id uuid UNIQUE` — the schema refuses double-reversal                             | V1:61             |
| Single-currency enforcement without joins | Composite `UNIQUE (id, currency_code)` + composite FKs on entries                                  | V1:43, 73, 96, 99 |
| Entry append ordering                     | `UNIQUE (posting_id, entry_number)`                                                                | V1:94             |
| Time-ordered UUIDs                        | `uuidv7()` PK defaults cluster recent rows                                                         | V1:30, 51, 84     |

## Deliberate sparsity

A journal is write-heavy: every secondary index taxes the insert path the
reliability story depends on. One secondary index plus uniqueness
constraints (which pull double duty as lookup paths) is the trade, not an
oversight. If balance reads ever dominate, the honest next step is a
materialized balance with a benchmark proving the skew — tracked as a
stretch goal, not claimed here.
