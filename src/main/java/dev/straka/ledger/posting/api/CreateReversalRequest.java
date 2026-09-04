package dev.straka.ledger.posting.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * A reversal asks for a reason and an effective time only. The server constructs the inverse lines;
 * clients cannot relabel an arbitrary posting as a reversal.
 */
public record CreateReversalRequest(
    @NotBlank @Size(max = 500) String reason, @NotNull Instant effectiveAt) {}
