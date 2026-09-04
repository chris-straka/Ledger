package dev.straka.ledger.posting.domain;

import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.AccountType;
import dev.straka.ledger.account.domain.CurrencyCode;
import dev.straka.ledger.account.domain.EntrySide;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A validated, immutable posting draft. Construction enforces the full balance definition from
 * PORT.md section 2: 2–100 ordered lines, at least two distinct accounts, one currency, and a zero
 * signed journal sum (debits +, credits −) computed in {@link BigInteger} so totals that overflow
 * {@code long} addition are still judged exactly. The deferred database trigger re-proves all of
 * this at commit for writers that bypass Java.
 */
public record PostingDraft(CurrencyCode currency, List<PostingLine> lines) {
  public PostingDraft {
    if (currency == null) {
      throw new InvalidPostingException("posting currency is required");
    }
    if (lines == null || lines.size() < 2 || lines.size() > 100) {
      throw new InvalidPostingException(
          "posting must declare 2-100 entry lines, got " + (lines == null ? 0 : lines.size()));
    }
    // Null elements are rejected here because List.copyOf would throw a bare NPE instead.
    for (PostingLine line : lines) {
      if (line == null) {
        throw new InvalidPostingException("posting lines must not be null");
      }
    }
    lines = List.copyOf(lines);
    Set<AccountId> accounts = new HashSet<>();
    BigInteger debits = BigInteger.ZERO;
    BigInteger credits = BigInteger.ZERO;
    for (PostingLine line : lines) {
      accounts.add(line.accountId());
      BigInteger amount = BigInteger.valueOf(line.amount().minorUnits());
      if (line.side() == EntrySide.DEBIT) {
        debits = debits.add(amount);
      } else {
        credits = credits.add(amount);
      }
    }
    if (accounts.size() < 2) {
      throw new InvalidPostingException("posting must touch at least two distinct accounts");
    }
    if (!debits.equals(credits)) {
      throw new InvalidPostingException(
          "posting does not balance: debits " + debits + " != credits " + credits);
    }
  }

  /** Signed journal sum of the draft: always zero by construction. */
  public BigInteger signedSum() {
    BigInteger sum = BigInteger.ZERO;
    for (PostingLine line : lines) {
      BigInteger amount = BigInteger.valueOf(line.amount().minorUnits());
      sum = line.side() == EntrySide.DEBIT ? sum.add(amount) : sum.subtract(amount);
    }
    return sum;
  }

  /**
   * Normal-side delta per account: how much each account's reported balance moves if this draft
   * commits. Positive means the balance increases. Several lines for one account aggregate into a
   * single delta, so line order cannot change the overdraft verdict.
   */
  public Map<AccountId, BigInteger> deltasByAccount(Function<AccountId, AccountType> types) {
    Map<AccountId, BigInteger> deltas = new HashMap<>();
    for (PostingLine line : lines) {
      AccountType type = types.apply(line.accountId());
      BigInteger signed = BigInteger.valueOf(line.amount().minorUnits());
      if (line.side() != type.normalSide()) {
        signed = signed.negate();
      }
      deltas.merge(line.accountId(), signed, BigInteger::add);
    }
    return Map.copyOf(deltas);
  }
}
