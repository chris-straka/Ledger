package dev.straka.ledger.account.api;

/**
 * balanceMinorUnits is a base-10 string because JSON numbers can't represent every 64-bit int in
 * many clients.
 */
public record BalanceResponse(String accountId, String currency, String balanceMinorUnits) {}
