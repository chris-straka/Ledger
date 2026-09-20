package dev.straka.ledger.account.domain;

import java.util.UUID;

/** Record identifying an immutable account. Never reused, never changed. */
public record AccountId(UUID value) {
  public AccountId {
    if (value == null) {
      throw new InvalidAccountException("account id is required");
    }
  }

  public static AccountId random() {
    return new AccountId(UUID.randomUUID());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
