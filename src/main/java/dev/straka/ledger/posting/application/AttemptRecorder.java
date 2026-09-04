package dev.straka.ledger.posting.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Counts posting attempts, serialization retries, and exhausted retries. The production
 * overdraft-race test reads these to prove a retry actually happened rather than passing on lucky
 * thread scheduling; the same counters back low-cardinality gauges for dashboards and alerts.
 */
@Component
public class AttemptRecorder {

  private final AtomicLong attempts = new AtomicLong();
  private final AtomicLong retries = new AtomicLong();
  private final AtomicLong exhausted = new AtomicLong();

  public AttemptRecorder(MeterRegistry registry) {
    registry.gauge("ledger.posting.attempts", attempts, AtomicLong::get);
    registry.gauge("ledger.posting.retries", retries, AtomicLong::get);
    registry.gauge("ledger.posting.retries.exhausted", exhausted, AtomicLong::get);
  }

  public void attemptStarted() {
    attempts.incrementAndGet();
  }

  public void retryScheduled() {
    retries.incrementAndGet();
  }

  public void exhausted() {
    exhausted.incrementAndGet();
  }

  public long attempts() {
    return attempts.get();
  }

  public long retries() {
    return retries.get();
  }

  public long exhaustedCount() {
    return exhausted.get();
  }

  public void reset() {
    attempts.set(0);
    retries.set(0);
    exhausted.set(0);
  }
}
