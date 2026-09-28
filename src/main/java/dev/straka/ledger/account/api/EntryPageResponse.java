package dev.straka.ledger.account.api;

import java.time.Instant;
import java.util.List;

/**
 * Record holding one page of an account's journal entries in immutable order. {@code nextCursor} is
 * absent on the last page.
 */
public record EntryPageResponse(List<EntryResponse> entries, String nextCursor) {
  /** One journal entry in transport types. */
  public record EntryResponse(
      String postingId,
      int entryNumber,
      String side,
      String amountMinorUnits, // int64 string
      String currency,
      String postingKind,
      String description,
      Instant recordedAt) {}
}
