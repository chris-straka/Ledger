package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for direct-JDBC commit tests. Every test connects as {@code ledger_app}, drives explicit
 * transactions to a real commit, and asserts SQLSTATE plus the stable constraint/message name —
 * never an English server message. No Spring test transaction wraps these: an auto-rolled-back
 * transaction cannot prove a deferred commit-time constraint.
 */
abstract class LedgerIntegrationTest {

  @BeforeAll
  static void startDb() {
    LedgerDB.start();
  }

  @BeforeEach
  void cleanJournal() throws SQLException {
    LedgerDB.cleanJournal();
  }

  @AfterEach
  void auditConservation() throws SQLException {
    List<String> violations = LedgerDB.conservationViolations();
    assertTrue(violations.isEmpty(), () -> "conservation violated: " + violations);
  }

  static String newKey() {
    return "test-" + UUID.randomUUID();
  }

  static byte[] fingerprint(String key) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static UUID insertAccount(
      Connection conn, String code, String type, String currency, String overdraft)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO ledger_account (account_code, name, currency_code, account_type,"
                + " overdraft_policy) VALUES (?, ?, ?, ?, ?) RETURNING id")) {
      ps.setString(1, code);
      ps.setString(2, code);
      ps.setString(3, currency);
      ps.setString(4, type);
      ps.setString(5, overdraft);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  static UUID insertPosting(
      Connection conn,
      String key,
      String kind,
      UUID reverses,
      String currency,
      int count,
      String description)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO ledger_posting (idempotency_key, request_fingerprint, posting_kind,"
                + " reverses_posting_id, currency_code, entry_count, description, effective_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
      ps.setString(1, key);
      ps.setBytes(2, fingerprint(key));
      ps.setString(3, kind);
      ps.setObject(4, reverses);
      ps.setString(5, currency);
      ps.setInt(6, count);
      ps.setString(7, description);
      ps.setTimestamp(8, Timestamp.from(Instant.now()));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getObject(1, UUID.class);
      }
    }
  }

  static void insertEntry(
      Connection conn,
      UUID postingId,
      int entry,
      UUID accountId,
      String currency,
      String side,
      long amount)
      throws SQLException {
    try (PreparedStatement ps =
        conn.prepareStatement(
            "INSERT INTO ledger_entry (posting_id, entry_number, account_id, currency_code, side,"
                + " amount_minor_units) VALUES (?, ?, ?, ?, ?, ?)")) {
      ps.setObject(1, postingId);
      ps.setInt(2, entry);
      ps.setObject(3, accountId);
      ps.setString(4, currency);
      ps.setString(5, side);
      ps.setLong(6, amount);
      ps.executeUpdate();
    }
  }

  static long tableCount(Connection conn, String table) throws SQLException {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  static SQLException commitFailure(Connection conn) {
    try {
      conn.commit();
    } catch (SQLException e) {
      return e;
    }
    return null;
  }

  static void assertCommitFailsWith(Connection conn, String sqlState, String messagePrefix)
      throws SQLException {
    SQLException failure = commitFailure(conn);
    assertTrue(failure != null, "expected commit to fail but it succeeded");
    assertEquals(sqlState, failure.getSQLState(), () -> "wrong SQLSTATE: " + failure.getMessage());
    assertTrue(
        failure.getMessage() != null && failure.getMessage().contains(messagePrefix),
        () -> "message <" + failure.getMessage() + "> lacks <" + messagePrefix + ">");
  }
}
