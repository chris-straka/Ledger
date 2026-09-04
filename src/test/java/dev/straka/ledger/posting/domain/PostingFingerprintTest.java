package dev.straka.ledger.posting.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.CurrencyCode;
import dev.straka.ledger.account.domain.EntrySide;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Fingerprint stability and sensitivity. Equality here is what separates an idempotent replay from
 * a conflicting reuse, so every semantic field must move the digest and nothing else may.
 */
class PostingFingerprintTest {

  private static final CurrencyCode CAD = new CurrencyCode("CAD");
  private static final Instant WHEN = Instant.parse("2026-09-04T12:00:00Z");

  private static PostingLine line(String account, EntrySide side, long amount) {
    return new PostingLine(
        new AccountId(UUID.nameUUIDFromBytes(account.getBytes())), side, new EntryAmount(amount));
  }

  private static List<PostingLine> lines() {
    return List.of(
        line("cash", EntrySide.DEBIT, 10_000), line("capital", EntrySide.CREDIT, 10_000));
  }

  @Test
  void identicalRequestsShareAFingerprint() {
    assertEquals(
        PostingFingerprint.v1Standard("opening", WHEN, lines()),
        PostingFingerprint.v1Standard("opening", WHEN, lines()));
  }

  @Test
  void everySemanticFieldMovesTheDigest() {
    PostingFingerprint base = PostingFingerprint.v1Standard("opening", WHEN, lines());
    assertNotEquals(base, PostingFingerprint.v1Standard("opening!", WHEN, lines()));
    assertNotEquals(base, PostingFingerprint.v1Standard("opening", WHEN.plusSeconds(1), lines()));
    assertNotEquals(
        base,
        PostingFingerprint.v1Standard(
            "opening",
            WHEN,
            List.of(
                line("cash", EntrySide.DEBIT, 10_001), line("capital", EntrySide.CREDIT, 10_001))));
    assertNotEquals(
        base,
        PostingFingerprint.v1Standard(
            "opening",
            WHEN,
            List.of(
                line("other", EntrySide.DEBIT, 10_000),
                line("capital", EntrySide.CREDIT, 10_000))));
  }

  @Test
  void lineOrderIsSignificant() {
    // Order becomes the immutable line_number, so swapping lines is a different request.
    List<PostingLine> swapped =
        List.of(line("capital", EntrySide.CREDIT, 10_000), line("cash", EntrySide.DEBIT, 10_000));
    assertNotEquals(
        PostingFingerprint.v1Standard("opening", WHEN, lines()),
        PostingFingerprint.v1Standard("opening", WHEN, swapped));
  }

  @Test
  void keysAreValidated() {
    assertEquals("abc-123._:X", new IdempotencyKey("abc-123._:X").value());
    assertThrows(InvalidIdempotencyKeyException.class, () -> new IdempotencyKey("has space"));
    assertThrows(InvalidIdempotencyKeyException.class, () -> new IdempotencyKey("UPPER lower"));
    assertThrows(InvalidIdempotencyKeyException.class, () -> new IdempotencyKey(""));
    assertThrows(InvalidIdempotencyKeyException.class, () -> new IdempotencyKey(null));
    assertThrows(InvalidIdempotencyKeyException.class, () -> new IdempotencyKey("x".repeat(129)));
  }
}
