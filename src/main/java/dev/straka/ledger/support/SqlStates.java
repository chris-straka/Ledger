package dev.straka.ledger.support;

import java.sql.SQLException;
import java.util.Objects;

/**
 * The Postgres error codes (SQLSTATEs) this ledger looks for. A SQLSTATE is a 5-character code
 * carried by driver exceptions; it is obtained from the driver-neutral {@link SQLException} so
 * callers never import the driver.
 */
public final class SqlStates {

  /** Foreign-key violation: unknown currency, account, or posting reference. */
  public static final String FOREIGN_KEY_VIOLATION_CODE = "23503";

  /** Check-constraint violation: raised here by the deferred ledger triggers. */
  public static final String CHECK_VIOLATION_CODE = "23514";

  /** Serialization failure: the SERIALIZABLE retry signal. */
  public static final String SERIALIZATION_FAILURE_CODE = "40001";

  /** Deadlock detected: retried like a serialization failure. */
  public static final String DEADLOCK_DETECTED_CODE = "40P01";

  private SqlStates() {}

  /**
   * True when any {@link SQLException} in the chain carries one of the given codes. Any link may
   * carry the state (batch and transaction wrappers nest it), so every link is inspected.
   */
  public static boolean hasState(Throwable failure, String... codes) {
    Throwable cause = failure;

    while (cause != null) {
      if (cause instanceof SQLException sql) {
        for (String code : codes) {
          if (Objects.equals(sql.getSQLState(), code)) {
            return true;
          }
        }
      }
      cause = cause.getCause();
    }
    return false;
  }
}
