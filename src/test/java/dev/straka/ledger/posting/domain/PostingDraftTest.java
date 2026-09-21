package dev.straka.ledger.posting.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.AccountType;
import dev.straka.ledger.account.domain.CurrencyCode;
import dev.straka.ledger.account.domain.EntrySide;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure-domain posting validation. Oracles here are hand-computed literals, never the production
 * balance function — an assertion that calls production code for its expected value proves nothing.
 */
class PostingDraftTest {

  private static final CurrencyCode CAD = new CurrencyCode("CAD");

  private static PostingEntry entry(String account, EntrySide side, long amount) {
    return new PostingEntry(
        new AccountId(java.util.UUID.nameUUIDFromBytes(account.getBytes())),
        side,
        new EntryAmount(amount));
  }

  @Test
  void balancedTwoEntriesConstruct() {
    PostingDraft draft =
        new PostingDraft(
            CAD,
            List.of(
                entry("cash", EntrySide.DEBIT, 10_000),
                entry("capital", EntrySide.CREDIT, 10_000)));
    assertEquals(BigInteger.ZERO, draft.signedSum());
    assertEquals(2, draft.entries().size());
  }

  @Test
  void balancedMultiEntryConstructs() {
    PostingDraft draft =
        new PostingDraft(
            CAD,
            List.of(
                entry("cash", EntrySide.DEBIT, 6_000),
                entry("supplies", EntrySide.DEBIT, 4_000),
                entry("capital", EntrySide.CREDIT, 10_000)));
    assertEquals(BigInteger.ZERO, draft.signedSum());
  }

  @Test
  void totalsBeyondLongRangeStillJudgeExactly() {
    // Each leg fits in a long, but the totals overflow long addition: 60 entries of
    // Long.MAX/20 per side. BigInteger sees equality; wrapping long math would not.
    long leg = Long.MAX_VALUE / 20;
    List<PostingEntry> entries = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      entries.add(entry("cash-" + i, EntrySide.DEBIT, leg));
    }
    for (int i = 0; i < 30; i++) {
      entries.add(entry("capital-" + i, EntrySide.CREDIT, leg));
    }
    PostingDraft draft = new PostingDraft(CAD, entries);
    assertEquals(BigInteger.ZERO, draft.signedSum());

    List<PostingEntry> tampered = new ArrayList<>(entries);
    tampered.set(0, entry("cash-0", EntrySide.DEBIT, leg + 1));
    assertThrows(InvalidPostingException.class, () -> new PostingDraft(CAD, tampered));
  }

  @Test
  void unbalancedDraftIsRejected() {
    assertThrows(
        InvalidPostingException.class,
        () ->
            new PostingDraft(
                CAD,
                List.of(
                    entry("cash", EntrySide.DEBIT, 100), entry("capital", EntrySide.CREDIT, 50))));
  }

  @Test
  void entryCountBoundsAreEnforced() {
    PostingEntry debit = entry("cash", EntrySide.DEBIT, 100);
    PostingEntry credit = entry("capital", EntrySide.CREDIT, 100);
    assertThrows(InvalidPostingException.class, () -> new PostingDraft(CAD, List.of()));
    assertThrows(InvalidPostingException.class, () -> new PostingDraft(CAD, List.of(debit)));
    List<PostingEntry> many = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      many.add(entry("cash-" + i, EntrySide.DEBIT, 10));
      many.add(entry("capital-" + i, EntrySide.CREDIT, 10));
    }
    assertEquals(100, many.size());
    new PostingDraft(CAD, many);
    many.add(entry("extra-cash", EntrySide.DEBIT, 10));
    many.add(entry("extra-capital", EntrySide.CREDIT, 10));
    assertThrows(InvalidPostingException.class, () -> new PostingDraft(CAD, many));
  }

  @Test
  void singleAccountSelfCancelIsRejected() {
    AccountId cash = new AccountId(java.util.UUID.randomUUID());
    assertThrows(
        InvalidPostingException.class,
        () ->
            new PostingDraft(
                CAD,
                List.of(
                    new PostingEntry(cash, EntrySide.DEBIT, new EntryAmount(100)),
                    new PostingEntry(cash, EntrySide.CREDIT, new EntryAmount(100)))));
  }

  @Test
  void inputListIsCopiedImmutably() {
    List<PostingEntry> mutable =
        new ArrayList<>(
            List.of(entry("cash", EntrySide.DEBIT, 100), entry("capital", EntrySide.CREDIT, 100)));
    PostingDraft draft = new PostingDraft(CAD, mutable);
    mutable.clear();
    assertEquals(2, draft.entries().size());
    assertThrows(UnsupportedOperationException.class, () -> draft.entries().clear());
  }

  @Test
  void deltasAggregatePerAccountIndependentOfEntryOrder() {
    AccountId cash = new AccountId(java.util.UUID.randomUUID());
    AccountId capital = new AccountId(java.util.UUID.randomUUID());
    PostingDraft draft =
        new PostingDraft(
            CAD,
            List.of(
                new PostingEntry(cash, EntrySide.DEBIT, new EntryAmount(6_000)),
                new PostingEntry(cash, EntrySide.DEBIT, new EntryAmount(4_000)),
                new PostingEntry(capital, EntrySide.CREDIT, new EntryAmount(10_000))));
    Map<AccountId, BigInteger> deltas =
        draft.deltasByAccount(id -> id.equals(cash) ? AccountType.ASSET : AccountType.EQUITY);
    // Cash (asset, debit-normal): +10000. Capital (equity, credit-normal): +10000.
    assertEquals(BigInteger.valueOf(10_000), deltas.get(cash));
    assertEquals(BigInteger.valueOf(10_000), deltas.get(capital));
  }

  @Test
  void nullEntriesAreRejected() {
    List<PostingEntry> withNull =
        new ArrayList<>(
            List.of(entry("cash", EntrySide.DEBIT, 100), entry("capital", EntrySide.CREDIT, 100)));
    withNull.set(0, null);
    assertThrows(InvalidPostingException.class, () -> new PostingDraft(CAD, withNull));
  }

  @Test
  void entryAmountParseRules() {
    assertEquals(100, EntryAmount.parse("100").minorUnits());
    assertEquals(Long.MAX_VALUE, EntryAmount.parse(Long.toString(Long.MAX_VALUE)).minorUnits());
    assertThrows(InvalidPostingException.class, () -> EntryAmount.parse("0"));
    assertThrows(InvalidPostingException.class, () -> EntryAmount.parse("-5"));
    assertThrows(InvalidPostingException.class, () -> EntryAmount.parse("10.5"));
    assertThrows(InvalidPostingException.class, () -> EntryAmount.parse("1e3"));
    assertThrows(InvalidPostingException.class, () -> EntryAmount.parse(""));
    assertThrows(InvalidPostingException.class, () -> EntryAmount.parse(null));
    assertThrows(InvalidPostingException.class, () -> EntryAmount.parse("9223372036854775808"));
    assertThrows(InvalidPostingException.class, () -> new EntryAmount(0));
    assertThrows(InvalidPostingException.class, () -> new EntryAmount(-1));
  }
}
