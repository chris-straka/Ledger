package dev.straka.ledger.posting.persistence;

import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.AccountType;
import dev.straka.ledger.account.domain.CurrencyCode;
import dev.straka.ledger.posting.domain.IdempotencyKey;
import dev.straka.ledger.posting.domain.PostingFingerprint;
import dev.straka.ledger.posting.domain.PostingKind;
import dev.straka.ledger.posting.domain.PostingLine;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Repository running explicit posting SQL. The header insert and the entry batch run inside the
 * caller's SERIALIZABLE transaction; this class never opens one. IDs and recordedAt stay
 * database-generated, read back through RETURNING.
 */
@Repository
public class PostingRepository {

  private final JdbcClient jdbc;
  private final JdbcTemplate template;

  public PostingRepository(JdbcClient jdbc, JdbcTemplate template) {
    this.jdbc = jdbc;
    this.template = template;
  }

  public UUID insertHeader(
      IdempotencyKey key,
      PostingFingerprint fingerprint,
      PostingKind kind,
      UUID reverses,
      CurrencyCode currency,
      int entryCount,
      String description,
      Instant effectiveAt) {
    return jdbc.sql(
            """
            INSERT INTO ledger_posting (idempotency_key, request_fingerprint, posting_kind,
                reverses_posting_id, currency_code, entry_count, description, effective_at)
            VALUES (:key, :fingerprint, :kind, :reverses, :currency, :count, :description, :effective)
            RETURNING id
            """)
        .param("key", key.value())
        .param("fingerprint", fingerprint.sha256())
        .param("kind", kind.name())
        .param("reverses", reverses)
        .param("currency", currency.code())
        .param("count", entryCount)
        .param("description", description)
        .param("effective", Timestamp.from(effectiveAt))
        .query((rs, n) -> (UUID) rs.getObject("id"))
        .single();
  }

  public void insertEntries(UUID postingId, CurrencyCode currency, List<PostingLine> lines) {
    List<Object[]> batch = new java.util.ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      PostingLine line = lines.get(i);
      batch.add(
          new Object[] {
            postingId,
            i + 1,
            line.accountId().value(),
            currency.code(),
            line.side().name(),
            line.amount().minorUnits()
          });
    }
    template.batchUpdate(
        "INSERT INTO ledger_entry (posting_id, line_number, account_id, currency_code, side,"
            + " amount_minor) VALUES (?, ?, ?, ?, ?, ?)",
        batch);
  }

  /** Committed header for an idempotency key, if any. Runs inside or outside transactions alike. */
  public Optional<CommittedPosting> findByKey(IdempotencyKey key) {
    return jdbc.sql(
            "SELECT id, request_fingerprint FROM ledger_posting WHERE idempotency_key = :key")
        .param("key", key.value())
        .query(
            (rs, n) ->
                new CommittedPosting(
                    (UUID) rs.getObject("id"),
                    new PostingFingerprint(rs.getBytes("request_fingerprint"))))
        .optional();
  }

  public Optional<StoredPosting> findById(UUID id) {
    List<StoredEntry> entries =
        jdbc.sql(
                """
                SELECT posting_id, line_number, account_id, currency_code, side, amount_minor
                FROM ledger_entry WHERE posting_id = :id ORDER BY line_number
                """)
            .param("id", id)
            .query(PostingRepository::mapEntry)
            .list();
    return jdbc.sql(
            """
            SELECT id, idempotency_key, posting_kind, reverses_posting_id, currency_code,
                entry_count, description, effective_at, recorded_at
            FROM ledger_posting WHERE id = :id
            """)
        .param("id", id)
        .query(
            (rs, n) ->
                new StoredPosting(
                    (UUID) rs.getObject("id"),
                    rs.getString("idempotency_key"),
                    PostingKind.valueOf(rs.getString("posting_kind")),
                    (UUID) rs.getObject("reverses_posting_id"),
                    new CurrencyCode(rs.getString("currency_code")),
                    rs.getInt("entry_count"),
                    rs.getString("description"),
                    rs.getTimestamp("effective_at").toInstant(),
                    rs.getTimestamp("recorded_at").toInstant(),
                    entries))
        .optional();
  }

  private static StoredEntry mapEntry(ResultSet rs, int n) throws SQLException {
    return new StoredEntry(
        rs.getInt("line_number"),
        new AccountId((UUID) rs.getObject("account_id")),
        new CurrencyCode(rs.getString("currency_code")),
        dev.straka.ledger.account.domain.EntrySide.valueOf(rs.getString("side")),
        rs.getLong("amount_minor"));
  }

  /**
   * Committed header behind an idempotency key: the posting ID plus the fingerprint replay checks
   * compare against.
   */
  public record CommittedPosting(UUID id, PostingFingerprint fingerprint) {}

  /** Account facts the posting path needs: currency, type, and overdraft policy. */
  public record ReferencedAccount(
      CurrencyCode currency,
      AccountType type,
      dev.straka.ledger.account.domain.OverdraftPolicy overdraftPolicy) {}

  public record StoredPosting(
      UUID id,
      String idempotencyKey,
      PostingKind kind,
      UUID reversesPostingId,
      CurrencyCode currency,
      int entryCount,
      String description,
      Instant effectiveAt,
      Instant recordedAt,
      List<StoredEntry> entries) {}

  public record StoredEntry(
      int lineNumber,
      AccountId accountId,
      CurrencyCode currency,
      dev.straka.ledger.account.domain.EntrySide side,
      long amountMinor) {}

  /**
   * Account rows for the draft's currency and normal-side checks. Only existing rows return; the
   * caller reports missing IDs as 404.
   */
  public Map<AccountId, ReferencedAccount> accountsOf(java.util.Set<AccountId> ids) {
    if (ids.isEmpty()) {
      return Map.of();
    }
    List<UUID> raw = ids.stream().map(AccountId::value).toList();
    java.util.Map<AccountId, ReferencedAccount> found = new java.util.HashMap<>();
    jdbc.sql(
            "SELECT id, currency_code, account_type, overdraft_policy FROM ledger_account WHERE id IN (:ids)")
        .param("ids", raw)
        .query(
            (rs, n) ->
                found.put(
                    new AccountId((UUID) rs.getObject("id")),
                    new ReferencedAccount(
                        new CurrencyCode(rs.getString("currency_code")),
                        AccountType.valueOf(rs.getString("account_type")),
                        dev.straka.ledger.account.domain.OverdraftPolicy.valueOf(
                            rs.getString("overdraft_policy")))))
        .list();
    return Map.copyOf(found);
  }
}
