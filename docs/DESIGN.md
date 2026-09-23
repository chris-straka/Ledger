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
There is no balance column, and a BEFORE COMMIT CHECK reads the same stale snapshot. 
SERIALIZABLE aborts one instead. The loser retries, sees the fresh balance, and gets refused.

It buys me no locks to manage. Postgres picks the loser.
I could've locked the account row first instead, but that's the same decision serialized by hand.
Or stored the balance in a column so the check reads fresh data, but then there's a copy to keep in sync.

## 4. How are retried requests handled?

A retried request must never post twice, it uses an idempotency key to replay the original response.
If the request body is different but the key is the same, the response is 409.
Only committed postings consume keys, so a lost response is answered by retrying the same key.

## 5. Why two DB roles?

Even if the java app goes rogue, it can't rewrite the DB, it lacks the rights.
The owner migrates and nothing else. The app reads cols and adds postings.
Triggers also enforce the grants (permissions). 

Two honest limits stay: the owner can dismantle it all, and raw SQL can skip the §3 protocol.

## 6. Why reversals and not edits? 

Reversals can't rewrite history (edits can).
Reversals make postings auditable without needing an audit table (or sync).
Overdraft rules still apply, reversing a reversal is refused.

