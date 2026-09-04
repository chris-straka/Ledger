package dev.straka.ledger.account.api;

import dev.straka.ledger.account.domain.Account;
import java.time.Instant;

/** Immutable account metadata. Balances never appear here; they are derived reads. */
public record AccountResponse(
    String accountId,
    String code,
    String name,
    String currency,
    String type,
    String overdraftPolicy,
    Instant createdAt) {
  public static AccountResponse from(Account account) {
    return new AccountResponse(
        account.id().toString(),
        account.code(),
        account.name(),
        account.currency().code(),
        account.type().name(),
        account.overdraftPolicy().name(),
        account.createdAt());
  }
}
