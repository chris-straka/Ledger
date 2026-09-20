package dev.straka.ledger.posting.domain;

import java.util.regex.Pattern;

/**
 * Record holding one entry's amount in the currency's minor units. Strictly positive: zero and
 * negatives are rejected here and again by the DB CHECK. A {@code long} holds any single amount;
 * sums use {@link java.math.BigInteger} so totals near the {@code long} limit cannot overflow.
 */
public record EntryAmount(long minorUnits) {
  private static final String REGEX = "[0-9]+";
  private static final Pattern PATTERN = Pattern.compile(REGEX);

  public EntryAmount {
    if (minorUnits <= 0) {
      throw new InvalidPostingException("entry amount must be positive: " + minorUnits);
    }
  }

  /** Parses the HTTP wire form: a base-10 integer string, no exponent or decimal point. */
  public static EntryAmount parse(String raw) {
    if (raw == null || !PATTERN.matcher(raw).matches()) {
      throw new InvalidPostingException("amount must be a base-10 integer string: " + raw);
    }

    try {
      return new EntryAmount(Long.parseLong(raw));
    } catch (NumberFormatException e) {
      throw new InvalidPostingException("amount exceeds 64-bit range: " + raw);
    }
  }
}
