package dev.straka.ledger.account.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Record carrying the create-account transport shape. Jakarta validation rejects malformed syntax
 * (HTTP 400); well-formed but invalid values — unknown type, unsupported currency — fall through to
 * the domain and DB, which answer HTTP 422.
 */
public record CreateAccountRequest(
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 200) String name,
    @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
    @NotBlank String type,
    @NotBlank String overdraftPolicy) {}
