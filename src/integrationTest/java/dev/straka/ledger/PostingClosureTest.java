package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Posting closure: the immutable declared entry_count seals a posting. Insert-only tables alone do
 * not stop a later append; the deferred count check does — even when the appended entries are
 * themselves balanced.
 */
class PostingClosureTest extends LedgerIntegrationTest {

  private UUID commitOpening(Connection conn) throws Exception {
    UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
    UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
    UUID posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
    insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 10_000);
    insertEntry(conn, posting, 2, capital, "CAD", "CREDIT", 10_000);
    conn.commit();
    return posting;
  }

  @Test
  void appendingOneEntryFailsAtCommit() throws Exception {
    UUID posting;
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      posting = commitOpening(conn);
    }
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID supplies = insertAccount(conn, "supplies", "EXPENSE", "CAD", "ALLOW");
      insertEntry(conn, posting, 3, supplies, "CAD", "DEBIT", 500);
      assertCommitFailsWith(conn, "23514", "ledger_posting_count");
      conn.rollback();
      assertEquals(2, tableCount(conn, "ledger_entry"));
    }
  }

  @Test
  void appendingABalancedPairFailsAtCommit() throws Exception {
    UUID posting;
    UUID cash;
    UUID capital;
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      posting = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(conn, posting, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, posting, 2, capital, "CAD", "CREDIT", 10_000);
      conn.commit();
    }
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      // Balanced on its own, but the posting declared exactly 2 entries.
      insertEntry(conn, posting, 3, cash, "CAD", "DEBIT", 500);
      insertEntry(conn, posting, 4, capital, "CAD", "CREDIT", 500);
      assertCommitFailsWith(conn, "23514", "ledger_posting_count");
      conn.rollback();
      assertEquals(2, tableCount(conn, "ledger_entry"));
    }
  }
}
