package dev.straka.ledger.posting.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Record carrying the create-posting transport shape. Currency is deliberately absent from every
 * line: it is derived from the accounts inside the transaction, never trusted from the client.
 */
public record CreatePostingRequest(
    @NotBlank @Size(max = 500) String description,
    @NotNull Instant effectiveAt,
    @NotNull @Size(min = 2, max = 100) @Valid List<EntryLine> lines) {
  /**
   * One requested entry line: raw account ID, side, and base-10 amount, validated on the way in.
   */
  public record EntryLine(
      @NotNull UUID accountId, @NotBlank String side, @NotBlank String amountMinor) {}
}
