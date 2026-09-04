package dev.straka.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Docker-free smoke test: proves the Spring context wires without a database.
// DataSource/Flyway auto-configuration is excluded because PostgreSQL-backed
// tests live in `integrationTest` (Testcontainers), never in `test`.
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude="
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
          + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
    })
class LedgerApplicationTests {

  @Test
  void contextLoads() {}
}
