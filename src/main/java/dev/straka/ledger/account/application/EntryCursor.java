package dev.straka.ledger.account.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Record holding an opaque keyset cursor over (recordedAt, postingId, entryNumber): the immutable
 * order entries are listed in. Encoded base64url so clients treat it as opaque; any tampering fails
 * parsing and the request is rejected as malformed rather than silently restarting the listing.
 */
public record EntryCursor(Instant recordedAt, UUID postingId, int entryNumber) {
  public String encode() {
    String payload = recordedAt.toString() + "|" + postingId + "|" + entryNumber;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
  }

  public static EntryCursor parse(String cursor) {
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
