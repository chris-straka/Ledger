package dev.straka.ledger.account.domain;

/**
 * Enum classifying accounts. The type fixes the account's normal side: an entry increases the
 * reported balance when its side matches the normal side and decreases it otherwise.
 */
public enum AccountType {
  ASSET,
  EXPENSE,
  LIABILITY,
  EQUITY,
  REVENUE;

  public EntrySide normalSide() {
    return switch (this) {
      case ASSET, EXPENSE -> EntrySide.DEBIT;
      case LIABILITY, EQUITY, REVENUE -> EntrySide.CREDIT;
    };
  }
}
