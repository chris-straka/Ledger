package dev.straka.ledger.posting.application;

/**
 * Backoff sleep between serialization retries. Injected so unit tests never sleep; production parks
 * the request thread briefly, which is correct for a synchronous command API.
 */
@FunctionalInterface
public interface Sleeper {
  void sleep(long millis) throws InterruptedException;
}
