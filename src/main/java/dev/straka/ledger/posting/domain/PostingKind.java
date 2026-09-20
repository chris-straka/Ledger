package dev.straka.ledger.posting.domain;

/** Enum distinguishing standard postings from exact reversals. A reversal inverts every line of one standard posting; nothing is ever edited. */
public enum PostingKind {
  STANDARD,
  REVERSAL
}
