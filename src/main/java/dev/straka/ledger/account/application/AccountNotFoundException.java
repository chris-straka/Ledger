package dev.straka.ledger.account.application;

/** A requested account does not exist. Maps to HTTP 404. */
public class AccountNotFoundException extends RuntimeException {
  public AccountNotFoundException(String message) {
    super(message);
  }
}
