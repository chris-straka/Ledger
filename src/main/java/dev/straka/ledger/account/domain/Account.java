package dev.straka.ledger.account.domain;

import java.time.Instant;

/**
 * Record describing an immutable account. No balance field: value enters through postings, and the
 * balance is always derived from entries.
 */
public record Account(
    AccountId id,
    String code,
    String name,
    CurrencyCode currency,
    AccountType type,
    OverdraftPolicy overdraftPolicy,
    Instant createdAt) {

  public Account {
    if (id == null) throw new InvalidAccountException("account id is required");

    if (code == null || code.isBlank() || code.length() > 64)
      throw new InvalidAccountException("account code must be 1-64 characters");

    if (name == null || name.isBlank() || name.length() > 200 || containsControl(name))
      throw new InvalidAccountException("account name must be 1-200 characters without controls");

    if (currency == null) throw new InvalidAccountException("currency is required");
    if (type == null) throw new InvalidAccountException("account type is required");
    if (overdraftPolicy == null) throw new InvalidAccountException("overdraft policy is required");
    if (createdAt == null) throw new InvalidAccountException("creation timestamp is required");
  }

  private static boolean containsControl(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c <= 0x1F || c == 0x7F) return true;
    }
    return false;
  }
}
