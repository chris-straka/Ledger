package dev.straka.ledger.account.api;

/**
 * Record reporting a derived normal-side balance. The minor-unit value is a base-10 string because
 * JSON numbers cannot represent every 64-bit integer exactly in common clients.
 */
public record BalanceResponse(String accountId, String currency, String balanceMinor) {}
