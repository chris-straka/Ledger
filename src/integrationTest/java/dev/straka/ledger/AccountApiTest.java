package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Account HTTP slice: thin-controller contract (status codes, Location, string minor units, problem
 * bodies) against real Postgres. Postings are funded through raw JDBC until the posting API lands
 * in Phase 4.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountApiTest extends LedgerIntegrationTest {

  @LocalServerPort private int port;

  private RestClient rest;

  @BeforeEach
  void buildClient() {
    rest = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    LedgerDatabase.start();
    registry.add("spring.datasource.url", () -> LedgerDatabase.ledgerUrl());
    registry.add("spring.datasource.username", () -> LedgerDatabase.APP);
    registry.add("spring.datasource.password", () -> LedgerDatabase.APP_PASSWORD);
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<Map> postAccount(Map<String, String> body) {
    // A no-op status handler turns every status into a returned entity instead
    // of an exception, so tests assert status codes rather than catch them.
    return rest.post()
        .uri("/v1/accounts")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(Map.class);
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<Map> get(String path) {
    return rest.get()
        .uri(path)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(Map.class);
  }

  private static Map<String, String> accountBody(String code) {
    Map<String, String> body = new HashMap<>();
    body.put("code", code);
    body.put("name", code + " account");
    body.put("currency", "CAD");
    body.put("type", "ASSET");
    body.put("overdraftPolicy", "DENY");
    return body;
  }

  @Test
  void createGetAndZeroBalance() {
    ResponseEntity<Map> created = postAccount(accountBody("cash-" + UUID.randomUUID()));
    assertEquals(201, created.getStatusCode().value());
    assertTrue(created.getHeaders().getLocation().toString().startsWith("/v1/accounts/"));
    String id = (String) created.getBody().get("accountId");

    ResponseEntity<Map> fetched = get("/v1/accounts/" + id);
    assertEquals(200, fetched.getStatusCode().value());
    assertEquals("CAD", fetched.getBody().get("currency"));

    ResponseEntity<Map> balance = get("/v1/accounts/" + id + "/balance");
    assertEquals(200, balance.getStatusCode().value());
    assertEquals("0", balance.getBody().get("balanceMinor"));
  }

  @Test
  void balanceDerivesFromEntries() throws Exception {
    UUID cash;
    UUID supplies;
    UUID capital;
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      cash = insertAccount(conn, "cash", "ASSET", "CAD", "DENY");
      capital = insertAccount(conn, "capital", "EQUITY", "CAD", "DENY");
      supplies = insertAccount(conn, "supplies", "EXPENSE", "CAD", "DENY");
      UUID opening = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(conn, opening, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, opening, 2, capital, "CAD", "CREDIT", 10_000);
      UUID spend = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "spend");
      insertEntry(conn, spend, 1, supplies, "CAD", "DEBIT", 2_500);
      insertEntry(conn, spend, 2, cash, "CAD", "CREDIT", 2_500);
      conn.commit();
    }
    assertEquals("7500", get("/v1/accounts/" + cash + "/balance").getBody().get("balanceMinor"));
    assertEquals(
        "2500", get("/v1/accounts/" + supplies + "/balance").getBody().get("balanceMinor"));
    assertEquals(
        "10000", get("/v1/accounts/" + capital + "/balance").getBody().get("balanceMinor"));
  }

  @Test
  void duplicateCodeConflicts() {
    String code = "dup-" + UUID.randomUUID();
    assertEquals(201, postAccount(accountBody(code)).getStatusCode().value());
    ResponseEntity<Map> replay = postAccount(accountBody(code));
    assertEquals(409, replay.getStatusCode().value());
    assertEquals("ACCOUNT_CONFLICT", replay.getBody().get("code"));
  }

  @Test
  void unsupportedCurrencyIsUnprocessable() {
    Map<String, String> body = accountBody("chf-" + UUID.randomUUID());
    body.put("currency", "CHF");
    ResponseEntity<Map> response = postAccount(body);
    assertEquals(422, response.getStatusCode().value());
    assertEquals("ACCOUNT_INVALID", response.getBody().get("code"));
  }

  @Test
  void unknownTypeIsUnprocessable() {
    Map<String, String> body = accountBody("w-" + UUID.randomUUID());
    body.put("type", "WALLET");
    ResponseEntity<Map> response = postAccount(body);
    assertEquals(422, response.getStatusCode().value());
  }

  @Test
  void blankCodeIsBadRequest() {
    Map<String, String> body = accountBody("x");
    body.put("code", "  ");
    ResponseEntity<Map> response = postAccount(body);
    assertEquals(400, response.getStatusCode().value());
    assertEquals("VALIDATION_FAILED", response.getBody().get("code"));
  }

  @Test
  void missingAccountIsNotFound() {
    ResponseEntity<Map> response = get("/v1/accounts/" + UUID.randomUUID());
    assertEquals(404, response.getStatusCode().value());
    assertEquals("ACCOUNT_NOT_FOUND", response.getBody().get("code"));

    ResponseEntity<Map> balance = get("/v1/accounts/" + UUID.randomUUID() + "/balance");
    assertEquals(404, balance.getStatusCode().value());
  }

  @Test
  void malformedUuidIsBadRequest() {
    ResponseEntity<Map> response = get("/v1/accounts/not-a-uuid");
    assertEquals(400, response.getStatusCode().value());
    assertEquals("MALFORMED_REQUEST", response.getBody().get("code"));
  }
}
