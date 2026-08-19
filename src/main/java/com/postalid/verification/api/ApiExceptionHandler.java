package com.postalid.verification.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns failures into RFC 9457 problem documents.
 *
 * <p>Validation problems name the offending fields, because those are the caller's own inputs and
 * telling them is how they fix the call. Everything else is answered generically: an unexpected
 * exception yields a request id and nothing more, with the detail going to the log. Echoing a
 * message from deep in the stack is how database structure and file paths escape into responses.
 */
@RestControllerAdvice(basePackages = "com.postalid.verification.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://postalid.example/problems/";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(PROBLEM_BASE + "validation-failed"));
        problem.setTitle("Validation Failed");
        problem.setDetail("One or more fields are missing or malformed.");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(PROBLEM_BASE + "malformed-request"));
        problem.setTitle("Malformed Request");
        // The parser's own message can quote the body it choked on, which may be personal data.
        problem.setDetail("The request body could not be parsed as JSON matching the expected schema.");
        return problem;
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException e) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(URI.create(PROBLEM_BASE + "rate-limited"));
        problem.setTitle("Too Many Requests");
        problem.setDetail("Request rate exceeded. Retry after " + e.getRetryAfterSeconds() + " seconds.");
        problem.setProperty("retryAfterSeconds", e.getRetryAfterSeconds());

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
                .body(problem);
    }

    /**
     * Passes through exceptions that already carry the status they want.
     *
     * <p>Without this, the catch-all below would turn a deliberate 404 from the image endpoint
     * into a 500 — which is both wrong and, because a 500 reads as "something broke here" rather
     * than "no such thing", a hint that the reference was interesting.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ProblemDetail> handleErrorResponse(ErrorResponseException e) {
        return ResponseEntity.status(e.getStatusCode()).body(e.getBody());
    }

    /**
     * Authentication and authorization failures belong to Spring Security's translation filter,
     * which turns them into 401 and 403. Rethrowing keeps the catch-all from converting a denial
     * into a 500 and, worse, into a response that looks like a server fault rather than a refusal.
     */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public void rethrowSecurityException(RuntimeException e) {
        throw e;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        String reference = java.util.UUID.randomUUID().toString();
        log.error("Unhandled exception [{}]", reference, e);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create(PROBLEM_BASE + "internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setDetail("The request could not be completed. Quote the reference when reporting this.");
        problem.setProperty("reference", reference);
        return problem;
    }
}
