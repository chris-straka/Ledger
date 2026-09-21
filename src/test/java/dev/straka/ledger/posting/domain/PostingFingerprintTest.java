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

  private static PostingEntry entry(String account, EntrySide side, long amount) {
    return new PostingEntry(
        new AccountId(UUID.nameUUIDFromBytes(account.getBytes())), side, new EntryAmount(amount));
  }

  private static List<PostingEntry> entries() {
    return List.of(
        entry("cash", EntrySide.DEBIT, 10_000), entry("capital", EntrySide.CREDIT, 10_000));
  }

  @Test
  void identicalRequestsShareAFingerprint() {
    assertEquals(
        PostingFingerprint.v1Standard("opening", WHEN, entries()),
        PostingFingerprint.v1Standard("opening", WHEN, entries()));
  }

  @Test
  void everySemanticFieldMovesTheDigest() {
    PostingFingerprint base = PostingFingerprint.v1Standard("opening", WHEN, entries());
    assertNotEquals(base, PostingFingerprint.v1Standard("opening!", WHEN, entries()));
    assertNotEquals(base, PostingFingerprint.v1Standard("opening", WHEN.plusSeconds(1), entries()));
    assertNotEquals(
        base,
        PostingFingerprint.v1Standard(
            "opening",
            WHEN,
            List.of(
                entry("cash", EntrySide.DEBIT, 10_001),
                entry("capital", EntrySide.CREDIT, 10_001))));
    assertNotEquals(
        base,
        PostingFingerprint.v1Standard(
            "opening",
            WHEN,
            List.of(
                entry("other", EntrySide.DEBIT, 10_000),
                entry("capital", EntrySide.CREDIT, 10_000))));
  }

  @Test
  void entryOrderIsSignificant() {
    // Order becomes the immutable entry_number, so swapping entries is a different request.
    List<PostingEntry> swapped =
        List.of(entry("capital", EntrySide.CREDIT, 10_000), entry("cash", EntrySide.DEBIT, 10_000));
    assertNotEquals(
        PostingFingerprint.v1Standard("opening", WHEN, entries()),
        PostingFingerprint.v1Standard("opening", WHEN, swapped));
  }

  @Test
  void reversalFingerprintsBindTargetReasonAndInstant() {
    UUID target = UUID.randomUUID();
    assertEquals(
        PostingFingerprint.v1Reversal(target, "oops", WHEN),
        PostingFingerprint.v1Reversal(target, "oops", WHEN));
    assertNotEquals(
        PostingFingerprint.v1Reversal(target, "oops", WHEN),
        PostingFingerprint.v1Reversal(UUID.randomUUID(), "oops", WHEN));
    assertNotEquals(
        PostingFingerprint.v1Reversal(target, "oops", WHEN),
        PostingFingerprint.v1Reversal(target, "different", WHEN));
    assertNotEquals(
        PostingFingerprint.v1Reversal(target, "oops", WHEN),
        PostingFingerprint.v1Reversal(target, "oops", WHEN.plusSeconds(1)));
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
