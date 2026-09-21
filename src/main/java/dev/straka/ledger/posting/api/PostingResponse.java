package dev.straka.ledger.posting.api;

import dev.straka.ledger.posting.persistence.PostingRepository;
import java.time.Instant;
import java.util.List;

/**
 * Record describing a committed posting with entries in immutable entry order. Amounts are integer
 * strings.
 */
public record PostingResponse(
    String postingId,
    String kind,
    String reversesPostingId,
    String currency,
    int entryCount,
    String description,
    Instant effectiveAt,
    Instant recordedAt,
    List<EntryResponse> entries) {

  public record EntryResponse(
      int entryNumber, String accountId, String currency, String side, String amountMinorUnits) {}

  public static PostingResponse from(PostingRepository.StoredPosting posting) {
    return new PostingResponse(
        posting.id().toString(),
        posting.kind().name(),
        posting.reversesPostingId() == null ? null : posting.reversesPostingId().toString(),
        posting.currency().code(),
        posting.entryCount(),
        posting.description(),
        posting.effectiveAt(),
        posting.recordedAt(),
        posting.entries().stream()
            .map(
                e ->
                    new EntryResponse(
                        e.entryNumber(),
                        e.accountId().toString(),
                        e.currency().code(),
                        e.side().name(),
                        Long.toString(e.amountMinorUnits())))
            .toList());
  }
}
