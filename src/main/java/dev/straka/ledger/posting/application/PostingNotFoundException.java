package dev.straka.ledger.posting.application;

/** A requested posting does not exist. Maps to HTTP 404. */
public class PostingNotFoundException extends RuntimeException {
  public PostingNotFoundException(String message) {
    super(message);
  }
}
