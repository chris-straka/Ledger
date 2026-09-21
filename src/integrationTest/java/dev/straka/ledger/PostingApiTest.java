package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Phase 4 gate: happy path, sequential replay, mismatched replay, response contract, and
 * conservation — plus the 20-way concurrent replay that proves the unique key (not a
 * check-then-insert race) chooses the winner.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostingApiTest extends LedgerIntegrationTest {

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
    Map<String, String> cash = accountBody("cash-" + tag, "ASSET", policy);
    Map<String, String> capital = accountBody("capital-" + tag, "EQUITY", policy);
    Map<String, String> supplies = accountBody("supplies-" + tag, "EXPENSE", policy);
    return new Accounts(postAccount(cash), postAccount(capital), postAccount(supplies));
  }

  private static Map<String, String> accountBody(String code, String type, String policy) {
    Map<String, String> body = new HashMap<>();
    body.put("code", code);
    body.put("name", code);
    body.put("currency", "CAD");
    body.put("type", type);
    body.put("overdraftPolicy", policy);
    return body;
  }

  @SuppressWarnings("unchecked")
  private UUID postAccount(Map<String, String> body) {
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

  @SuppressWarnings("unchecked")
  private ResponseEntity<Map> postPosting(String key, Map<String, Object> body) {
    return rest.post()
        .uri("/v1/postings")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", key)
        .body(body)
        .retrieve()
        .onStatus(status -> true, (request, response) -> {})
        .toEntity(Map.class);
  }

  @Test
  void happyPathPostsReadsBackAndDerivesBalances() {
    Accounts accounts = createAccounts();
    String key = newKey();
    Map<String, Object> body =
        postingBody(
            "opening capital",
            List.of(
                entry(accounts.cash(), "DEBIT", "10000"),
                entry(accounts.capital(), "CREDIT", "10000")));

    ResponseEntity<Map> created = postPosting(key, body);
    assertEquals(201, created.getStatusCode().value());
    String id = (String) created.getBody().get("postingId");
    assertTrue(created.getHeaders().getLocation().toString().endsWith("/v1/postings/" + id));
    assertEquals(2, created.getBody().get("entryCount"));

    ResponseEntity<Map> fetched =
        rest.get().uri("/v1/postings/" + id).retrieve().toEntity(Map.class);
    assertEquals(200, fetched.getStatusCode().value());
    assertEquals("10000", balanceOf(accounts.cash()));
    assertEquals("10000", balanceOf(accounts.capital()));
  }

  @Test
  void replayReturnsOriginalWithFlag() throws Exception {
    Accounts accounts = createAccounts();
    String key = newKey();
    Map<String, Object> body =
        postingBody(
            "opening capital",
            List.of(
                entry(accounts.cash(), "DEBIT", "10000"),
                entry(accounts.capital(), "CREDIT", "10000")));
    String first = (String) postPosting(key, body).getBody().get("postingId");

    ResponseEntity<Map> replay = postPosting(key, body);
    assertEquals(200, replay.getStatusCode().value());
    assertEquals(first, replay.getBody().get("postingId"));
    assertEquals("true", replay.getHeaders().getFirst("Idempotency-Replayed"));

    try (Connection conn = LedgerDatabase.appConnection()) {
      assertEquals(1, tableCount(conn, "ledger_posting"));
      assertEquals(2, tableCount(conn, "ledger_entry"));
    }
  }

  @Test
  void differentBodyUnderSameKeyConflicts() {
    Accounts accounts = createAccounts();
    String key = newKey();
    postPosting(
        key,
        postingBody(
            "opening capital",
            List.of(
                entry(accounts.cash(), "DEBIT", "10000"),
                entry(accounts.capital(), "CREDIT", "10000"))));
    ResponseEntity<Map> conflict =
        postPosting(
            key,
            postingBody(
                "opening capital",
                List.of(
                    entry(accounts.cash(), "DEBIT", "9000"),
                    entry(accounts.capital(), "CREDIT", "9000"))));
    assertEquals(409, conflict.getStatusCode().value());
    assertEquals("IDEMPOTENCY_CONFLICT", conflict.getBody().get("code"));
  }

  @Test
  void unbalancedPostingIsUnprocessable() {
    Accounts accounts = createAccounts("ALLOW");
    ResponseEntity<Map> response =
        postPosting(
            newKey(),
            postingBody(
                "unbalanced",
                List.of(
                    entry(accounts.cash(), "DEBIT", "100"),
                    entry(accounts.capital(), "CREDIT", "50"))));
    assertEquals(422, response.getStatusCode().value());
    assertEquals("POSTING_INVALID", response.getBody().get("code"));
  }

  @Test
  void unknownAccountIsNotFound() {
    Accounts accounts = createAccounts("ALLOW");
    ResponseEntity<Map> response =
        postPosting(
            newKey(),
            postingBody(
                "ghost leg",
                List.of(
                    entry(accounts.cash(), "DEBIT", "100"),
                    entry(UUID.randomUUID(), "CREDIT", "100"))));
    assertEquals(404, response.getStatusCode().value());
    assertEquals("ACCOUNT_NOT_FOUND", response.getBody().get("code"));
  }

  @Test
  void missingKeyIsBadRequest() {
    Accounts accounts = createAccounts();
    ResponseEntity<Map> response =
        rest.post()
            .uri("/v1/postings")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                postingBody(
                    "no key",
                    List.of(
                        entry(accounts.cash(), "DEBIT", "100"),
                        entry(accounts.capital(), "CREDIT", "100"))))
            .retrieve()
            .onStatus(status -> true, (request, res) -> {})
            .toEntity(Map.class);
    assertEquals(400, response.getStatusCode().value());
  }

  @Test
  void malformedKeyIsBadRequest() {
    Accounts accounts = createAccounts();
    ResponseEntity<Map> response =
        postPosting(
            "not a valid key!",
            postingBody(
                "bad key",
                List.of(
                    entry(accounts.cash(), "DEBIT", "100"),
                    entry(accounts.capital(), "CREDIT", "100"))));
    assertEquals(400, response.getStatusCode().value());
    assertEquals("MALFORMED_REQUEST", response.getBody().get("code"));
  }

  @Test
  void overdraftIsRejectedWithConflict() {
    Accounts accounts = createAccounts();
    postPosting(
        newKey(),
        postingBody(
            "opening",
            List.of(
                entry(accounts.cash(), "DEBIT", "10000"),
                entry(accounts.capital(), "CREDIT", "10000"))));
    ResponseEntity<Map> overspend =
        postPosting(
            newKey(),
            postingBody(
                "overspend",
                List.of(
                    entry(accounts.supplies(), "DEBIT", "25000"),
                    entry(accounts.cash(), "CREDIT", "25000"))));
    assertEquals(409, overspend.getStatusCode().value());
    assertEquals("OVERDRAFT_REJECTED", overspend.getBody().get("code"));
    assertEquals("10000", balanceOf(accounts.cash()));
  }

  @Test
  void farFutureEffectiveAtIsUnprocessable() {
    Accounts accounts = createAccounts("ALLOW");
    Map<String, Object> body =
        postingBody(
            "time traveler",
            List.of(
                entry(accounts.cash(), "DEBIT", "100"),
                entry(accounts.capital(), "CREDIT", "100")));
    body.put("effectiveAt", Instant.now().plusSeconds(3600).toString());
    ResponseEntity<Map> response = postPosting(newKey(), body);
    assertEquals(422, response.getStatusCode().value());
  }

  @Test
  void twentyWayReplayMakesOnePosting() throws Exception {
    Accounts accounts = createAccounts();
    String key = newKey();
    Map<String, Object> body =
        postingBody(
            "race",
            List.of(
                entry(accounts.cash(), "DEBIT", "10000"),
                entry(accounts.capital(), "CREDIT", "10000")));

    int racers = 20;
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    CountDownLatch ready = new CountDownLatch(racers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ResponseEntity<Map>>> futures = new ArrayList<>();
    for (int i = 0; i < racers; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("start barrier timed out");
                }
                return postPosting(key, body);
              }));
    }
    assertTrue(ready.await(10, TimeUnit.SECONDS), "racers did not ready in time");
    start.countDown();
    List<String> ids = new ArrayList<>();
    for (Future<ResponseEntity<Map>> future : futures) {
      ResponseEntity<Map> response = future.get(30, TimeUnit.SECONDS);
      assertTrue(
          response.getStatusCode().value() == 200 || response.getStatusCode().value() == 201,
          () -> "unexpected status " + response.getStatusCode());
      ids.add((String) response.getBody().get("postingId"));
    }
    pool.shutdown();
    assertEquals(1, new java.util.HashSet<>(ids).size(), "replay race made two postings");
    try (Connection conn = LedgerDatabase.appConnection()) {
      assertEquals(1, tableCount(conn, "ledger_posting"));
      assertEquals(2, tableCount(conn, "ledger_entry"));
    }
  }

  @SuppressWarnings("unchecked")
  private String balanceOf(UUID account) {
    ResponseEntity<Map> response =
        rest.get().uri("/v1/accounts/" + account + "/balance").retrieve().toEntity(Map.class);
    assertEquals(200, response.getStatusCode().value());
    return (String) response.getBody().get("balanceMinor");
  }
}
