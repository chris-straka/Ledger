package dev.straka.ledger.account.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor over the listing order (recordedAt, postingId, entryNumber).
 * Base64url-encoded; anything that fails decoding is rejected as malformed.
 */
public record EntryCursor(Instant recordedAt, UUID postingId, int entryNumber) {

  public String encode() {
    String payload = recordedAt.toString() + "|" + postingId + "|" + entryNumber;

    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  public static EntryCursor decode(String cursor) {
    if (cursor == null || cursor.isBlank()) throw new IllegalArgumentException("Cursor is blank");

    String payload;

    try {
      payload = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("cursor is not base64url");
    }

    String[] parts = payload.split("\\|", -1);
    if (parts.length != 3) throw new IllegalArgumentException("cursor has wrong shape");

    try {
      return new EntryCursor(
          Instant.parse(parts[0]), UUID.fromString(parts[1]), Integer.parseInt(parts[2]));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("cursor fields are malformed");
    }
  }
}
