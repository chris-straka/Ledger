package dev.straka.ledger.account.domain;

/** Enum for the journal side of one entry. Signed journal arithmetic treats DEBIT as + and CREDIT as −. */
public enum EntrySide {
  DEBIT,
  CREDIT
}
