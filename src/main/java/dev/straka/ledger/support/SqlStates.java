package dev.straka.ledger.support;

import java.sql.SQLException;
import java.util.Objects;

/**
 * The PG error codes (SQLSTATEs) this ledger looks for. A SQLSTATE is a 5-character code carried by
 * driver exceptions; obtained from the driver-neutral {@link SQLException}
 */
public final class SqlStates {

  /** Unknown currency, account, or posting reference. */
  public static final String FOREIGN_KEY_VIOLATION_CODE = "23503";

  /** Raised by the deferred ledger triggers. */
  public static final String CHECK_VIOLATION_CODE = "23514";

  /** PG aborted the current TX: a SERIALIZABLE dependency could not serialize. */
  public static final String SERIALIZATION_FAILURE_CODE = "40001";

  /** PG throws this when the current TX is aborted due to a deadlock. */
  public static final String DEADLOCK_DETECTED_CODE = "40P01";

  private SqlStates() {}

  /**
   * True when any {@link SQLException} in the exception chain carries one of the given codes.
   * Wrappers nest the state, so every cause is inspected.
   */
  public static boolean hasSqlState(Throwable failure, String... codes) {
    Throwable cause = failure;

    while (cause != null) {
      if (cause instanceof SQLException sql) {
        for (String code : codes) {
          if (Objects.equals(sql.getSQLState(), code)) return true;
        }
      }
      cause = cause.getCause();
    }
    return false;
  }
}
