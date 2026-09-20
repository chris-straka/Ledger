package dev.straka.ledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Application entry point. Wires the context; ledger semantics live in the domain and the SQL, never here. */
@SpringBootApplication
public class LedgerApplication {

  public static void main(String[] args) {
    SpringApplication.run(LedgerApplication.class, args);
  }
}
