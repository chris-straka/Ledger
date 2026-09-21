package dev.straka.ledger.posting.domain;

import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.EntrySide;

/**
 * Record holding one ordered entry of a posting draft. Position in the list becomes the immutable
 * entry_number.
 */
public record PostingEntry(AccountId accountId, EntrySide side, EntryAmount amount) {
  public PostingEntry {
    if (accountId == null) {
      throw new InvalidPostingException("entry account is required");
    }
    if (side == null) {
      throw new InvalidPostingException("entry side is required");
    }
    if (amount == null) {
      throw new InvalidPostingException("entry amount is required");
    }
  }
}
