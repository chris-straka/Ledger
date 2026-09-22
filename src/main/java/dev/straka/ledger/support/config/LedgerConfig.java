package dev.straka.ledger.support.config;

import dev.straka.ledger.posting.application.Sleeper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Injectable seams for PostingService: the {@link Clock} bounds client-supplied effectiveAt
 * (rejected beyond now plus five minutes) while recordedAt stays a Postgres default; the
 * {@link Sleeper} backs the jittered serialization-retry backoff so tests can skip the sleep.
 */
@Configuration
public class LedgerConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  public Sleeper sleeper() {
    return Thread::sleep;
  }
}
