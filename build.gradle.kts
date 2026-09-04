// Ledger build. Spring Boot BOM manages the ecosystem; add a dependency only
// with a concrete requirement and a design note (PORT.md section 4).
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.straka"
version = "0.0.1-SNAPSHOT"

java {
    // Java 25 baseline (mise.toml); the toolchain — not the workstation JDK — builds.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Separate source set for Testcontainers suites: real Postgres, never H2.
// `check` runs both; `test` stays fast and Docker-free (PORT.md section 8).
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
        runtimeClasspath += output + compileClasspath
    }
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against Testcontainers PostgreSQL."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    // Commit-sensitive tests share one database fixture; no parallel execution.
    maxParallelForks = 1
}

tasks.check {
    dependsOn(integrationTest)
}
