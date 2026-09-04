package dev.straka.ledger.account.domain;

/**
 * Currency code, syntactically validated here and checked for V1 support against the {@code
 * ledger_currency} reference table by the database foreign key. Only already-converted minor units
 * cross this boundary — never an amount in one currency added to another.
 */
public record CurrencyCode(String code) {
  public CurrencyCode {
    if (code == null || !code.matches("[A-Z]{3}")) {
      throw new InvalidAccountException("currency code must be three uppercase letters: " + code);
    }
  }
}
