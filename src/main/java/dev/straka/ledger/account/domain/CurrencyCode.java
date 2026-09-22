package dev.straka.ledger.account.domain;

import java.util.regex.Pattern;

/**
 * Record holding a three-letter currency code, syntactically validated here and checked for V1
 * support against the {@code ledger_currency} reference table by the DB foreign key. Only
 * already-converted minor units cross this boundary — never an amount in one currency added to
 * another.
 */
public record CurrencyCode(String code) {
  private static final String REGEX = "[A-Z]{3}";
  private static final Pattern PATTERN = Pattern.compile(REGEX);

  public CurrencyCode {
    if (code == null || !PATTERN.matcher(code).matches())
      throw new InvalidAccountException("currency code must be three uppercase letters: " + code);
  }
}
