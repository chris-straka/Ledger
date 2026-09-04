package dev.straka.ledger.support.crash;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Harness-only controls. This controller exists solely under the {@code crash-test} profile, is
 * never exposed in production, and carries no ledger semantics: arm is implicit (the gate starts
 * armed on boot), {@code /paused} lets the harness wait for the pause point, and {@code /release}
 * resumes a paused request thread when no kill is planned.
 */
@RestController
@RequestMapping("/internal/crash")
@Profile("crash-test")
public class InternalCrashController {

  private final LatchingCrashGate gate;

  public InternalCrashController(LatchingCrashGate gate) {
    this.gate = gate;
  }

  @PostMapping("/arm")
  public Map<String, String> arm(@RequestParam String window) {
    gate.arm(window);
    return Map.of("window", window, "armed", "true");
  }

  @GetMapping("/paused")
  public ResponseEntity<Map> paused(@RequestParam String window) throws Exception {
    boolean paused = gate.paused(window, 60, TimeUnit.SECONDS);
    return ResponseEntity.ok(Map.of("window", window, "paused", paused));
  }

  @PostMapping("/release")
  public Map<String, String> release(@RequestParam String window) {
    gate.release(window);
    return Map.of("window", window, "released", "true");
  }
}
