package dev.straka.ledger.account.persistence;

import dev.straka.ledger.account.application.AccountConflictException;
import dev.straka.ledger.account.application.AccountNotFoundException;
import dev.straka.ledger.account.domain.Account;
import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.AccountType;
import dev.straka.ledger.account.domain.CurrencyCode;
import dev.straka.ledger.account.domain.InvalidAccountException;
import dev.straka.ledger.account.domain.OverdraftPolicy;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Repository running explicit SQL against the journal tables. Single statements are individually
 * atomic, so this slice needs no explicit transaction; the multi-statement posting path is where
 * SERIALIZABLE transactions begin. Amounts leave the database as scale-zero text parsed into {@link
 * BigInteger} — never through {@code double}.
 */
@Repository
public class AccountRepository {

  private final JdbcClient jdbc;

  public AccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public Account create(
      String code, String name, CurrencyCode currency, AccountType type, OverdraftPolicy policy) {
    try {
      return jdbc.sql(
              """
              INSERT INTO ledger_account (account_code, name, currency_code, account_type, overdraft_policy)
              VALUES (:code, :name, :currency, :type, :policy)
              RETURNING id, account_code, name, currency_code, account_type, overdraft_policy, created_at
              """)
          .param("code", code)
          .param("name", name)
          .param("currency", currency.code())
          .param("type", type.name())
          .param("policy", policy.name())
          .query(AccountRepository::mapAccount)
          .single();
    } catch (DuplicateKeyException e) {
      throw new AccountConflictException("account code already exists: " + code);
    } catch (DataAccessException e) {
      throw translate(e, code);
    }
  }

  public Optional<Account> findById(AccountId id) {
    return jdbc.sql(
            """
            SELECT id, account_code, name, currency_code, account_type, overdraft_policy, created_at
            FROM ledger_account WHERE id = :id
            """)
        .param("id", id.value())
        .query(AccountRepository::mapAccount)
        .optional();
  }

  /**
   * Current normal-side balance for the overdraft projection, derived from entries in one
   * statement. Empty when the account is missing; runs in the caller's transaction.
   */
  public Optional<BigInteger> balanceMinorOf(AccountId id) {
    return balanceOf(id).map(AccountBalance::balanceMinor);
  }

  /**
   * One keyset page of an account's journal lines in immutable order (recordedAt, postingId,
   * lineNumber). Fetches one row past the page so the caller can tell a next page exists.
   */
  public List<AccountEntry> listEntries(AccountId id, EntryCursorBean after, int fetch) {
    String sql =
        """
        SELECT e.posting_id, e.line_number, e.side, e.amount_minor, e.currency_code,
            p.posting_kind, p.description, p.recorded_at
        FROM ledger_entry e JOIN ledger_posting p ON p.id = e.posting_id
        WHERE e.account_id = :id
        """;
    if (after != null) {
      sql += " AND (p.recorded_at, e.posting_id, e.line_number) > (:rec, :pid, :line)";
    }

    sql += " ORDER BY p.recorded_at, e.posting_id, e.line_number LIMIT :fetch";
    var query = jdbc.sql(sql).param("id", id.value()).param("fetch", fetch);
    if (after != null) {
      query =
          query
              .param("rec", Timestamp.from(after.recordedAt()))
              .param("pid", after.postingId())
              .param("line", after.lineNumber());
    }

    return query
        .query(
            (rs, n) ->
                new AccountEntry(
                    (UUID) rs.getObject("posting_id"),
                    rs.getInt("line_number"),
                    rs.getString("side"),
                    Long.toString(rs.getLong("amount_minor")),
                    rs.getString("currency_code"),
                    rs.getString("posting_kind"),
                    rs.getString("description"),
                    rs.getTimestamp("recorded_at").toInstant()))
        .list();
  }

  public record AccountEntry(
      UUID postingId,
      int lineNumber,
      String side,
      String amountMinor,
      String currency,
      String postingKind,
      String description,
      Instant recordedAt) {}

  /** Cursor fields without importing the transport codec into persistence. */
  public record EntryCursorBean(Instant recordedAt, UUID postingId, int lineNumber) {}

  public Optional<AccountBalance> balanceOf(AccountId id) {
    return jdbc.sql(
            "SELECT account_id, currency_code, balance_minor FROM v_account_balance WHERE account_id = :id")
        .param("id", id.value())
        .query(
            (rs, n) ->
                new AccountBalance(
                    new AccountId((java.util.UUID) rs.getObject("account_id")),
                    new CurrencyCode(rs.getString("currency_code")),
                    new BigInteger(rs.getString("balance_minor"))))
        .optional();
  }

  public Account requireById(AccountId id) {
    return findById(id).orElseThrow(() -> new AccountNotFoundException("account not found: " + id));
  }

  private static Account mapAccount(ResultSet rs, int n) throws SQLException {
    Timestamp created = rs.getTimestamp("created_at");
    return new Account(
        new AccountId((java.util.UUID) rs.getObject("id")),
        rs.getString("account_code"),
        rs.getString("name"),
        new CurrencyCode(rs.getString("currency_code")),
        AccountType.valueOf(rs.getString("account_type")),
        OverdraftPolicy.valueOf(rs.getString("overdraft_policy")),
        created.toInstant());
  }

  private static RuntimeException translate(DataAccessException e, String code) {
    // SQLSTATE is read off java.sql.SQLException so main code never imports the driver.
    Throwable cause = e;

    while (cause != null) {
      if (cause instanceof SQLException sql && "23503".equals(sql.getSQLState())) {
        return new InvalidAccountException("unsupported currency for account: " + code);
      }
      cause = cause.getCause();
    }
    throw e;
  }

  public record AccountBalance(
      AccountId accountId, CurrencyCode currency, BigInteger balanceMinor) {}
}
