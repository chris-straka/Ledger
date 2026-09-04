package dev.straka.ledger.account.domain;

/** Journal side of one entry. Signed journal arithmetic treats DEBIT as + and CREDIT as −. */
public enum EntrySide {
  DEBIT,
  CREDIT
}
