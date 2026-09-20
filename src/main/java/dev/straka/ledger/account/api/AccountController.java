package dev.straka.ledger.account.api;

import dev.straka.ledger.account.application.AccountService;
import dev.straka.ledger.account.application.EntryPage;
import dev.straka.ledger.account.domain.Account;
import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.persistence.AccountRepository;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller, thin by design: validate syntax, translate DTOs, call one use case, map the result.
 * There are no update or delete routes — corrections are reversal postings, and accounts are
 * immutable.
 */
@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

  private final AccountService accounts;

  public AccountController(AccountService accounts) {
    this.accounts = accounts;
  }

  @PostMapping
  public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
    Account account =
        accounts.create(
            request.code().trim(),
            request.name().trim(),
            request.currency(),
            request.type(),
            request.overdraftPolicy());

    return ResponseEntity.created(URI.create("/v1/accounts/" + account.id()))
        .body(AccountResponse.from(account));
  }

  @GetMapping("/{accountId}")
  public AccountResponse get(@PathVariable UUID accountId) {
    return AccountResponse.from(accounts.get(new AccountId(accountId)));
  }

  @GetMapping("/{accountId}/balance")
  public BalanceResponse balance(@PathVariable UUID accountId) {
    AccountRepository.AccountBalance balance = accounts.balance(new AccountId(accountId));
    return new BalanceResponse(
        balance.accountId().toString(),
        balance.currency().code(),
        balance.balanceMinor().toString());
  }

  @GetMapping("/{accountId}/entries")
  public EntryPageResponse entries(
      @PathVariable UUID accountId,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String limit) {
    EntryPage page = accounts.entries(new AccountId(accountId), cursor, limit);
    return new EntryPageResponse(
        page.entries().stream()
            .map(
                e ->
                    new EntryPageResponse.EntryResponse(
                        e.postingId().toString(),
                        e.lineNumber(),
                        e.side(),
                        e.amountMinor(),
                        e.currency(),
                        e.postingKind(),
                        e.description(),
                        e.recordedAt()))
            .toList(),
        page.nextCursor());
  }
}
