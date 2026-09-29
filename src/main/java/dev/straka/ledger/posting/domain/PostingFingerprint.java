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
 * Versioned SHA-256 over a request's semantic content. A retry after a lost response replays the
 * original posting; a different request under one key is a conflict.
 */
public record PostingFingerprint(byte[] sha256) {

  public PostingFingerprint {
    if (sha256 == null || sha256.length != 32) {
      throw new InvalidPostingException("fingerprint must be 32 bytes");
    }
    sha256 = sha256.clone();
  }

  /**
   * Covers the target posting ID, reason, and effective instant. The inverse entries are
   * server-derived, not client input, so they stay out.
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

  /**
   * Covers the description, effective instant, and ordered entries. Entry order is significant: it
   * becomes the immutable entry_number. Generated IDs, recordedAt, and transport fields stay out.
   */
  public static PostingFingerprint v1Standard(
      String description, Instant effectiveAt, List<PostingEntry> entries) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      field(digest, "v1");
      field(digest, PostingKind.STANDARD.name());
      field(digest, description);
      field(digest, effectiveAt.toString());
      digest.update(ByteBuffer.allocate(4).putInt(entries.size()).array());

      for (PostingEntry entry : entries) {
        field(digest, entry.accountId().toString());
        field(digest, entry.side().name());
        field(digest, Long.toString(entry.amount().minorUnits()));
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
