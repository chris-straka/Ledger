package dev.straka.ledger.account.domain;

import java.math.BigInteger;

/**
 * Normal-side balance math, shared by tests and (later) the application overdraft check. Pure
 * arithmetic on already-fetched totals: an entry increases the balance when its side matches the
 * account's normal side. Sums stay in {@link BigInteger} so near-{@code long} totals cannot
 * overflow into a false balance.
 */
public final class Balances {
  private Balances() {}

  public static BigInteger of(AccountType type, BigInteger debits, BigInteger credits) {
    if (debits == null || credits == null) {
      throw new InvalidAccountException("debit and credit totals are required");
    }
    return switch (type.normalSide()) {
      case DEBIT -> debits.subtract(credits);
      case CREDIT -> credits.subtract(debits);
    };
  }
}
