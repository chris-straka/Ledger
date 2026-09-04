package dev.straka.ledger.support.crash;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Production gate: every pause point is a straight pass-through. */
@Component
@Profile("!crash-test")
public class NoOpCrashGate implements CrashGate {
  @Override
  public void awaitBeforeCommit() {}

  @Override
  public void awaitAfterCommit() {}
}
