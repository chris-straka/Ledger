package dev.straka.ledger.support.config;

import dev.straka.ledger.posting.application.Sleeper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Application-time decisions (future effectiveAt) run on an injected {@link Clock} so tests pin
 * time; PostgreSQL still assigns recordedAt. The production {@link Sleeper} parks briefly between
 * serialization retries; tests inject a no-op.
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
