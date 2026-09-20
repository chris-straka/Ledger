package dev.straka.ledger.posting.api;

import dev.straka.ledger.posting.application.PostingNotFoundException;
import dev.straka.ledger.posting.application.PostingOutcome;
import dev.straka.ledger.posting.application.PostingService;
import dev.straka.ledger.posting.persistence.PostingRepository;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API handler for postings. Malformed requests fail here (HTTP 400); well-formed but invalid ones
 * fall through to the domain (HTTP 422). Each call runs inside {@link PostingService}'s
 * transaction, and repeating a request under the same idempotency key replays the original instead
 * of posting twice.
 */
@RestController
@RequestMapping("/v1/postings")
public class PostingController {

  private final PostingService postings;
  private final PostingRepository repository;

  public PostingController(PostingService postings, PostingRepository repository) {
    this.postings = postings;
    this.repository = repository;
  }

  @PostMapping
  public ResponseEntity<PostingResponse> post(
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreatePostingRequest request) {
    List<PostingService.PostingLineInput> lines =
        request.lines().stream()
            .map(
                l ->
                    new PostingService.PostingLineInput(
                        l.accountId(), l.side(), l.amountMinorUnits()))
            .toList();
    PostingOutcome outcome =
        postings.post(key, request.description().trim(), request.effectiveAt(), lines);
    UUID id =
        switch (outcome) {
          case PostingOutcome.Created created -> created.postingId();
          case PostingOutcome.Replayed replayed -> replayed.postingId();
        };

    PostingResponse body = load(id);
    if (outcome instanceof PostingOutcome.Replayed) {
      return ResponseEntity.ok().header("Idempotency-Replayed", "true").body(body);
    }
    return ResponseEntity.created(URI.create("/v1/postings/" + id)).body(body);
  }

  @GetMapping("/{postingId}")
  public PostingResponse get(@PathVariable UUID postingId) {
    return load(postingId);
  }

  @PostMapping("/{postingId}/reversals")
  public ResponseEntity<PostingResponse> reverse(
      @PathVariable UUID postingId,
      @RequestHeader("Idempotency-Key") String key,
      @Valid @RequestBody CreatePostingReversalRequest request) {
    PostingOutcome outcome =
        postings.reverse(key, postingId, request.reason().trim(), request.effectiveAt());
    UUID id =
        switch (outcome) {
          case PostingOutcome.Created created -> created.postingId();
          case PostingOutcome.Replayed replayed -> replayed.postingId();
        };

    PostingResponse body = load(id);
    if (outcome instanceof PostingOutcome.Replayed) {
      return ResponseEntity.ok().header("Idempotency-Replayed", "true").body(body);
    }
    return ResponseEntity.created(URI.create("/v1/postings/" + id)).body(body);
  }

  private PostingResponse load(UUID id) {
    return repository
        .findById(id)
        .map(PostingResponse::from)
        .orElseThrow(() -> new PostingNotFoundException("posting not found: " + id));
  }
}
