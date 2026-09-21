// Ledger build. Spring Boot BOM manages the ecosystem; add a dependency only
// with a concrete requirement and a design note.
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "7.2.1"
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
    // Prometheus exposition for the /actuator/prometheus endpoint named in
    // application.yaml; the only observability dependency with a concrete use.
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // The Boot BOM does not manage Testcontainers; its own BOM does.
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.3"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// One formatter per language, both via Spotless (runs in `check` and CI).
// Pinned: Spotless's default GJF calls a javac internal removed in JDK 25
// (NoSuchMethodError on DeferredDiagnosticHandler); 1.28.0 works on JDK 17-25.
// Newer GJF (tried 1.36.1) fails the same way on JDK 25, so the mason
// google-java-format used by conform.nvim on save must stay in sync by hand:
// if save-formatting and spotlessCheck disagree, spotlessApply wins.
spotless {
    java {
        googleJavaFormat("1.28.0")
    }
    // Markdown: prettier with proseWrap preserve (mirrors .prettierrc.json) —
    // normalizes structure and strips trailing whitespace, never reflows prose,
    // so line breaks stay the author's choice and `spotlessCheck` still bites.
    // Needs node: pinned in mise.toml, set up in CI.
    format("markdown") {
        target("**/*.md")
        // resume.md is YAML data with a .md extension, not prose: prettier
        // would flatten its mapping structure into markdown lists.
        targetExclude("resume.md")
        prettier("3.6.2").config(mapOf("filepath" to "README.md", "proseWrap" to "preserve"))
    }
}

// Useful compiler linting without overlapping style systems.
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:all"))
}

// Separate source set for Testcontainers suites: real Postgres, never H2.
// `check` runs both; `test` stays fast and Docker-free.
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

