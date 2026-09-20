package dev.straka.ledger.posting.application;

/**
 * Sealed interface describing one attempt's outcome: {@code Created} for a newly committed posting,
 * {@code Replayed} when the idempotency key already won. Success is visible to HTTP only after
 * commit.
 */
public sealed interface PostingOutcome permits PostingOutcome.Created, PostingOutcome.Replayed {
  record Created(java.util.UUID postingId) implements PostingOutcome {}

  record Replayed(java.util.UUID postingId) implements PostingOutcome {}
}
