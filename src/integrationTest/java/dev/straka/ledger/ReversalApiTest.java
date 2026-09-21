package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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
 * Phase 6 gate: exact reversals are additive — the original is never edited, a second reversal is
 * refused, reversing a reversal is refused, and a fraudulent non-inverse reversal fails at commit.
 * Overdraft policy still applies, so history already spent cannot always be undone.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReversalApiTest extends LedgerIntegrationTest {

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

  private record Accounts(UUID cash, UUID capital, UUID supplies) {}

  private Accounts createAccounts() {
    return createAccounts("DENY");
  }

  private Accounts createAccounts(String policy) {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    return new Accounts(
        postAccount("cash-" + tag, "ASSET", policy),
        postAccount("capital-" + tag, "EQUITY", policy),
        postAccount("supplies-" + tag, "EXPENSE", policy));
  }

  @SuppressWarnings("unchecked")
  private UUID postAccount(String code, String type, String policy) {
    Map<String, String> body = new HashMap<>();
    body.put("code", code);
    body.put("name", code);
    body.put("currency", "CAD");
    body.put("type", type);
    body.put("overdraftPolicy", policy);
    ResponseEntity<Map> response =
        rest.post()
            .uri("/v1/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(Map.class);
    assertEquals(201, response.getStatusCode().value());
    return UUID.fromString((String) response.getBody().get("accountId"));
  }

  private static Map<String, Object> entry(UUID account, String side, String amount) {
    Map<String, Object> entry = new HashMap<>();
    entry.put("accountId", account.toString());
    entry.put("side", side);
    entry.put("amountMinorUnits", amount);
    return entry;
  }

  private static Map<String, Object> postingBody(
      String description, List<Map<String, Object>> entries) {
    Map<String, Object> body = new HashMap<>();
    body.put("description", description);
    body.put("effectiveAt", Instant.now().toString());
    body.put("entries", entries);
    return body;
  }

  private String fundThenSpend(Accounts accounts) {
    postStandard(
        newKey(),
        "funding",
        List.of(
            entry(accounts.cash(), "DEBIT", "10000"),
            entry(accounts.capital(), "CREDIT", "10000")));
    return postStandard(
        newKey(),
        "buy supplies",
        List.of(
            entry(accounts.supplies(), "DEBIT", "2500"), entry(accounts.cash(), "CREDIT", "2500")));
  }

  @SuppressWarnings("unchecked")
  private String postStandard(String key, String description, List<Map<String, Object>> entries) {
    ResponseEntity<Map> response =
        rest.post()
            .uri("/v1/postings")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key)
            .body(postingBody(description, entries))
            .retrieve()
            .toEntity(Map.class);
    assertEquals(201, response.getStatusCode().value());
    return (String) response.getBody().get("postingId");
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<Map> postReversal(String key, String target, String reason) {
    return postReversal(key, target, reason, Instant.now());
  }

  @SuppressWarnings("unchecked")
  private ResponseEntity<Map> postReversal(
      String key, String target, String reason, Instant effectiveAt) {
    Map<String, Object> body = new HashMap<>();
    body.put("reason", reason);
    body.put("effectiveAt", effectiveAt.toString());
    return rest.post()
        .uri("/v1/postings/" + target + "/reversals")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", key)
        .body(body)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(Map.class);
  }

  @SuppressWarnings("unchecked")
  private Map getPosting(String id) {
    return rest.get().uri("/v1/postings/" + id).retrieve().toEntity(Map.class).getBody();
  }

  @SuppressWarnings("unchecked")
  private String balanceOf(UUID account) {
    return (String)
        rest.get()
            .uri("/v1/accounts/" + account + "/balance")
            .retrieve()
            .toEntity(Map.class)
            .getBody()
            .get("balanceMinor");
  }

  @Test
  @SuppressWarnings("unchecked")
  void reversalRestoresBalancesAndLeavesOriginalUntouched() throws Exception {
    Accounts accounts = createAccounts();
    String spend = fundThenSpend(accounts);
    assertEquals("7500", balanceOf(accounts.cash()));

    ResponseEntity<Map> reversed = postReversal(newKey(), spend, "wrong supplies order");
    assertEquals(201, reversed.getStatusCode().value());
    String reversalId = (String) reversed.getBody().get("postingId");
    assertEquals("REVERSAL", reversed.getBody().get("kind"));
    assertEquals(spend, reversed.getBody().get("reversesPostingId"));

    assertEquals("10000", balanceOf(accounts.cash()));
    assertEquals("0", balanceOf(accounts.supplies()));

    Map original = getPosting(spend);
    assertEquals("STANDARD", original.get("kind"));
    assertNull(original.get("reversesPostingId"));
    assertEquals(2, original.get("entryCount"));

    try (Connection conn = LedgerDatabase.appConnection()) {
      assertEquals(3, tableCount(conn, "ledger_posting"));
      assertEquals(6, tableCount(conn, "ledger_entry"));
    }
  }

  @Test
  void reversalReplayReturnsOriginal() {
    Accounts accounts = createAccounts();
    String spend = fundThenSpend(accounts);
    String key = newKey();
    Instant effectiveAt = Instant.now();
    String first =
        (String) postReversal(key, spend, "oops", effectiveAt).getBody().get("postingId");

    // A replay repeats the identical semantic request, including effectiveAt.
    ResponseEntity<Map> replay = postReversal(key, spend, "oops", effectiveAt);
    assertEquals(200, replay.getStatusCode().value());
    assertEquals(first, replay.getBody().get("postingId"));
    assertEquals("true", replay.getHeaders().getFirst("Idempotency-Replayed"));
  }

  @Test
  void secondReversalConflicts() {
    // ALLOW isolates the single-reversal slot: with DENY the second attempt would
    // trip the ordinary overdraft check first, which is correct but proves less.
    Accounts accounts = createAccounts("ALLOW");
    String spend = fundThenSpend(accounts);
    assertEquals(201, postReversal(newKey(), spend, "first undo").getStatusCode().value());

    ResponseEntity<Map> second = postReversal(newKey(), spend, "second undo");
    assertEquals(409, second.getStatusCode().value());
    assertEquals("REVERSAL_CONFLICT", second.getBody().get("code"));
  }

  @Test
  void reversingAReversalConflicts() {
    Accounts accounts = createAccounts();
    String spend = fundThenSpend(accounts);
    String reversal = (String) postReversal(newKey(), spend, "undo").getBody().get("postingId");

    ResponseEntity<Map> response = postReversal(newKey(), reversal, "undo the undo");
    assertEquals(409, response.getStatusCode().value());
    assertEquals("REVERSAL_CONFLICT", response.getBody().get("code"));
  }

  @Test
  void reversingMissingPostingIsNotFound() {
    ResponseEntity<Map> response = postReversal(newKey(), UUID.randomUUID().toString(), "ghost");
    assertEquals(404, response.getStatusCode().value());
    assertEquals("POSTING_NOT_FOUND", response.getBody().get("code"));
  }

  @Test
  void reversalBlockedByLaterOverdraft() {
    Accounts accounts = createAccounts();
    String opening =
        postStandard(
            newKey(),
            "funding",
            List.of(
                entry(accounts.cash(), "DEBIT", "10000"),
                entry(accounts.capital(), "CREDIT", "10000")));
    postStandard(
        newKey(),
        "spend most",
        List.of(
            entry(accounts.supplies(), "DEBIT", "8000"), entry(accounts.cash(), "CREDIT", "8000")));
    // Undoing the funding would pull 10,000 out of a 2,000 cash account.
    ResponseEntity<Map> response = postReversal(newKey(), opening, "take back capital");
    assertEquals(409, response.getStatusCode().value());
    assertEquals("OVERDRAFT_REJECTED", response.getBody().get("code"));
    assertEquals("2000", balanceOf(accounts.cash()));
  }

  @Test
  void fraudulentRawReversalFailsAtCommit() throws Exception {
    UUID target;
    try (Connection conn = LedgerDatabase.appConnection()) {
      conn.setAutoCommit(false);
      UUID cash = insertAccount(conn, "cash", "ASSET", "CAD", "ALLOW");
      UUID capital = insertAccount(conn, "capital", "EQUITY", "CAD", "ALLOW");
      target = insertPosting(conn, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(conn, target, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, target, 2, capital, "CAD", "CREDIT", 10_000);
      conn.commit();

      // A REVERSAL-labeled posting whose entries are NOT the exact inverse.
      conn.setAutoCommit(false);
      UUID fraud = insertPosting(conn, newKey(), "REVERSAL", target, "CAD", 2, "fake undo");
      insertEntry(conn, fraud, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(conn, fraud, 2, capital, "CAD", "CREDIT", 10_000);
      assertCommitFailsWith(conn, "23514", "ledger_reversal_inverse");
      conn.rollback();
      assertEquals(1, tableCount(conn, "ledger_posting"));
    }
  }
}
