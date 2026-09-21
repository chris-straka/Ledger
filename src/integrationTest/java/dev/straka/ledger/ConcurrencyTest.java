package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.straka.ledger.posting.application.AttemptRecorder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

/**
 * Invariant L6/L7 concurrency proof. Real threads, independent pooled connections, a start barrier,
 * and hard future timeouts — never sleeps for coordination. Derived balances mean concurrent
 * credits cannot lose updates; the hard problem is the read-before-append overdraft decision,
 * proven here at both isolation levels.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrencyTest extends LedgerIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private AttemptRecorder attempts;

  private RestClient rest;

  @BeforeEach
  void buildClient() {
    rest = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    attempts.reset();
  }

  @DynamicPropertySource
  static void datasource(DynamicPropertyRegistry registry) {
    LedgerDatabase.start();
    registry.add("spring.datasource.url", () -> LedgerDatabase.ledgerUrl());
    registry.add("spring.datasource.username", () -> LedgerDatabase.APP);
    registry.add("spring.datasource.password", () -> LedgerDatabase.APP_PASSWORD);
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

  /**
   * Posts with bounded client retries on 503. A hot account under SERIALIZABLE produces real retry
   * pressure; the API answers 503 + Retry-After precisely so clients back off and retry. The
   * invariant under proof is exact-sum accounting, not zero server-side retries.
   */
  private ResponseEntity<Map> postWithClientRetry(String key, Map<String, Object> body)
      throws InterruptedException {
    ResponseEntity<Map> response = postPosting(key, body);
    for (int i = 0; i < 15 && response.getStatusCode().value() == 503; i++) {
      Thread.sleep(100);
      response = postPosting(key, body);
    }
    return response;
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

  @SuppressWarnings("unchecked")
  private String balanceOf(UUID account) {
    ResponseEntity<Map> response =
        rest.get().uri("/v1/accounts/" + account + "/balance").retrieve().toEntity(Map.class);
    return (String) response.getBody().get("balanceMinor");
  }

  @Test
  void concurrentPostingsSumExactly() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    UUID cash = postAccount("cash-" + tag, "ASSET", "ALLOW");
    UUID capital = postAccount("capital-" + tag, "EQUITY", "ALLOW");

    int writers = 50;
    ExecutorService pool = Executors.newFixedThreadPool(writers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < writers; i++) {
      final int n = i;
      futures.add(
          pool.submit(
              () -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("start timed out");
                }
                ResponseEntity<Map> response =
                    postWithClientRetry(
                        "sum-" + tag + "-" + n,
                        postingBody(
                            "credit " + n,
                            List.of(entry(cash, "DEBIT", "100"), entry(capital, "CREDIT", "100"))));
                return response.getStatusCode().value();
              }));
    }
    start.countDown();
    for (Future<Integer> future : futures) {
      assertEquals(201, future.get(120, TimeUnit.SECONDS));
    }
    pool.shutdown();
    assertEquals("5000", balanceOf(cash));
    try (Connection conn = LedgerDatabase.appConnection()) {
      assertEquals(writers, tableCount(conn, "ledger_posting"));
      assertEquals(writers * 2L, tableCount(conn, "ledger_entry"));
    }
  }

  @Test
  void differentPayloadsUnderOneKeyElectOneWinner() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    UUID cash = postAccount("cash-" + tag, "ASSET", "ALLOW");
    UUID capital = postAccount("capital-" + tag, "EQUITY", "ALLOW");
    String key = "winner-" + tag;

    int racers = 10;
    ExecutorService pool = Executors.newFixedThreadPool(racers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < racers; i++) {
      final int n = i;
      futures.add(
          pool.submit(
              () -> {
                if (!start.await(10, TimeUnit.SECONDS)) {
                  throw new IllegalStateException("start timed out");
                }
                ResponseEntity<Map> response =
                    postPosting(
                        key,
                        postingBody(
                            "variant " + n,
                            List.of(entry(cash, "DEBIT", "100"), entry(capital, "CREDIT", "100"))));
                return response.getStatusCode().value();
              }));
    }
    start.countDown();
    int created = 0;
    int conflicts = 0;
    for (Future<Integer> future : futures) {
      int status = future.get(30, TimeUnit.SECONDS);
      if (status == 201) {
        created++;
      } else if (status == 409) {
        conflicts++;
      } else {
        throw new IllegalStateException("unexpected status " + status);
      }
    }
    pool.shutdown();
    assertEquals(1, created, "exactly one payload must win the key");
    assertEquals(racers - 1, conflicts, "every other payload must conflict");
    try (Connection conn = LedgerDatabase.appConnection()) {
      assertEquals(1, tableCount(conn, "ledger_posting"));
    }
  }

  @Test
  void overdraftRaceCommitsOneAndRejectsOne() throws Exception {
    // Each round races two 8,000 withdrawals against 10,000 on fresh accounts. Rounds
    // repeat until the recorder proves a serialization retry happened — the verdict
    // below must hold in every round, retried or not.
    int rounds = 0;
    while (attempts.retries() == 0) {
      rounds++;
      assertTrue(rounds <= 10, "no serialization retry observed in 10 race rounds");
      raceOneOverdraftRound();
    }
    assertTrue(attempts.retries() >= 1, "a retry must have occurred, not just a fast reject");
  }

  private void raceOneOverdraftRound() throws Exception {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    UUID cash = postAccount("cash-" + tag, "ASSET", "DENY");
    UUID capital = postAccount("capital-" + tag, "EQUITY", "DENY");
    postPosting(
        "fund-" + tag,
        postingBody(
            "funding", List.of(entry(cash, "DEBIT", "10000"), entry(capital, "CREDIT", "10000"))));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Future<Integer> first =
        pool.submit(
            () -> {
              start.await(10, TimeUnit.SECONDS);
              return postPosting(
                      "race-" + tag + "-a",
                      postingBody(
                          "withdrawal a",
                          List.of(entry(capital, "DEBIT", "8000"), entry(cash, "CREDIT", "8000"))))
                  .getStatusCode()
                  .value();
            });
    Future<Integer> second =
        pool.submit(
            () -> {
              start.await(10, TimeUnit.SECONDS);
              return postPosting(
                      "race-" + tag + "-b",
                      postingBody(
                          "withdrawal b",
                          List.of(entry(capital, "DEBIT", "8000"), entry(cash, "CREDIT", "8000"))))
                  .getStatusCode()
                  .value();
            });
    start.countDown();
    Set<Integer> outcomes =
        new HashSet<>(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)));
    pool.shutdown();
    // One withdrawal commits, the other is rejected — by the application's projected
    // balance after a retry, or by the deferred trigger; either way HTTP says 409,
    // never 500 and never a second commit.
    assertEquals(Set.of(201, 409), outcomes);
    assertEquals("2000", balanceOf(cash));
  }

  @Test
  void repeatableReadLosesTheOverdraftRace() throws Exception {
    // Test-only REPEATABLE READ harness in the disposable anomaly database. Both
    // transactions read 10,000, both approve 8,000, both commit — the deferred
    // trigger cannot save them because each commit-time check runs under a
    // snapshot that predates the rival commit. Final state: −6,000 on a DENY
    // account. Production runs SERIALIZABLE precisely so this schedule aborts.
    UUID cash;
    UUID capital;
    try (Connection setup =
        LedgerDatabase.anomalyConnection(LedgerDatabase.OWNER, LedgerDatabase.OWNER_PASSWORD)) {
      cash = insertAccount(setup, "cash", "ASSET", "CAD", "DENY");
      capital = insertAccount(setup, "capital", "EQUITY", "CAD", "DENY");
      UUID opening = insertPosting(setup, newKey(), "STANDARD", null, "CAD", 2, "opening");
      insertEntry(setup, opening, 1, cash, "CAD", "DEBIT", 10_000);
      insertEntry(setup, opening, 2, capital, "CAD", "CREDIT", 10_000);
      setup.commit();
    }

    Connection first =
        LedgerDatabase.anomalyConnection(LedgerDatabase.APP, LedgerDatabase.APP_PASSWORD);
    Connection second =
        LedgerDatabase.anomalyConnection(LedgerDatabase.APP, LedgerDatabase.APP_PASSWORD);
    try {
      first.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      second.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      assertEquals(10_000, readBalance(first, cash));
      assertEquals(10_000, readBalance(second, cash));

      UUID firstPosting =
          insertPosting(first, newKey(), "STANDARD", null, "CAD", 2, "withdrawal one");
      insertEntry(first, firstPosting, 1, capital, "CAD", "DEBIT", 8_000);
      insertEntry(first, firstPosting, 2, cash, "CAD", "CREDIT", 8_000);
      UUID secondPosting =
          insertPosting(second, newKey(), "STANDARD", null, "CAD", 2, "withdrawal two");
      insertEntry(second, secondPosting, 1, capital, "CAD", "DEBIT", 8_000);
      insertEntry(second, secondPosting, 2, cash, "CAD", "CREDIT", 8_000);

      // Both commits succeed: the write-skew anomaly REPEATABLE READ permits.
      first.commit();
      second.commit();

      try (Connection check =
          LedgerDatabase.anomalyConnection(LedgerDatabase.OWNER, LedgerDatabase.OWNER_PASSWORD)) {
        assertEquals(-6_000, readBalance(check, cash));
      }
    } finally {
      first.close();
      second.close();
    }
  }

  private static long readBalance(Connection conn, UUID account) throws Exception {
    try (PreparedStatement ps =
        conn.prepareStatement("SELECT balance_minor FROM v_account_balance WHERE account_id = ?")) {
      ps.setObject(1, account);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return Long.parseLong(rs.getString(1));
      }
    }
  }
}
