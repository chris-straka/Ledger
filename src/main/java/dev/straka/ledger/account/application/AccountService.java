package dev.straka.ledger.account.application;

import dev.straka.ledger.account.domain.Account;
import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.AccountType;
import dev.straka.ledger.account.domain.CurrencyCode;
import dev.straka.ledger.account.domain.InvalidAccountException;
import dev.straka.ledger.account.domain.OverdraftPolicy;
import dev.straka.ledger.account.persistence.AccountRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Service hosting the account use cases. One controller call maps to one use case; transactions
 * would live here, but every operation below is a single atomic statement, so there is deliberately
 * no transaction boundary yet. The first TransactionTemplate appears with the multi-statement
 * posting path.
 */
@Service
public class AccountService {

  private final AccountRepository accounts;

  public AccountService(AccountRepository accounts) {
    this.accounts = accounts;
  }

  public Account create(String code, String name, String currency, String type, String policy) {
    AccountType accountType = parseType(type);
    OverdraftPolicy overdraft = parsePolicy(policy);
    CurrencyCode currencyCode = new CurrencyCode(currency);
    return accounts.create(code, name, currencyCode, accountType, overdraft);
  }

  public Account get(AccountId id) {
    return accounts.requireById(id);
  }

  public EntryPage entries(AccountId id, String cursorRaw, String limitRaw) {
    accounts.requireById(id);

    AccountRepository.EntryCursorBean after = null;
    if (cursorRaw != null && !cursorRaw.isBlank()) {
      EntryCursor parsed = EntryCursor.parse(cursorRaw);
      after =
          new AccountRepository.EntryCursorBean(
              parsed.recordedAt(), parsed.postingId(), parsed.lineNumber());
    }

    int limit = parseLimit(limitRaw);
    List<AccountRepository.AccountEntry> rows = accounts.listEntries(id, after, limit + 1);

    List<EntryPage.Entry> page = new ArrayList<>();
    String nextCursor = null;
    for (int i = 0; i < Math.min(limit, rows.size()); i++) {
      page.add(EntryPage.Entry.from(rows.get(i)));
    }
    if (rows.size() > limit) {
      AccountRepository.AccountEntry last = rows.get(limit - 1);
      nextCursor = new EntryCursor(last.recordedAt(), last.postingId(), last.lineNumber()).encode();
    }
    return new EntryPage(List.copyOf(page), nextCursor);
  }

  private static int parseLimit(String raw) {
    if (raw == null || raw.isBlank()) {
      return 50;
    }
    try {
      int limit = Integer.parseInt(raw.trim());
      if (limit < 1 || limit > 500) {
        throw new IllegalArgumentException("limit must be 1-500");
      }
      return limit;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("limit must be an integer");
    }
  }

  public AccountRepository.AccountBalance balance(AccountId id) {
    accounts.requireById(id);
    return accounts
        .balanceOf(id)
        .orElseThrow(() -> new AccountNotFoundException("account not found: " + id));
  }

  private static AccountType parseType(String raw) {
    try {
      return AccountType.valueOf(raw);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidAccountException("unknown account type: " + raw);
    }
  }

  private static OverdraftPolicy parsePolicy(String raw) {
    try {
      return OverdraftPolicy.valueOf(raw);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidAccountException("unknown overdraft policy: " + raw);
    }
  }
}
