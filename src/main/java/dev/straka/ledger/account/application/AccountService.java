package dev.straka.ledger.account.application;

import dev.straka.ledger.account.domain.Account;
import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.AccountType;
import dev.straka.ledger.account.domain.CurrencyCode;
import dev.straka.ledger.account.domain.InvalidAccountException;
import dev.straka.ledger.account.domain.OverdraftPolicy;
import dev.straka.ledger.account.persistence.AccountRepository;
import org.springframework.stereotype.Service;

/**
 * Account use cases. One controller call maps to one use case; transactions would live here, but
 * every operation below is a single atomic statement, so there is deliberately no transaction
 * boundary yet. The first TransactionTemplate appears with the multi-statement posting path.
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
