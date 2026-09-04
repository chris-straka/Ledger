package dev.straka.ledger.posting.application;

import dev.straka.ledger.account.application.AccountNotFoundException;
import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.AccountType;
import dev.straka.ledger.account.domain.CurrencyCode;
import dev.straka.ledger.account.domain.EntrySide;
import dev.straka.ledger.account.domain.OverdraftPolicy;
import dev.straka.ledger.account.persistence.AccountRepository;
import dev.straka.ledger.posting.domain.EntryAmount;
import dev.straka.ledger.posting.domain.IdempotencyKey;
import dev.straka.ledger.posting.domain.InvalidPostingException;
import dev.straka.ledger.posting.domain.PostingDraft;
import dev.straka.ledger.posting.domain.PostingFingerprint;
import dev.straka.ledger.posting.domain.PostingKind;
import dev.straka.ledger.posting.domain.PostingLine;
import dev.straka.ledger.posting.persistence.PostingRepository;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Atomic posting path. Each attempt runs in a fresh PostgreSQL {@code SERIALIZABLE} transaction
 * created by the {@link TransactionTemplate} below — never a private self-invoked
 * {@code @Transactional}, never in a controller, and never retried inside an aborted transaction.
 * The retry loop lives outside the template and re-runs the complete decision, because PostgreSQL
 * requires retrying everything that decided which writes to issue.
 *
 * <p>Why SERIALIZABLE: the anomaly is overdraft write skew. Two transactions read a 10,000 balance,
 * each approves an 8,000 withdrawal through disjoint row inserts, and both commit at REPEATABLE
 * READ leaving −6,000. At SERIALIZABLE the read/write dependency cannot serialize, so PostgreSQL
 * aborts one attempt with SQLSTATE 40001 and the full retry sees the new balance.
 */
@Service
public class PostingService {

  static final int MAX_ATTEMPTS = 5;
  static final long BACKOFF_BASE_MILLIS = 25;
  static final long BACKOFF_CAP_MILLIS = 250;
  static final Duration MAX_FUTURE_EFFECTIVE = Duration.ofMinutes(5);

  private final PostingRepository postings;
  private final AccountRepository accounts;
  private final TransactionTemplate serializable;
  private final Clock clock;
  private final Sleeper sleeper;

  public PostingService(
      PostingRepository postings,
      AccountRepository accounts,
      PlatformTransactionManager transactions,
      Clock clock,
      Sleeper sleeper) {
    this.postings = postings;
    this.accounts = accounts;
    this.clock = clock;
    this.sleeper = sleeper;
    this.serializable = new TransactionTemplate(transactions);
    this.serializable.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
  }

  /** Transport-level input for one standard posting. Line order becomes line_number. */
  public record PostingLineInput(UUID accountId, String side, String amountMinor) {}

  public PostingOutcome post(
      String key, String description, Instant effectiveAt, List<PostingLineInput> inputs) {
    IdempotencyKey idempotencyKey = new IdempotencyKey(key);
    String cleanDescription = cleanDescription(description);
    Instant cleanEffective = cleanEffectiveAt(effectiveAt);
    List<PostingLine> lines = toLines(inputs);
    // Currency is derived from accounts inside the transaction, never trusted
    // from the client — so it is not part of the compared body either.
    PostingFingerprint fingerprint =
        PostingFingerprint.v1Standard(cleanDescription, cleanEffective, lines);

    for (int attempt = 1; ; attempt++) {
      try {
        return serializable.execute(
            status ->
                attemptPost(idempotencyKey, fingerprint, cleanDescription, cleanEffective, lines));
      } catch (DuplicateKeyException e) {
        // The unique key — not a check-then-insert race — chose the winner. Our whole
        // attempt rolled back; read the winner outside it and compare fingerprints.
        return resolveIdempotencyRace(idempotencyKey, fingerprint);
      } catch (DataAccessException e) {
        if (isSerializationFailure(e) && attempt < MAX_ATTEMPTS) {
          backoff(attempt);
          continue;
        }
        if (isSerializationFailure(e)) {
          throw new PostingRetryExhaustedException(
              "posting did not serialize after " + MAX_ATTEMPTS + " attempts");
        }
        // The deferred trigger is the last line of defense: if it rejects a commit
        // the application check admitted (e.g. a concurrent overdraft decision), the
        // verdict still surfaces as a business rejection, never a 500.
        RuntimeException translated = translateTriggerRejection(e);
        if (translated != null) {
          throw translated;
        }
        throw e;
      }
    }
  }

  private PostingOutcome attemptPost(
      IdempotencyKey key,
      PostingFingerprint fingerprint,
      String description,
      Instant effectiveAt,
      List<PostingLine> lines) {
    PostingRepository.CommittedPosting existing = postings.findByKey(key).orElse(null);
    if (existing != null) {
      return compare(existing, fingerprint);
    }
    Map<AccountId, PostingRepository.ReferencedAccount> referenced = loadAccounts(lines);
    CurrencyCode currency = singleCurrency(referenced);
    PostingDraft draft = new PostingDraft(currency, lines);
    rejectOverdraft(draft, referenced);
    UUID id =
        postings.insertHeader(
            key,
            fingerprint,
            PostingKind.STANDARD,
            null,
            currency,
            lines.size(),
            description,
            effectiveAt);
    postings.insertEntries(id, currency, lines);
    // Success is not visible to HTTP until commit plus all deferred triggers succeed.
    return new PostingOutcome.Created(id);
  }

