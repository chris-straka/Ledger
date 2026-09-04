package dev.straka.ledger.account.application;

import dev.straka.ledger.account.persistence.AccountRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One page of an account's journal lines with the cursor for the next page. The service maps
 * persistence rows here; the controller maps this to the transport shape.
 */
public record EntryPage(List<Entry> entries, String nextCursor) {
  public record Entry(
      UUID postingId,
      int lineNumber,
      String side,
      String amountMinor,
      String currency,
      String postingKind,
      String description,
      Instant recordedAt) {
    static Entry from(AccountRepository.AccountEntry row) {
      return new Entry(
          row.postingId(),
          row.lineNumber(),
          row.side(),
          row.amountMinor(),
          row.currency(),
          row.postingKind(),
          row.description(),
          row.recordedAt());
    }
  }
}
