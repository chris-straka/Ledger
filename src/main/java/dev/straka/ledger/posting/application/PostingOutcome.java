package dev.straka.ledger.posting.application;

/** One attempt's outcome. Success is visible to HTTP only after commit. */
public sealed interface PostingOutcome permits PostingOutcome.Created, PostingOutcome.Replayed {
  record Created(java.util.UUID postingId) implements PostingOutcome {}

  record Replayed(java.util.UUID postingId) implements PostingOutcome {}
}
