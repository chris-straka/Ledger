# DESIGN

## 1. Why raw SQL?

An ORM has managed entities, automatic updates, and cascades by default. 
I needed more control to set the tx boundary, BEFORE commit triggers and grants.
Bringing in an ORM to disable the features it comes with felt like a bad choice.

## 2. Why UUIDv7?

I could've used random UUIDs but they scatter writes across the index.
v7 starts with a timestamp so new rows land at the end.
Same uniqueness, cheaper inserts.

## 3. Why SERIALIZABLE? (tx isolation level)

Say the balance is 500 and two 500 withdrawals come in.
Without SERIALIZABLE, both read 500 and both apply. The account lands at −500.
A check at commit time can't catch it either. It reads the same stale snapshot.
SERIALIZABLE aborts one instead. The loser retries, sees the fresh balance, and gets refused.

It buys me no locks to manage. Postgres picks the loser.
I could've locked the account row first instead, but that's the same decision serialized by hand.
Or stored the balance in a column so the check reads fresh data, but then there's a copy to keep in sync.

## 4. Why idempotency keys?

A retried request must never post twice.
The key decides who won: same body replays the original, different body gets a 409.
Only committed postings consume keys, so a lost response is answered by retrying the same key.

## 5. Why two database roles?

Even if the app goes rogue, it can't rewrite history. It lacks the rights.
The owner migrates and nothing else. The app can only read and insert listed columns.
Triggers backstop the grants. Two honest limits stay: the owner can dismantle it all, and raw SQL can skip the §3 protocol.

## 6. Why reversals?

A correction must never rewrite history.
So it appends a mirror posting: same lines, opposite sides, built by the server.
One per posting, overdraft rules still apply, reversing a reversal is refused.

## 7. Why is all that other stuff out?

Each would need a rule I can't prove yet: rounding, delivery, authorization, multi-node truth, legal claims.
The README says so plainly.
The way back in is one stretch goal at a time, each with its own rule and test.

## 8. Why one entries table?

Opening an account must not touch the schema.
One table each for accounts, postings, entries, shared by everyone.
Per-account tables would mean DDL per account and the ledger sum scattered across N tables.

## 9. Why four parts per area?

The rules stay testable with no framework, and each feature lives in one place.
Application and domain decide; the edges only translate.
Layer-by-kind would scatter one feature across three directories.
