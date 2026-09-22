package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariant L4: one currency per posting, enforced by composite foreign keys — not by trusting
 * Java. Any currency disagreement between posting, account, and entry fails before commit with
 * SQLSTATE 23503.
 */
class CurrencyTest extends LedgerIntegrationTest {

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

  @Test
  void unsupportedAccountCurrencyIsRejected() throws Exception {
    try (Connection conn = LedgerDB.appConnection()) {
      conn.setAutoCommit(false);
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_account (account_code, name, currency_code, account_type,"
                  + " overdraft_policy) VALUES ('chf-cash', 'CHF Cash', 'CHF', 'ASSET', 'DENY')");
      assertTrue(failure != null, "expected statement to fail but it succeeded");
      assertEquals("23503", failure.getSQLState());
      conn.rollback();
    }
  }

  @Test
  void entryCurrencyMismatchWithPostingIsRejected() throws Exception {
    try (Connection conn = LedgerDB.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "fx smuggle");
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_entry (posting_id, entry_number, account_id, currency_code, side,"
                  + " amount_minor_units) VALUES (?, 1, ?, 'USD', 'DEBIT', 100)",
              posting,
              cash);
      assertTrue(failure != null, "expected statement to fail but it succeeded");
      assertEquals("23503", failure.getSQLState());
      assertTrue(failure.getMessage().contains("ledger_entry_posting_currency"));
      conn.rollback();
    }
  }

  @Test
  void entryCurrencyMismatchWithAccountIsRejected() throws Exception {
    try (Connection conn = LedgerDB.appConnection()) {
      conn.setAutoCommit(false);
      UUID usdCash = insertAccount(conn, "usd-cash", "ASSET", "USD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "account mismatch");
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_entry (posting_id, entry_number, account_id, currency_code, side,"
                  + " amount_minor_units) VALUES (?, 1, ?, 'CAD', 'DEBIT', 100)",
              posting,
              usdCash);
      assertTrue(failure != null, "expected statement to fail but it succeeded");
      assertEquals("23503", failure.getSQLState());
      assertTrue(failure.getMessage().contains("ledger_entry_account_currency"));
      conn.rollback();
    }
  }

  @Test
  void mixedCurrencyAccountsInOnePostingCannotCommit() throws Exception {
    try (Connection conn = LedgerDB.appConnection()) {
      conn.setAutoCommit(false);
      UUID cadCash = insertAccount(conn, "cad-cash", "ASSET", "CAD", "ALLOW");
      UUID usdCapital = insertAccount(conn, "usd-capital", "EQUITY", "USD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "mixed posting");
      insertEntry(conn, posting, 1, cadCash, "CAD", "DEBIT", 100);
      // The USD leg cannot even reference this CAD posting; the statement fails first.
      SQLException failure =
          statementFailure(
              conn,
              "INSERT INTO ledger_entry (posting_id, entry_number, account_id, currency_code, side,"
                  + " amount_minor_units) VALUES (?, 2, ?, 'USD', 'CREDIT', 100)",
              posting,
              usdCapital);
      assertTrue(failure != null, "expected statement to fail but it succeeded");
      assertEquals("23503", failure.getSQLState());
      conn.rollback();
    }
  }

  @Test
  void zeroDecimalCurrencyPostingCommits() throws Exception {
    try (Connection conn = LedgerDB.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "jpy-cash", "ASSET", "JPY", "DENY");
      UUID capital = insertAccount(conn, "jpy-capital", "EQUITY", "JPY", "DENY");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "JPY", 2, "jpy funding");
      insertEntry(conn, posting, 1, cash, "JPY", "DEBIT", 10_000);
      insertEntry(conn, posting, 2, capital, "JPY", "CREDIT", 10_000);
      conn.commit();
      assertEquals(1, tableCount(conn, "ledger_posting"));
    }
  }
}
