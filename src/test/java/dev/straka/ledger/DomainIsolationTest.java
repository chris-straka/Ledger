package dev.straka.ledger;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

/**
 * The domain has no Spring, Jackson, Jakarta Validation, or JDBC: HTTP and persistence adapt to
 * application/domain code, never the reverse. One ArchUnit test guards the boundary instead of
 * extra Gradle modules.
 */
class DomainIsolationTest {

  private final JavaClasses classes = new ClassFileImporter().importPackages("dev.straka.ledger..");

  @Test
  void domainHasNoFrameworkDependencies() {
    ArchRuleDefinition.noClasses()
        .that()
        .resideInAPackage("dev.straka.ledger..domain..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.springframework..",
            "com.fasterxml.jackson..",
            "jakarta.validation..",
            "jakarta.persistence..",
            "org.springframework.jdbc..",
            "javax.sql..")
        .because("domain must stay free of HTTP, validation, and persistence frameworks")
        .check(classes);
  }
}
