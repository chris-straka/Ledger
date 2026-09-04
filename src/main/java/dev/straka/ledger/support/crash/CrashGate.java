package dev.straka.ledger.support.crash;

/**
 * Crash-test pause points. The production bean is a no-op; the {@code crash-test} profile arms real
 * latches so the harness can SIGKILL the JVM at two exact windows: with a posting transaction open
 * (after header plus entries are inserted, before commit) and after commit but before the HTTP
 * response is written.
 */
public interface CrashGate {
  void awaitBeforeCommit();

  void awaitAfterCommit();
}
