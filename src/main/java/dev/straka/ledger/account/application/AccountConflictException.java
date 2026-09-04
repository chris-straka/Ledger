package dev.straka.ledger.account.application;

/** Account creation collided with existing state (duplicate code). Maps to HTTP 409. */
public class AccountConflictException extends RuntimeException {
  public AccountConflictException(String message) {
    super(message);
  }
}
