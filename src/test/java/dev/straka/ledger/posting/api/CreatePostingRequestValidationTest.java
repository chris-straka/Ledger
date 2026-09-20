package dev.straka.ledger.posting.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Transport validation for the create-posting shape: nulls fail fast, well-formed passes. */
class CreatePostingRequestValidationTest {

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private static CreatePostingRequest.EntryLine line() {
    return new CreatePostingRequest.EntryLine(UUID.randomUUID(), "DEBIT", "100");
  }

  @Test
  void nullLinesIsViolation() {
    var request = new CreatePostingRequest("opening", Instant.now(), null);
    assertTrue(
        validator.validate(request).stream()
            .anyMatch(v -> v.getPropertyPath().toString().equals("lines")));
  }

  @Test
  void nestedLineViolationCascades() {
    var bad = new CreatePostingRequest.EntryLine(null, "DEBIT", "100");
    var request = new CreatePostingRequest("opening", Instant.now(), List.of(bad, line()));
    assertTrue(
        validator.validate(request).stream()
            .anyMatch(v -> v.getPropertyPath().toString().startsWith("lines")));
  }

  @Test
  void wellFormedRequestPasses() {
    var request = new CreatePostingRequest("opening", Instant.now(), List.of(line(), line()));
    assertEquals(0, validator.validate(request).size());
  }
}
