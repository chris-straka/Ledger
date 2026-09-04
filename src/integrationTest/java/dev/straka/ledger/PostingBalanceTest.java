package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Invariant L1: every posting balances. The deferred trigger checks the signed debit/credit sum at
 * commit; these tests bypass Java entirely through raw JDBC. Statement success proves nothing —
 * only the explicit commit verdict counts.
 */
class PostingBalanceTest extends LedgerIntegrationTest {

  @Test
  void balancedTwoLinePostingCommits() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "DENY");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "DENY");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening capital");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, posting, 2, capital, "CAD", "CREDIT", 10_000);
      conn.commit();
      assertEquals(1, tableCount(conn, "ledger_posting"));
      assertEquals(2, tableCount(conn, "ledger_entry"));
    }
  }

  @Test
  void balancedMultiLinePostingCommits() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "DENY");
      UUID supplies = insertAccount(conn, "supplies", "EXPENSE", "CAD", "DENY");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "DENY");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 3, "split funding");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 6_000);
      insertEntry(conn, posting, 2, supplies, "CAD", "DEBIT", 4_000);
      insertEntry(conn, posting, 3, capital, "CAD", "CREDIT", 10_000);
      conn.commit();
      assertEquals(3, tableCount(conn, "ledger_entry"));
    }
  }

  @Test
  void hugeAmountsNearLongLimitCommitExactly() throws Exception {
    long huge = 4_000_000_000_000_000_000L;
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "huge transfer");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", huge);
      insertEntry(conn, posting, 2, capital, "CAD", "CREDIT", huge);
      conn.commit();
      assertEquals(2, tableCount(conn, "ledger_entry"));
    }
  }

  @Test
  void unbalancedPostingFailsAtCommit() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "unbalanced");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 100);
      insertEntry(conn, posting, 2, capital, "CAD", "CREDIT", 50);
      assertCommitFailsWith(conn, "23514", "ledger_posting_balance");
      conn.rollback();
      assertEquals(0, tableCount(conn, "ledger_posting"));
    }
  }

  @Test
  void zeroEntryHeaderFailsAtCommit() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "empty header");
      assertCommitFailsWith(conn, "23514", "ledger_posting_count");
      conn.rollback();
      assertEquals(0, tableCount(conn, "ledger_posting"));
    }
  }

  @Test
  void missingEntryFailsAtCommit() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 3, "short posting");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 100);
      insertEntry(conn, posting, 2, capital, "CAD", "CREDIT", 100);
      assertCommitFailsWith(conn, "23514", "ledger_posting_count");
      conn.rollback();
    }
  }

  @Test
  void singleAccountSelfCancelFailsAtCommit() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "self cancel");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 100);
      insertEntry(conn, posting, 2, cash, "CAD", "CREDIT", 100);
      assertCommitFailsWith(conn, "23514", "ledger_posting_accounts");
      conn.rollback();
    }
  }

  @Test
  void gappedLineNumbersFailAtCommit() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      UUID mid = insertAccount(conn, "mid", "EXPENSE", "CAD", "ALLOW");
      UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 3, "gapped lines");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 100);
      insertEntry(conn, posting, 2, mid, "CAD", "DEBIT", 50);
      insertEntry(conn, posting, 4, capital, "CAD", "CREDIT", 150);
      assertCommitFailsWith(conn, "23514", "ledger_posting_lines");
      conn.rollback();
    }
  }
}
