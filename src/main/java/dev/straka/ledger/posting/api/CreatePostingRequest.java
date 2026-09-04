package dev.straka.ledger.posting.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Transport shape only. Currency is deliberately absent from every line: it is derived from the
 * accounts inside the transaction, never trusted from the client.
 */
public record CreatePostingRequest(
    @NotBlank @Size(max = 500) String description,
    @NotNull Instant effectiveAt,
    @Size(min = 2, max = 100) @Valid List<EntryLine> lines) {
  public record EntryLine(
      @NotNull UUID accountId, @NotBlank String side, @NotBlank String amountMinor) {}
}
