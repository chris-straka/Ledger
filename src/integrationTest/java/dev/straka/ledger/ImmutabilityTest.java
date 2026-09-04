package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariant L3: no UPDATE or DELETE on journal tables, ever. The mechanism is revoked grants — the
 * runtime role holds only CONNECT, schema USAGE, SELECT, and narrow column INSERTs — so the
 * database refuses with SQLSTATE 42501. Immutable-row triggers backstop a mis-issued grant; the
 * owner path proves they exist.
 */
class ImmutabilityTest extends LedgerIntegrationTest {

  private static SQLException statementFailure(Connection conn, String sql) {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute(sql);
    } catch (SQLException e) {
      return e;
    }
    return null;
  }

  @Test
  void runtimeRoleCannotUpdateJournal() throws Exception {
    UUID posting;
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, posting, 2, capital, "CAD", "CREDIT", 10_000);
      conn.commit();
    }
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      SQLException failure =
          statementFailure(
              conn,
              "UPDATE ledger_entry SET amount_minor = 1 WHERE posting_id = '" + posting + "'");
      assertTrue(failure != null, "UPDATE should have been refused");
      assertEquals("42501", failure.getSQLState());
      conn.rollback();
      assertEquals(2, tableCount(conn, "ledger_entry"));
    }
  }

  @Test
  void runtimeRoleCannotDeleteOrTruncateJournal() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      UUID opening = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(conn, opening, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, opening, 2, capital, "CAD", "CREDIT", 10_000);
      conn.commit();
      assertEquals(2, tableCount(conn, "ledger_account"));

      // Each refused statement aborts the transaction (later commands would report
      // 25P02 instead of their own verdict), so roll back to a fresh transaction
      // between probes. Setup rows are already committed and survive this.
      SQLException deleteFailure = statementFailure(conn, "DELETE FROM ledger_account");
      assertTrue(deleteFailure != null, "DELETE should have been refused");
      assertEquals("42501", deleteFailure.getSQLState());
      conn.rollback();

      SQLException truncateFailure = statementFailure(conn, "TRUNCATE ledger_posting");
      assertTrue(truncateFailure != null, "TRUNCATE should have been refused");
      assertEquals("42501", truncateFailure.getSQLState());
      conn.rollback();

      SQLException accountUpdate = statementFailure(conn, "UPDATE ledger_account SET name = 'x'");
      assertTrue(accountUpdate != null, "account UPDATE should have been refused");
      assertEquals("42501", accountUpdate.getSQLState());
      conn.rollback();

      conn.rollback();
      assertEquals(2, tableCount(conn, "ledger_account"));
      assertEquals(1, tableCount(conn, "ledger_posting"));
    }
  }

  @Test
  void runtimeRoleCannotSupplyGeneratedIds() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      SQLException failure = null;
      try (Statement stmt = conn.createStatement()) {
        stmt.execute(
            "INSERT INTO ledger_account (id, account_code, name, currency_code, account_type,"
                + " overdraft_policy) VALUES ('"
                + UUID.randomUUID()
                + "', 'forged', 'Forged',"
                + " 'CAD', 'ASSET', 'DENY')");
      } catch (SQLException e) {
        failure = e;
      }
      assertTrue(failure != null, "id-supplying INSERT should have been refused");
      assertEquals("42501", failure.getSQLState());
      conn.rollback();
    }
  }

  @Test
  void immutableTriggerBackstopsTheOwner() throws Exception {
    // The owner bypasses grants, so this proves the trigger backstop exists behind them.
    try (Connection conn = LedgerDatabase.ownerConnection()) {
      conn.setAutoCommit(false);
      SQLException failure = null;
      try (Statement stmt = conn.createStatement()) {
        stmt.execute("UPDATE ledger_currency SET minor_unit_digits = 3 WHERE code = 'JPY'");
      } catch (SQLException e) {
        failure = e;
      }
      // Currency is reference data, not journal history: no immutable trigger covers it, so the
      // owner write succeeds and must be rolled back to keep the seed intact.
      assertTrue(failure == null, "owner currency write unexpectedly failed");
      conn.rollback();
    }
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, posting, 2, capital, "CAD", "CREDIT", 10_000);
      conn.commit();
    }
    try (Connection conn = LedgerDatabase.ownerConnection()) {
      conn.setAutoCommit(false);
      SQLException failure = statementFailure(conn, "DELETE FROM ledger_entry");
      assertTrue(failure != null, "owner DELETE should hit the immutable trigger");
      assertEquals("25001", failure.getSQLState());
      assertTrue(failure.getMessage().contains("ledger immutable"));
      conn.rollback();
    }
    try (Connection conn = LedgerDatabase.appConnection()) {
      assertEquals(2, tableCount(conn, "ledger_entry"));
    }
  }
}
