package dev.straka.ledger.posting.domain;

import java.util.regex.Pattern;

/**
 * Record holding a client-supplied idempotency key. Case-sensitive and globally scoped (V1 has
 * one ledger and one tenant): never trimmed, never case-folded, rejected unless it already
 * matches the alphabet.
 */
public record IdempotencyKey(String value) {
  private static final String REGEX = "[A-Za-z0-9._:-]{1,128}";
  private static final Pattern PATTERN = Pattern.compile(REGEX);

  public IdempotencyKey {
    if (value == null || !PATTERN.matcher(value).matches()) {
      throw new InvalidIdempotencyKeyException("idempotency key must match " + REGEX);
    }
  }
}
