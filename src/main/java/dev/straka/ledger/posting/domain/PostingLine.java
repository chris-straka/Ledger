package dev.straka.ledger.posting.domain;

import dev.straka.ledger.account.domain.AccountId;
import dev.straka.ledger.account.domain.EntrySide;

/** Record holding one ordered line of a posting draft. Position in the list becomes the immutable line_number. */
public record PostingLine(AccountId accountId, EntrySide side, EntryAmount amount) {
  public PostingLine {
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
