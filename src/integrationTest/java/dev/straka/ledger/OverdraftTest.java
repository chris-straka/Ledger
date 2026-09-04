package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariant L7 (raw-SQL half): per-account overdraft policy. The deferred trigger evaluates the
 * projected normal-side balance over the whole ledger inside the committing transaction, so a raw
 * overdraft fails at commit. The application-level SERIALIZABLE race proof arrives in Phase 5.
 */
class OverdraftTest extends LedgerIntegrationTest {

  private static long balanceOf(Connection conn, UUID accountId) throws Exception {
    try (Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT balance_minor FROM v_account_balance WHERE account_id = '"
                    + accountId
                    + "'")) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  void denyAccountRejectsOverdraftAtCommit() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "DENY");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "DENY");
      UUID supplies = insertAccount(conn, "supplies", "EXPENSE", "CAD", "DENY");
      UUID opening = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(conn, opening, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, opening, 2, capital, "CAD", "CREDIT", 10_000);
      conn.commit();

      conn.setAutoCommit(false);
      UUID spend = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "overspend");
      insertEntry(conn, spend, 1, supplies, "CAD", "DEBIT", 25_000);
      insertEntry(conn, spend, 2, cash, "CAD", "CREDIT", 25_000);
      assertCommitFailsWith(conn, "23514", "ledger_posting_overdraft");
      conn.rollback();

      assertEquals(10_000, balanceOf(conn, cash));
      assertEquals(1, tableCount(conn, "ledger_posting"));
    }
  }

  @Test
  void exactSpendToZeroIsAllowed() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "DENY");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "DENY");
      UUID supplies = insertAccount(conn, "supplies", "EXPENSE", "CAD", "DENY");
      UUID opening = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(conn, opening, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, opening, 2, capital, "CAD", "CREDIT", 10_000);
      conn.commit();

      conn.setAutoCommit(false);
      UUID spend = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "spend all");
      insertEntry(conn, spend, 1, supplies, "CAD", "DEBIT", 10_000);
      insertEntry(conn, spend, 2, cash, "CAD", "CREDIT", 10_000);
      conn.commit();
      assertEquals(0, balanceOf(conn, cash));
      assertEquals(10_000, balanceOf(conn, supplies));
    }
  }

  @Test
  void allowAccountPermitsNegativeBalance() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "flex-cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "flex-capital", "EQUITY", "CAD", "ALLOW");
      UUID opening = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(conn, opening, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, opening, 2, capital, "CAD", "CREDIT", 10_000);
      conn.commit();

      conn.setAutoCommit(false);
      UUID spend = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "overdraft allowed");
      insertEntry(conn, spend, 1, capital, "CAD", "DEBIT", 16_000);
      insertEntry(conn, spend, 2, cash, "CAD", "CREDIT", 16_000);
      conn.commit();
      assertEquals(-6_000, balanceOf(conn, cash));
    }
  }
}
