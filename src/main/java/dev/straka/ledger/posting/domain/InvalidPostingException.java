package dev.straka.ledger.posting.domain;

/** Domain failure for unbalanced or malformed postings. Maps to HTTP 422, never 500. */
public class InvalidPostingException extends RuntimeException {
  public InvalidPostingException(String message) {
    super(message);
  }
}
