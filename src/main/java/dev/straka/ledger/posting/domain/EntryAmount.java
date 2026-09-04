package dev.straka.ledger.posting.domain;

/**
 * One entry's amount in the currency's minor units. Strictly positive: zero and negatives are
 * rejected here and again by the database CHECK. A {@code long} holds any single amount; sums use
 * {@link java.math.BigInteger} so totals near the {@code long} limit cannot overflow.
 */
public record EntryAmount(long minorUnits) {
  public EntryAmount {
    if (minorUnits <= 0) {
      throw new InvalidPostingException("entry amount must be positive: " + minorUnits);
    }
  }

  /** Parses the HTTP wire form: a base-10 integer string, no exponent or decimal point. */
  public static EntryAmount parse(String raw) {
    if (raw == null || !raw.matches("[0-9]+")) {
      throw new InvalidPostingException("amount must be a base-10 integer string: " + raw);
    }
    try {
      return new EntryAmount(Long.parseLong(raw));
    } catch (NumberFormatException e) {
      throw new InvalidPostingException("amount exceeds 64-bit range: " + raw);
    }
  }
}
