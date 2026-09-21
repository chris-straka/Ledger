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

  private static CreatePostingRequest.Entry entry() {
    return new CreatePostingRequest.Entry(UUID.randomUUID(), "DEBIT", "100");
  }

  @Test
  void nullEntriesIsViolation() {
    var request = new CreatePostingRequest("opening", Instant.now(), null);
    assertTrue(
        validator.validate(request).stream()
            .anyMatch(v -> v.getPropertyPath().toString().equals("entries")));
  }

  @Test
  void nestedEntryViolationCascades() {
    var bad = new CreatePostingRequest.Entry(null, "DEBIT", "100");
    var request = new CreatePostingRequest("opening", Instant.now(), List.of(bad, entry()));
    assertTrue(
        validator.validate(request).stream()
            .anyMatch(v -> v.getPropertyPath().toString().startsWith("entries")));
  }

  @Test
  void wellFormedRequestPasses() {
    var request = new CreatePostingRequest("opening", Instant.now(), List.of(entry(), entry()));
    assertEquals(0, validator.validate(request).size());
  }
}
