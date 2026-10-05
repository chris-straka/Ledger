import org.flywaydb.core.Flyway;

/**
 * Applies the Flyway migrations as the owner role without Docker. Launched as a single-file source
 * program by scripts/run-local.sh with the boot jar's libraries on the classpath.
 *
 * <p>Usage: java -cp 'lib/*' scripts/LocalMigrate.java JDBC_URL OWNER PASSWORD
 */
public class LocalMigrate {
  public static void main(String[] args) {
    var result =
        Flyway.configure()
            .dataSource(args[0], args[1], args[2])
            .locations("filesystem:src/main/resources/db/migration")
            .load()
            .migrate();
    System.out.println("migrations applied: " + result.migrationsExecuted);
  }
}
