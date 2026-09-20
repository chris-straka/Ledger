package dev.straka.ledger.account.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Record holding an opaque keyset cursor over (recordedAt, postingId, lineNumber): the immutable
 * order entries are listed in. Encoded base64url so clients treat it as opaque; any tampering
 * fails parsing and the request is rejected as malformed rather than silently restarting the
 * listing.
 */
public record EntryCursor(Instant recordedAt, UUID postingId, int lineNumber) {
  public String encode() {
    String raw = recordedAt.toString() + "|" + postingId + "|" + lineNumber;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  public static EntryCursor parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("cursor is blank");
    }
    String decoded;
    try {
      decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("cursor is not base64url");
    }
    String[] parts = decoded.split("\\|", -1);
    if (parts.length != 3) {
      throw new IllegalArgumentException("cursor has wrong shape");
    }
    try {
      return new EntryCursor(
          Instant.parse(parts[0]), UUID.fromString(parts[1]), Integer.parseInt(parts[2]));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("cursor fields are malformed");
    }
  }
}