  private PostingOutcome resolveIdempotencyRace(
      IdempotencyKey key, PostingFingerprint fingerprint) {
    PostingRepository.CommittedPosting winner =
        postings
            .findByKey(key)
            .orElseThrow(
                () -> new PostingRetryExhaustedException("idempotency winner vanished; retry"));
    return compare(winner, fingerprint);
  }

  private static PostingOutcome compare(
      PostingRepository.CommittedPosting committed, PostingFingerprint fingerprint) {
    if (committed.fingerprint().equals(fingerprint)) {
      return new PostingOutcome.Replayed(committed.id());
    }
    throw new IdempotencyConflictException("idempotency key already used by a different request");
  }

  private Map<AccountId, PostingRepository.ReferencedAccount> loadAccounts(
      List<PostingLine> lines) {
    Set<AccountId> ids = new HashSet<>();
    for (PostingLine line : lines) {
      ids.add(line.accountId());
    }
    Map<AccountId, PostingRepository.ReferencedAccount> found = postings.accountsOf(ids);
    for (AccountId id : ids) {
      if (!found.containsKey(id)) {
        throw new AccountNotFoundException("account not found: " + id);
      }
    }
    return found;
  }

  private static CurrencyCode singleCurrency(
      Map<AccountId, PostingRepository.ReferencedAccount> referenced) {
    Set<String> codes = new HashSet<>();
    for (PostingRepository.ReferencedAccount account : referenced.values()) {
      codes.add(account.currency().code());
    }
    if (codes.size() != 1) {
      throw new InvalidPostingException("posting touches more than one currency: " + codes);
    }
    return new CurrencyCode(codes.iterator().next());
  }

  private void rejectOverdraft(
      PostingDraft draft, Map<AccountId, PostingRepository.ReferencedAccount> referenced) {
    Map<AccountId, AccountType> types = new HashMap<>();
    for (Map.Entry<AccountId, PostingRepository.ReferencedAccount> entry : referenced.entrySet()) {
      types.put(entry.getKey(), entry.getValue().type());
    }
    Map<AccountId, BigInteger> deltas = draft.deltasByAccount(types::get);
    for (Map.Entry<AccountId, BigInteger> delta : deltas.entrySet()) {
      if (referenced.get(delta.getKey()).overdraftPolicy() != OverdraftPolicy.DENY) {
        continue;
      }
      BigInteger current = currentBalance(delta.getKey(), accounts);
      if (current.add(delta.getValue()).signum() < 0) {
        throw new OverdraftRejectedException(
            "posting overdraws account " + delta.getKey() + " (balance " + current + ")");
      }
    }
  }

  private BigInteger currentBalance(AccountId id, AccountRepository accounts) {
    // One statement per DENY account, inside the serializable attempt, so the
    // read participates in the serializability guarantee instead of racing it.
    return accounts
        .balanceMinorOf(id)
        .orElseThrow(() -> new AccountNotFoundException("account not found: " + id));
  }

  private static List<PostingLine> toLines(List<PostingLineInput> inputs) {
    if (inputs == null) {
      throw new InvalidPostingException("entry lines are required");
    }
    List<PostingLine> lines = new ArrayList<>(inputs.size());
    for (PostingLineInput input : inputs) {
      if (input == null || input.accountId() == null) {
        throw new InvalidPostingException("entry account is required");
      }
      EntrySide side;
      try {
        side = EntrySide.valueOf(input.side());
      } catch (IllegalArgumentException | NullPointerException e) {
        throw new InvalidPostingException("unknown entry side: " + input.side());
      }
      lines.add(
          new PostingLine(
              new AccountId(input.accountId()), side, EntryAmount.parse(input.amountMinor())));
    }
    return lines;
  }

  private static String cleanDescription(String description) {
    if (description == null || description.isBlank() || description.length() > 500) {
      throw new InvalidPostingException("description must be 1-500 characters");
    }
    for (int i = 0; i < description.length(); i++) {
      char c = description.charAt(i);
      if (c <= 0x1F || c == 0x7F) {
        throw new InvalidPostingException("description must not contain control characters");
      }
    }
    return description;
  }

  private Instant cleanEffectiveAt(Instant effectiveAt) {
    if (effectiveAt == null) {
      throw new InvalidPostingException("effectiveAt is required");
    }
    if (effectiveAt.isAfter(clock.instant().plus(MAX_FUTURE_EFFECTIVE))) {
      throw new InvalidPostingException("effectiveAt is more than five minutes in the future");
    }
    return effectiveAt;
  }

  static boolean isSerializationFailure(DataAccessException e) {
    // Any link in the chain may carry the serialization state (batch and
    // transaction wrappers nest it), so every SQLException is inspected.
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof SQLException sql
          && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  private static String sqlStateOf(DataAccessException e) {
    Throwable cause = e;
    while (cause != null) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null) {
        return sql.getSQLState();
      }
      cause = cause.getCause();
    }
    return "";
  }

  private static RuntimeException translateTriggerRejection(DataAccessException e) {
    if (!"23514".equals(sqlStateOf(e)) && !"23503".equals(sqlStateOf(e))) {
      return null;
    }
    String message = e.getMessage() == null ? "" : e.getMessage();
    if (message.contains("ledger_posting_overdraft")) {
      return new OverdraftRejectedException("posting overdraws a DENY account");
    }
    if (message.contains("ledger_")) {
      return new InvalidPostingException("posting rejected by ledger constraints at commit");
    }
    return null;
  }

  private void backoff(int attempt) {
    long bound = Math.min(BACKOFF_CAP_MILLIS, BACKOFF_BASE_MILLIS * (1L << attempt));
    try {
      sleeper.sleep(ThreadLocalRandom.current().nextLong(bound + 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PostingRetryExhaustedException("retry interrupted");
    }
  }
}
