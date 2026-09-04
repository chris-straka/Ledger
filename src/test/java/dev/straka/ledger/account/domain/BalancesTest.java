package dev.straka.ledger.account.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Normal-side math for all five account types against hand-computed literals. */
class BalancesTest {

  @ParameterizedTest
  @CsvSource({
    "ASSET, 10000, 2500, 7500",
    "EXPENSE, 2500, 0, 2500",
    "LIABILITY, 0, 10000, 10000",
    "EQUITY, 0, 10000, 10000",
    "REVENUE, 3000, 8000, 5000"
  })
  void normalSideBalances(AccountType type, long debits, long credits, long expected) {
    assertEquals(
        BigInteger.valueOf(expected),
        Balances.of(type, BigInteger.valueOf(debits), BigInteger.valueOf(credits)));
  }

  @Test
  void negativeBalancesAreRepresentable() {
    assertEquals(
        BigInteger.valueOf(-6_000),
        Balances.of(AccountType.ASSET, BigInteger.valueOf(10_000), BigInteger.valueOf(16_000)));
  }

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
