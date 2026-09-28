package dev.straka.ledger.account.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CurrencyCodeTest {

  @Test
  void currencyCodesAreValidated() {
    assertEquals("CAD", new CurrencyCode("CAD").code());
    assertEquals("JPY", new CurrencyCode("JPY").code());
    assertThrows(InvalidAccountException.class, () -> new CurrencyCode("usd"));
    assertThrows(InvalidAccountException.class, () -> new CurrencyCode("USDD"));
    assertThrows(InvalidAccountException.class, () -> new CurrencyCode("US"));
    assertThrows(InvalidAccountException.class, () -> new CurrencyCode(null));
  }
}
