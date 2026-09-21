package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Role and migration identity: tests really run as the restricted runtime role against the migrated
 * schema — otherwise every bypass test above would prove nothing.
 */
class RuntimeRoleTest extends LedgerIntegrationTest {

  @Test
  void testsConnectAsRestrictedRuntimeRole() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection()) {
      assertEquals("ledger_app", LedgerDatabase.currentUser(conn));
    }
  }

  @Test
  void allMigrationsAppliedInOrder() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")) {
      StringBuilder versions = new StringBuilder();
      while (rs.next()) {
        versions.append(rs.getString(1)).append(',');
      }
      assertEquals("1,2,3,4,5,", versions.toString());
    }
  }

  @Test
  void currencySeedsIncludeZeroDecimalJpy() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT code, minor_unit_digits FROM ledger_currency ORDER BY code")) {
      StringBuilder seeds = new StringBuilder();
      while (rs.next()) {
        seeds.append(rs.getString(1)).append(':').append(rs.getInt(2)).append(',');
      }
      assertEquals("CAD:2,EUR:2,GBP:2,JPY:0,USD:2,", seeds.toString());
    }
  }

  @Test
  void conservationViewIsEmptyBeforeAnyPosting() throws Exception {
    try (Connection conn = LedgerDatabase.appConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM v_conservation")) {
      rs.next();
      assertEquals(0, rs.getLong(1));
    }
    assertTrue(LedgerDatabase.conservationViolations().isEmpty());
  }
}
