package dev.straka.ledger.posting.domain;

/** Malformed idempotency key: a header-syntax problem, so HTTP 400 rather than 422. */
public class InvalidIdempotencyKeyException extends RuntimeException {
  public InvalidIdempotencyKeyException(String message) {
    super(message);
  }
}
