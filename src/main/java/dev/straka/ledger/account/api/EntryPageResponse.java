package dev.straka.ledger.account.api;

import java.time.Instant;
import java.util.List;

/**
 * One page of an account's journal lines in immutable order. Amounts are integer strings; {@code
 * nextCursor} is absent on the last page.
 */
public record EntryPageResponse(List<EntryResponse> entries, String nextCursor) {
  public record EntryResponse(
      String postingId,
      int lineNumber,
      String side,
      String amountMinor,
      String currency,
      String postingKind,
      String description,
      Instant recordedAt) {}
}
