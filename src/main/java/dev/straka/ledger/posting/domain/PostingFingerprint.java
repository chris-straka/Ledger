package dev.straka.ledger.posting.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Record holding a semantic fingerprint: versioned SHA-256 over a documented canonical tuple,
 * so a retry after a lost HTTP response is recognized as the same request while a different
 * request under one key is a conflict. Covered: algorithm version, operation kind, description,
 * normalized instant, and ordered normalized entry lines. Excluded: generated IDs, recordedAt,
 * trace data, and transport-only fields. JSON property order is irrelevant; entry line order is
 * significant because it becomes the immutable line_number.
 */
public record PostingFingerprint(byte[] sha256) {
  public PostingFingerprint {
    if (sha256 == null || sha256.length != 32) {
      throw new InvalidPostingException("fingerprint must be 32 bytes");
    }
    sha256 = sha256.clone();
  }

  /**
   * Reversal fingerprint over the operation kind, target posting ID, reason, and normalized
   * instant. Server-derived inverse lines are not client input and stay out of the tuple.
   */
  public static PostingFingerprint v1Reversal(UUID target, String reason, Instant effectiveAt) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      field(digest, "v1");
      field(digest, PostingKind.REVERSAL.name());
      field(digest, target.toString());
      field(digest, reason);
      field(digest, effectiveAt.toString());
      return new PostingFingerprint(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public static PostingFingerprint v1Standard(
      String description, Instant effectiveAt, List<PostingLine> lines) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      field(digest, "v1");
      field(digest, PostingKind.STANDARD.name());
      field(digest, description);
      field(digest, effectiveAt.toString());
      digest.update(ByteBuffer.allocate(4).putInt(lines.size()).array());
      for (PostingLine line : lines) {
        field(digest, line.accountId().toString());
        field(digest, line.side().name());
        field(digest, Long.toString(line.amount().minorUnits()));
      }
      return new PostingFingerprint(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static void field(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
    digest.update(bytes);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PostingFingerprint fp && Arrays.equals(sha256, fp.sha256);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(sha256);
  }
}
