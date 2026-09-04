package dev.straka.ledger.posting.domain;

/**
 * Client-supplied idempotency key. Case-sensitive and globally scoped (V1 has one ledger and one
 * tenant): never trimmed, never case-folded, rejected unless it already matches the alphabet.
 */
public record IdempotencyKey(String value) {
  public IdempotencyKey {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new InvalidIdempotencyKeyException("idempotency key must match [A-Za-z0-9._:-]{1,128}");
    }
  }
}
