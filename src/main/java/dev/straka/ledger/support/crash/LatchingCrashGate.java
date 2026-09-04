package dev.straka.ledger.support.crash;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Latching gate, active only under the {@code crash-test} profile and driven by the profile-gated
 * internal controller. Each window is one-shot per JVM: arm, the next posting pauses and signals,
 * the harness kills or releases, and a bounded wait guarantees a stuck harness fails loudly instead
 * of hanging a request thread forever.
 */
@Component
@Profile("crash-test")
public class LatchingCrashGate implements CrashGate {

  private volatile boolean armedBefore;
  private volatile boolean armedAfter;
  private volatile CountDownLatch pausedBefore = new CountDownLatch(1);
  private volatile CountDownLatch releaseBefore = new CountDownLatch(1);
  private volatile CountDownLatch pausedAfter = new CountDownLatch(1);
  private volatile CountDownLatch releaseAfter = new CountDownLatch(1);

  public void arm(String window) {
    if ("before-commit".equals(window)) {
      armedBefore = true;
    } else if ("after-commit".equals(window)) {
      armedAfter = true;
    } else {
      throw new IllegalArgumentException("unknown crash window: " + window);
    }
  }

  public void release(String window) {
    if ("before-commit".equals(window)) {
      releaseBefore.countDown();
    } else if ("after-commit".equals(window)) {
      releaseAfter.countDown();
    } else {
      throw new IllegalArgumentException("unknown crash window: " + window);
    }
  }

  @Override
  public void awaitBeforeCommit() {
    if (!armedBefore) {
      return;
    }
    armedBefore = false;
    pausedBefore.countDown();
    await(releaseBefore, "before-commit");
  }

  @Override
  public void awaitAfterCommit() {
    if (!armedAfter) {
      return;
    }
    armedAfter = false;
    pausedAfter.countDown();
    await(releaseAfter, "after-commit");
  }

  public boolean paused(String window, long timeout, TimeUnit unit) throws InterruptedException {
    if ("before-commit".equals(window)) {
      return pausedBefore.await(timeout, unit);
    }
    if ("after-commit".equals(window)) {
      return pausedAfter.await(timeout, unit);
    }
    throw new IllegalArgumentException("unknown crash window: " + window);
  }

  private static void await(CountDownLatch latch, String window) {
    try {
      if (!latch.await(120, TimeUnit.SECONDS)) {
        throw new IllegalStateException("crash harness did not release window " + window);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("crash wait interrupted in window " + window, e);
    }
  }
}
