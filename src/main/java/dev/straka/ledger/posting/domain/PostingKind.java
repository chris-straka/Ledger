package dev.straka.ledger.posting.domain;

/** Posting flavor. V1 has standard postings; exact reversals arrive in Phase 6. */
public enum PostingKind {
  STANDARD,
  REVERSAL
}
