# Syntax-directed, non-root runtime for the ledger API.
FROM eclipse-temurin:25-jre AS runtime

RUN groupadd --system ledger && useradd --system --gid ledger ledger

WORKDIR /app
COPY build/libs/ledger-*.jar app.jar
RUN chown ledger:ledger app.jar

USER ledger
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
