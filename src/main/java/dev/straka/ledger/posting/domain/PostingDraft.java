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
 * A validated, immutable posting draft: the all-or-nothing unit of the ledger.
 *
 * <p>A draft is accepted only when every entry agrees:
 *
 * <ul>
 *   <li>2–100 ordered entries, across at least two distinct accounts;
 *   <li>one currency for the whole posting;
 *   <li>debits (+) equal credits (-), so the signed journal sum is zero.
 * </ul>
 *
 * <p>DB re-checks zero sum at commit, so non-Java writers cannot break it either.
 */
public record PostingDraft(CurrencyCode currency, List<PostingEntry> entries) {
  /**
   * Checks the {@link PostingDraft} acceptance rules. Totals are compared in {@link BigInteger} so
   * sums near the {@code long} limit are still judged exactly.
   *
   * @throws InvalidPostingException if any check fails
   */
  public PostingDraft {
    if (currency == null) throw new InvalidPostingException("posting currency is required");

    if (entries == null || entries.size() < 2 || entries.size() > 100)
      throw new InvalidPostingException(
          "posting must declare 2-100 entry entries, got "
              + (entries == null ? 0 : entries.size()));

    // Null elements are rejected here because List.copyOf would throw a bare NPE instead.
    for (PostingEntry entry : entries) {
      if (entry == null) throw new InvalidPostingException("posting entries must not be null");
    }

    entries = List.copyOf(entries);

    Set<AccountId> accounts = new HashSet<>();
    BigInteger debits = BigInteger.ZERO;
    BigInteger credits = BigInteger.ZERO;
    for (PostingEntry entry : entries) {
      accounts.add(entry.accountId());
      BigInteger amount = BigInteger.valueOf(entry.amount().minorUnits());
      if (entry.side() == EntrySide.DEBIT) {
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
    for (PostingEntry entry : entries) {
      BigInteger amount = BigInteger.valueOf(entry.amount().minorUnits());
      sum = entry.side() == EntrySide.DEBIT ? sum.add(amount) : sum.subtract(amount);
    }
    return sum;
  }

  /**
   * Normal-side delta per account: how much each account's reported balance moves if this draft
   * commits. Positive means the balance increases. Several entries for one account aggregate into a
   * single delta, so entry order cannot change the overdraft verdict.
   */
  public Map<AccountId, BigInteger> deltasByAccount(Function<AccountId, AccountType> types) {
    Map<AccountId, BigInteger> deltas = new HashMap<>();
    for (PostingEntry entry : entries) {
      AccountType type = types.apply(entry.accountId());
      BigInteger signed = BigInteger.valueOf(entry.amount().minorUnits());
      if (entry.side() != type.normalSide()) {
        signed = signed.negate();
      }
      deltas.merge(entry.accountId(), signed, BigInteger::add);
    }
    return Map.copyOf(deltas);
  }
}
