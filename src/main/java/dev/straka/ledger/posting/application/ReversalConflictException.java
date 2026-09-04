package dev.straka.ledger.posting.application;

/**
 * A reversal was refused: target already reversed, target is itself a reversal, or the reversal
 * would overdraw. Maps to HTTP 409.
 */
public class ReversalConflictException extends RuntimeException {
  public ReversalConflictException(String message) {
    super(message);
  }
}
