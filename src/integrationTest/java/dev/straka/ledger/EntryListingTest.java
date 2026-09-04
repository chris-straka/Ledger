package dev.straka.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
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
 * Account statement listing: immutable keyset order across pages, exact page contents, and the
 * 400/404 contract. Seeds five two-line postings (ten cash lines) through the real posting path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EntryListingTest extends LedgerIntegrationTest {

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
  private UUID postAccount(String code, String type) {
    Map<String, String> body = new HashMap<>();
    body.put("code", code);
    body.put("name", code);
    body.put("currency", "CAD");
    body.put("type", type);
    body.put("overdraftPolicy", "ALLOW");
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

  private void post(UUID cash, UUID capital, String key, String amount) {
    Map<String, Object> debit = new HashMap<>();
    debit.put("accountId", cash.toString());
    debit.put("side", "DEBIT");
    debit.put("amountMinor", amount);
    Map<String, Object> credit = new HashMap<>();
    credit.put("accountId", capital.toString());
    credit.put("side", "CREDIT");
    credit.put("amountMinor", amount);
    Map<String, Object> body = new HashMap<>();
    body.put("description", "funding " + key);
    body.put("effectiveAt", Instant.now().toString());
    body.put("lines", List.of(debit, credit));
    ResponseEntity<Map> response =
        rest.post()
            .uri("/v1/postings")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", key)
            .body(body)
            .retrieve()
            .toEntity(Map.class);
    assertEquals(201, response.getStatusCode().value());
  }

  @SuppressWarnings("unchecked")
  private Map getPage(UUID account, String query) {
    ResponseEntity<Map> response =
        rest.get()
            .uri("/v1/accounts/" + account + "/entries" + query)
            .retrieve()
            .onStatus(status -> true, (request, res) -> {})
            .toEntity(Map.class);
    assertEquals(200, response.getStatusCode().value());
    return response.getBody();
  }

  @Test
  @SuppressWarnings("unchecked")
  void pagesWalkTheWholeHistoryInOrder() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    UUID cash = postAccount("cash-" + tag, "ASSET");
    UUID capital = postAccount("capital-" + tag, "EQUITY");
    for (int i = 1; i <= 5; i++) {
      post(cash, capital, "fund-" + tag + "-" + i, String.valueOf(i * 100));
    }

    List<String> amounts = new ArrayList<>();
    List<String> kinds = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      String query = "?limit=4" + (cursor == null ? "" : "&cursor=" + cursor);
      Map page = getPage(cash, query);
      List<Map> entries = (List<Map>) page.get("entries");
      for (Map entry : entries) {
        amounts.add((String) entry.get("amountMinor"));
        kinds.add((String) entry.get("postingKind"));
        assertEquals("STANDARD", entry.get("postingKind"));
        assertEquals("DEBIT", entry.get("side"));
        assertNotNull(entry.get("recordedAt"));
        assertNotNull(entry.get("description"));
      }
      cursor = (String) page.get("nextCursor");
      pages++;
      assertTrue(pages <= 5, "pagination did not terminate");
    } while (cursor != null);

    assertEquals(List.of("100", "200", "300", "400", "500"), amounts);
    assertEquals(2, pages, "ten lines at limit 4 walk two full pages plus a short tail");
    assertEquals(5, kinds.size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void emptyHistoryReturnsEmptyPage() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    UUID cash = postAccount("cash-" + tag, "ASSET");
    Map page = getPage(cash, "");
    assertEquals(0, ((List<?>) page.get("entries")).size());
    assertNull(page.get("nextCursor"));
  }

  @Test
  void unknownAccountIsNotFound() {
    ResponseEntity<Map> response =
        rest.get()
            .uri("/v1/accounts/" + UUID.randomUUID() + "/entries")
            .retrieve()
            .onStatus(status -> true, (request, res) -> {})
            .toEntity(Map.class);
    assertEquals(404, response.getStatusCode().value());
  }

  @Test
  void malformedCursorAndLimitAreBadRequest() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    UUID cash = postAccount("cash-" + tag, "ASSET");
    ResponseEntity<Map> badCursor =
        rest.get()
            .uri("/v1/accounts/" + cash + "/entries?cursor=nope!!")
            .retrieve()
            .onStatus(status -> true, (request, res) -> {})
            .toEntity(Map.class);
    assertEquals(400, badCursor.getStatusCode().value());
    ResponseEntity<Map> badLimit =
        rest.get()
            .uri("/v1/accounts/" + cash + "/entries?limit=banana")
            .retrieve()
            .onStatus(status -> true, (request, res) -> {})
            .toEntity(Map.class);
    assertEquals(400, badLimit.getStatusCode().value());
    ResponseEntity<Map> hugeLimit =
        rest.get()
            .uri("/v1/accounts/" + cash + "/entries?limit=501")
            .retrieve()
            .onStatus(status -> true, (request, res) -> {})
            .toEntity(Map.class);
    assertEquals(400, hugeLimit.getStatusCode().value());
  }

  @Test
  @SuppressWarnings("unchecked")
  void reversalLinesAppearWithKind() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    UUID cash = postAccount("cash-" + tag, "ASSET");
    UUID capital = postAccount("capital-" + tag, "EQUITY");
    post(cash, capital, "fund-" + tag, "1000");

    Map<String, Object> debit = new HashMap<>();
    debit.put("accountId", cash.toString());
    debit.put("side", "DEBIT");
    debit.put("amountMinor", "400");
    Map<String, Object> credit = new HashMap<>();
    credit.put("accountId", capital.toString());
    credit.put("side", "CREDIT");
    credit.put("amountMinor", "400");
    Map<String, Object> spend = new HashMap<>();
    spend.put("description", "spend " + tag);
    spend.put("effectiveAt", Instant.now().toString());
    spend.put("lines", List.of(debit, credit));
    ResponseEntity<Map> created =
        rest.post()
            .uri("/v1/postings")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "spend-" + tag)
            .body(spend)
            .retrieve()
            .toEntity(Map.class);
    String spendId = (String) created.getBody().get("postingId");

    Map<String, Object> undo = new HashMap<>();
    undo.put("reason", "undo spend");
    undo.put("effectiveAt", Instant.now().toString());
    ResponseEntity<Map> reversed =
        rest.post()
            .uri("/v1/postings/" + spendId + "/reversals")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "undo-" + tag)
            .body(undo)
            .retrieve()
            .toEntity(Map.class);
    assertEquals(201, reversed.getStatusCode().value());

    Map page = getPage(cash, "?limit=50");
    List<Map> entries = (List<Map>) page.get("entries");
    // Fund DEBIT, spend DEBIT, reversal CREDIT — the reversal visibly undoes the spend.
    assertEquals(3, entries.size());
    assertEquals("STANDARD", entries.get(0).get("postingKind"));
    assertEquals("STANDARD", entries.get(1).get("postingKind"));
    assertEquals("REVERSAL", entries.get(2).get("postingKind"));
    assertEquals("DEBIT", entries.get(1).get("side"));
    assertEquals("CREDIT", entries.get(2).get("side"));
    assertNull(page.get("nextCursor"));
  }
}
