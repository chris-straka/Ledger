package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Row-level CHECKs and key shapes: malformed single rows fail at the statement, before any deferred
 * trigger runs. Each case asserts SQLSTATE 23514 and the stable constraint name.
 */
class PostingChecksTest extends LedgerIntegrationTest {

  private static SQLException statementFailure(Connection conn, String sql, Object... params) {
    try (PreparedStatement ps = conn.prepareStatement(sql)) {
      for (int i = 0; i < params.length; i++) {
        ps.setObject(i + 1, params[i]);
      }
      ps.executeUpdate();
    } catch (SQLException e) {
      return e;
    }
    return null;
  }

  private static void assertStatementFailsWith(SQLException failure, String constraint) {
    assertTrue(failure != null, "expected statement to fail but it succeeded");
    assertEquals("23514", failure.getSQLState(), () -> "wrong SQLSTATE: " + failure.getMessage());
    assertTrue(
        failure.getMessage() != null && failure.getMessage().contains(constraint),
        () -> "message <" + failure.getMessage() + "> lacks <" + constraint + ">");
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, -9_999_999_999L})
  void nonPositiveAmountIsRejected(long amount) throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "bad amount");
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_entry (posting_id, line_number, account_id, currency_code, side,"
                  + " amount_minor) VALUES (?, 1, ?, 'CAD', 'DEBIT', ?)",
              posting,
              cash,
              amount);
      assertStatementFailsWith(failure, "ledger_entry_amount_positive");
      conn.rollback();
    }
  }

  @Test
  void unknownSideIsRejected() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "bad side");
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_entry (posting_id, line_number, account_id, currency_code, side,"
                  + " amount_minor) VALUES (?, 1, ?, 'CAD', 'DEBITX', 100)",
              posting,
              cash);
      assertStatementFailsWith(failure, "ledger_entry_side_allowed");
      conn.rollback();
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 1, 101, 500})
  void outOfRangeDeclaredCountIsRejected(int count) throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      String key = newKey();
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_posting (idempotency_key, request_fingerprint, posting_kind,"
                  + " currency_code, entry_count, description, effective_at) VALUES (?, ?,"
                  + " 'STANDARD', 'CAD', ?, 'bad count', now())",
              key,
              fingerprint(key),
              count);
      assertStatementFailsWith(failure, "ledger_posting_entry_count_range");
      conn.rollback();
    }
  }

  @Test
  void malformedIdempotencyKeyIsRejected() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      String key = "has space and CAPS-ok-but-space";
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_posting (idempotency_key, request_fingerprint, posting_kind,"
                  + " currency_code, entry_count, description, effective_at) VALUES (?, ?,"
                  + " 'STANDARD', 'CAD', 2, 'bad key', now())",
              key,
              fingerprint(key));
      assertStatementFailsWith(failure, "ledger_posting_key_charset");
      conn.rollback();
    }
  }

  @Test
  void blankDescriptionIsRejected() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      String key = newKey();
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_posting (idempotency_key, request_fingerprint, posting_kind,"
                  + " currency_code, entry_count, description, effective_at) VALUES (?, ?,"
                  + " 'STANDARD', 'CAD', 2, '', now())",
              key,
              fingerprint(key));
      assertStatementFailsWith(failure, "ledger_posting_description_shape");
      conn.rollback();
    }
  }

  @Test
  void shortFingerprintIsRejected() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      String key = newKey();
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_posting (idempotency_key, request_fingerprint, posting_kind,"
                  + " currency_code, entry_count, description, effective_at) VALUES (?, ?,"
                  + " 'STANDARD', 'CAD', 2, 'short hash', now())",
              key,
              new byte[31]);
      assertStatementFailsWith(failure, "ledger_posting_fingerprint_sha256");
      conn.rollback();
    }
  }

  @Test
  void standardPostingWithReversalTargetIsRejected() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      String key = newKey();
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_posting (idempotency_key, request_fingerprint, posting_kind,"
                  + " reverses_posting_id, currency_code, entry_count, description, effective_at)"
                  + " VALUES (?, ?, 'STANDARD', ?, 'CAD', 2, 'fake reversal', now())",
              key,
              fingerprint(key),
              UUID.randomUUID());
      assertStatementFailsWith(failure, "ledger_posting_reversal_coherence");
      conn.rollback();
    }
  }

  @Test
  void accountTypeAllowlistsIsEnforced() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_account (account_code, name, currency_code, account_type,"
                  + " overdraft_policy) VALUES ('x', 'X', 'CAD', 'WALLET', 'DENY')");
      assertStatementFailsWith(failure, "ledger_account_type_allowed");
      conn.rollback();
    }
  }

  @Test
  void junitParamsAreAvailable() {
    // Guard: if junit-jupiter-params ever drops from the BOM, the parameterized
    // tests above silently stop running. This fails loudly instead.
    try {
      Class.forName("org.junit.jupiter.params.ParameterizedTest");
    } catch (ClassNotFoundException e) {
      fail("junit-jupiter-params missing from test classpath");
    }
  }
}
