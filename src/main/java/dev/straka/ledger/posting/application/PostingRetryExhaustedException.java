package dev.straka.ledger.posting.application;

/**
 * Serialization retries exhausted. Maps to HTTP 503 with {@code Retry-After: 1}: the request is
 * fine, the database just could not order it after five attempts.
 */
public class PostingRetryExhaustedException extends RuntimeException {
  public PostingRetryExhaustedException(String message) {
    super(message);
  }
}
