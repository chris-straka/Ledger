package dev.straka.ledger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared PostgreSQL fixture. One {@code postgres:18.6} container per JVM — the identical image tag
 * used in Compose — bootstrapped exactly like production: a superuser creates the owner/app roles
 * and the ledger database, Flyway migrates as the owner, and every test connects as the restricted
 * {@code ledger_app} runtime role. Tests never hold owner credentials.
 */
final class LedgerDatabase {

  static final String IMAGE = "postgres:18.6";
  static final String DB = "ledger";
  static final String ANOMALY_DB = "ledger_anomaly";
  static final String OWNER = "ledger_owner";
  static final String OWNER_PASSWORD = "ledger_owner_integration_only";
  static final String APP = "ledger_app";
  static final String APP_PASSWORD = "ledger_app_integration_only";

  private static final PostgreSQLContainer CONTAINER =
      new PostgreSQLContainer(IMAGE)
          .withDatabaseName("test")
          .withUsername("test")
          .withPassword("test");

  private static HikariDataSource appPool;
  private static String ledgerUrl;
  private static String anomalyUrl;

  private LedgerDatabase() {}

  static synchronized void start() {
    if (appPool != null) {
      return;
    }
    CONTAINER.start();
    ledgerUrl = CONTAINER.getJdbcUrl().replaceAll("/test(\\?.*)?$", "/" + DB + "$1");
    anomalyUrl = CONTAINER.getJdbcUrl().replaceAll("/test(\\?.*)?$", "/" + ANOMALY_DB + "$1");
    try (Connection admin = DriverManager.getConnection(CONTAINER.getJdbcUrl(), "test", "test");
        Statement stmt = admin.createStatement()) {
      stmt.execute("CREATE ROLE \"" + OWNER + "\" LOGIN PASSWORD '" + OWNER_PASSWORD + "'");
      stmt.execute("CREATE ROLE \"" + APP + "\" LOGIN PASSWORD '" + APP_PASSWORD + "'");
      stmt.execute("CREATE DATABASE \"" + DB + "\" OWNER \"" + OWNER + "\"");
      stmt.execute("CREATE DATABASE \"" + ANOMALY_DB + "\" OWNER \"" + OWNER + "\"");
      stmt.execute("GRANT CONNECT ON DATABASE \"" + DB + "\" TO \"" + APP + "\"");
      stmt.execute("GRANT CONNECT ON DATABASE \"" + ANOMALY_DB + "\" TO \"" + APP + "\"");
    } catch (SQLException e) {
      throw new IllegalStateException("bootstrap failed", e);
    }
    migrate(ledgerUrl);
    // The anomaly database carries the identical schema but is deliberately excluded
    // from the shared conservation audit: the weaker-isolation test leaves it overdrawn.
    migrate(anomalyUrl);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(ledgerUrl);
    config.setUsername(APP);
    config.setPassword(APP_PASSWORD);
    config.setMaximumPoolSize(20);
    appPool = new HikariDataSource(config);
  }

  private static void migrate(String url) {
    Flyway.configure()
        .dataSource(url, OWNER, OWNER_PASSWORD)
        .locations("classpath:db/migration")
        .load()
        .migrate();
  }

  /** Fresh connection as the runtime role. Callers own commit/rollback and closing. */
  static Connection appConnection() throws SQLException {
    return appPool.getConnection();
  }

  /** Raw connection to the disposable anomaly database. Never conservation-audited. */
  static Connection anomalyConnection(String user, String password) throws SQLException {
    Connection conn = DriverManager.getConnection(anomalyUrl, user, password);
    conn.setAutoCommit(false);
    return conn;
  }

  static String ledgerUrl() {
    return ledgerUrl;
  }

  /** One-shot owner connection for privileged cleanup only, never for assertions. */
  static Connection ownerConnection() throws SQLException {
    return DriverManager.getConnection(ledgerUrl, OWNER, OWNER_PASSWORD);
  }

  /** Removes all journal rows as the owner. Currency reference rows survive. */
  static void cleanJournal() throws SQLException {
    try (Connection conn = ownerConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE ledger_entry, ledger_posting, ledger_account");
    }
  }

  /**
   * Independent conservation audit: every currency's signed sum is exactly zero. Runs after every
   * test on a fresh connection, before privileged cleanup, even for tests that never mutate data.
   */
  static List<String> conservationViolations() throws SQLException {
    List<String> violations = new ArrayList<>();
    try (Connection conn = appConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT currency_code, signed_sum FROM v_conservation")) {
      while (rs.next()) {
        BigDecimal sum = rs.getBigDecimal("signed_sum");
        if (sum == null || sum.compareTo(BigDecimal.ZERO) != 0) {
          violations.add(rs.getString("currency_code") + " sums to " + sum);
        }
      }
    }
    return violations;
  }

  static String currentUser(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT current_user")) {
      rs.next();
      return rs.getString(1);
    }
  }
}
