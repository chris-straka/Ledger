package dev.straka.ledger.account.api;

import java.time.Instant;
import java.util.List;

/**
 * Record holding one page of an account's journal lines in immutable order. Amounts are integer
 * strings; {@code nextCursor} is absent on the last page.
 */
public record EntryPageResponse(List<EntryResponse> entries, String nextCursor) {
  /** One journal line in transport types. */
  public record EntryResponse(
      String postingId,
      int lineNumber,
      String side,
      String amountMinorUnits,
      String currency,
      String postingKind,
      String description,
      Instant recordedAt) {}
}
