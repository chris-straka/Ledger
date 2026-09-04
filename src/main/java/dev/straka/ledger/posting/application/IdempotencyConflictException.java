package dev.straka.ledger.posting.application;

/** Same idempotency key, different semantic request. Maps to HTTP 409. */
public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException(String message) {
    super(message);
  }
}
