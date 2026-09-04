package dev.straka.ledger.posting.application;

/** A DENY account would go negative. Maps to HTTP 409: state conflict, not malformed input. */
public class OverdraftRejectedException extends RuntimeException {
  public OverdraftRejectedException(String message) {
    super(message);
  }
}
