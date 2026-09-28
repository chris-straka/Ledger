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

/** No transaction boundary: every operation is a single atomic statement. */
@Service
public class AccountService {

  private static final int MIN_LIMIT = 1;
  private static final int MAX_LIMIT = 500;
  private static final int DEFAULT_LIMIT = 50;

  private final AccountRepository accounts;

  public AccountService(AccountRepository accounts) {
    this.accounts = accounts;
  }

  public Account create(String code, String name, String currency, String type, String policy) {
    AccountType accountType = parseAccountType(type);
    OverdraftPolicy overdraft = parseOverdraftPolicy(policy);
    CurrencyCode currencyCode = new CurrencyCode(currency);
    return accounts.create(code, name, currencyCode, accountType, overdraft);
  }

  public Account get(AccountId accountId) {
    return accounts.requireById(accountId);
  }

  // Keyset pagination: cursor is last row seen and next page resumes
  public EntryPage entries(AccountId accountId, String cursorRaw, String limitRaw) {
    accounts.requireById(accountId); // make sure exists else 404

    // Decodes the cursor; null or blank means the first page.
    AccountRepository.EntryCursorBean cursorAfter = parseAfterCursor(cursorRaw);

    int limit = parseLimit(limitRaw);

    List<AccountRepository.AccountEntry> entryRows =
        accounts.listEntries(accountId, cursorAfter, limit + 1);

    List<EntryPage.Entry> entryPage = new ArrayList<>();

    String nextCursor = null;
    for (int i = 0; i < Math.min(limit, entryRows.size()); i++)
      entryPage.add(EntryPage.Entry.from(entryRows.get(i)));

    if (entryRows.size() > limit) {
      AccountRepository.AccountEntry last = entryRows.get(limit - 1);
      nextCursor =
          new EntryCursor(last.recordedAt(), last.postingId(), last.entryNumber()).encode();
    }

    return new EntryPage(List.copyOf(entryPage), nextCursor);
  }

  private static AccountRepository.EntryCursorBean parseAfterCursor(String cursorRaw) {
    if (cursorRaw == null || cursorRaw.isBlank()) return null;
    EntryCursor parsed = EntryCursor.parse(cursorRaw);
    return new AccountRepository.EntryCursorBean(
        parsed.recordedAt(), parsed.postingId(), parsed.entryNumber());
  }

  private static int parseLimit(String limitRaw) {
    if (limitRaw == null || limitRaw.isBlank()) return DEFAULT_LIMIT;

    try {
      int limit = Integer.parseInt(limitRaw.trim());
      if (limit < MIN_LIMIT || limit > MAX_LIMIT)
        throw new IllegalArgumentException("limit must be " + MIN_LIMIT + "-" + MAX_LIMIT);
      return limit;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("limit must be an integer");
    }
  }

  public AccountRepository.AccountBalance balance(AccountId accountId) {
    accounts.requireById(accountId);
    return accounts
        .balanceOf(accountId)
        .orElseThrow(() -> new AccountNotFoundException("account not found: " + accountId));
  }

  private static AccountType parseAccountType(String raw) {
    try {
      return AccountType.valueOf(raw);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidAccountException("unknown account type: " + raw);
    }
  }

  private static OverdraftPolicy parseOverdraftPolicy(String raw) {
    try {
      return OverdraftPolicy.valueOf(raw);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new InvalidAccountException("unknown overdraft policy: " + raw);
    }
  }
}
