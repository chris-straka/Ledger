package dev.straka.ledger.account.domain;

/**
 * Enum holding the explicit overdraft policy per account. {@code DENY} means the projected
 * normal-side balance cannot go negative; {@code ALLOW} means a negative balance is valid.
 */
public enum OverdraftPolicy {
  ALLOW,
  DENY
}
