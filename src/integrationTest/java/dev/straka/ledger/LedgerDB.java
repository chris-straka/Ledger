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
 * and the ledger DB, Flyway migrates as the owner, and every test connects as the restricted {@code
 * ledger_app} runtime role. Tests never hold owner credentials.
 *
 * <p>Without Docker, set {@code LEDGER_TEST_ADMIN_URL} (plus optional {@code
 * LEDGER_TEST_ADMIN_USER} / {@code LEDGER_TEST_ADMIN_PASSWORD}) to a superuser JDBC URL on a
 * <em>disposable</em> Postgres 18 cluster. The same bootstrap then runs there instead of in a
 * container, after dropping any previous ledger databases and roles on that cluster.
 */
final class LedgerDB {

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

  /** Superuser JDBC URL of a disposable external cluster; unset means Testcontainers. */
  private static final String EXTERNAL_ADMIN_URL = System.getenv("LEDGER_TEST_ADMIN_URL");

  private static HikariDataSource appPool;
  private static String ledgerUrl;
  private static String anomalyUrl;

  private LedgerDB() {}

  static synchronized void start() {
    if (appPool != null) {
      return;
    }
    String adminUrl;
    String adminUser;
    String adminPassword;
    boolean external = EXTERNAL_ADMIN_URL != null && !EXTERNAL_ADMIN_URL.isBlank();
    if (external) {
      adminUrl = EXTERNAL_ADMIN_URL;
      adminUser = System.getenv().getOrDefault("LEDGER_TEST_ADMIN_USER", "postgres");
      adminPassword = System.getenv().getOrDefault("LEDGER_TEST_ADMIN_PASSWORD", "");
    } else {
      CONTAINER.start();
      adminUrl = CONTAINER.getJdbcUrl();
      adminUser = "test";
      adminPassword = "test";
    }
    ledgerUrl = adminUrl.replaceAll("/[^/?]+(\\?.*)?$", "/" + DB + "$1");
    anomalyUrl = adminUrl.replaceAll("/[^/?]+(\\?.*)?$", "/" + ANOMALY_DB + "$1");
    try (Connection admin = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
        Statement stmt = admin.createStatement()) {
      if (external) {
        // Disposable cluster: start from nothing, exactly like a fresh container.
        stmt.execute("DROP DATABASE IF EXISTS \"" + DB + "\" WITH (FORCE)");
        stmt.execute("DROP DATABASE IF EXISTS \"" + ANOMALY_DB + "\" WITH (FORCE)");
        stmt.execute("DROP ROLE IF EXISTS \"" + APP + "\"");
        stmt.execute("DROP ROLE IF EXISTS \"" + OWNER + "\"");
      }
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
    // The anomaly DB carries the identical schema but is deliberately excluded
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

  /** Raw connection to the disposable anomaly DB. Never conservation-audited. */
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
