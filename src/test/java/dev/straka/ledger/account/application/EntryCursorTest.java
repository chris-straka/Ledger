package dev.straka.ledger.account.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntryCursorTest {

  @Test
  void roundTripIsStable() {
    EntryCursor cursor =
        new EntryCursor(Instant.parse("2026-09-04T12:00:00Z"), UUID.randomUUID(), 2);
    assertEquals(cursor, EntryCursor.parse(cursor.encode()));
  }

  @Test
  void tamperedCursorsAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> EntryCursor.parse("not-base64!!"));
    assertThrows(IllegalArgumentException.class, () -> EntryCursor.parse(""));
    assertThrows(IllegalArgumentException.class, () -> EntryCursor.parse(null));
    assertThrows(IllegalArgumentException.class, () -> EntryCursor.parse("e30="));
    String wrongShape =
        java.util.Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("only|two".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertThrows(IllegalArgumentException.class, () -> EntryCursor.parse(wrongShape));
  }
}
