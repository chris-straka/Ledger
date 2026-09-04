package dev.straka.ledger.support.error;

import dev.straka.ledger.account.application.AccountConflictException;
import dev.straka.ledger.account.application.AccountNotFoundException;
import dev.straka.ledger.account.domain.InvalidAccountException;
import dev.straka.ledger.posting.domain.InvalidPostingException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * One place where errors become HTTP. Every body is {@code application/problem+json} with a stable
 * machine-readable {@code code}, a safe detail, the instance, and a trace id. SQL, constraint text,
 * credentials, and stack traces never leave the process.
 */
@RestControllerAdvice
public class ProblemAdvice {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail validation(MethodArgumentNotValidException e) {
    return problem(
        HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request body failed validation", e);
  }

  @ExceptionHandler({
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class
  })
  public ProblemDetail malformed(Exception e) {
    return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "malformed request syntax", e);
  }

  @ExceptionHandler(InvalidAccountException.class)
  public ProblemDetail accountInvalid(InvalidAccountException e) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_INVALID", e.getMessage(), e);
  }

  @ExceptionHandler(InvalidPostingException.class)
  public ProblemDetail postingInvalid(InvalidPostingException e) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "POSTING_INVALID", e.getMessage(), e);
  }

  @ExceptionHandler(AccountNotFoundException.class)
  public ProblemDetail notFound(AccountNotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", e.getMessage(), e);
  }

  @ExceptionHandler(AccountConflictException.class)
  public ProblemDetail conflict(AccountConflictException e) {
    return problem(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT", e.getMessage(), e);
  }

  private static ProblemDetail problem(HttpStatus status, String code, String detail, Exception e) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setProperty("code", code);
    body.setProperty("traceId", UUID.randomUUID().toString());
    body.setInstance(URI.create("/problems/" + code.toLowerCase().replace('_', '-')));
    return body;
  }
}
