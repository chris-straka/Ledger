package dev.straka.ledger.account.domain;

/** Domain failure for malformed accounts. Maps to HTTP 422, never 500. */
public class InvalidAccountException extends RuntimeException {
  public InvalidAccountException(String message) {
    super(message);
  }
}
