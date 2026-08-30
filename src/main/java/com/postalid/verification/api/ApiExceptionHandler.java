package com.postalid.verification.api;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns failures into RFC 9457 problem documents.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the whole family of standard Spring MVC
 * faults — unsupported media type, unacceptable representation, wrong method, missing parameter —
 * gets its correct 4xx status. Previously only the cases with an explicit handler were mapped and
 * everything else fell through to the catch-all, so a client sending the wrong {@code Content-Type}
 * was told the <em>server</em> had failed, and a routine client mistake logged a stack trace.
 *
 * <p>Validation problems name the offending fields, because those are the caller's own inputs and
 * telling them is how they fix the call. Everything else is answered generically: an unexpected
 * exception yields a reference and nothing more, with the detail going to the log. Echoing a
 * message from deep in the stack is how database structure and file paths escape into responses.
 */
@RestControllerAdvice(basePackages = "com.postalid.verification.api")
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://postalid.example/problems/";

    /**
     * Field-level detail for validation failures.
     *
     * <p>Overrides the parent so the response keeps the {@code errors} map clients rely on; the
     * inherited version reports only that validation failed.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(PROBLEM_BASE + "validation-failed"));
        problem.setTitle("Validation Failed");
        problem.setDetail("One or more fields are missing or malformed.");
        problem.setProperty("errors", errors);

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A malformed body, reported without quoting it.
     *
     * <p>The parser's own message repeats the input it choked on, which on this endpoint is
     * somebody's name and date of birth.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create(PROBLEM_BASE + "malformed-request"));
        problem.setTitle("Malformed Request");
        problem.setDetail("The request body could not be parsed as JSON matching the expected schema.");

        return ResponseEntity.badRequest().body(problem);
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

    // ErrorResponseException — and so ResponseStatusException, which the image endpoint throws
    // for an unusable reference — is handled by the parent, which already returns the status and
    // problem body the exception carries. Declaring it here as well is an ambiguous mapping and
    // stops the context starting.

    /**
     * Authentication and authorization failures belong to Spring Security's translation filter,
     * which turns them into 401 and 403. Rethrowing keeps the catch-all from converting a denial
     * into a 500 and, worse, into a response that looks like a server fault rather than a refusal.
     */
    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public void rethrowSecurityException(RuntimeException e) {
        throw e;
    }

    /**
     * The genuine unknowns.
     *
     * <p>Reached only after the parent has had its say, so anything landing here really is
     * unexpected and deserves the stack trace it gets.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request) {
        String reference = UUID.randomUUID().toString();
        log.error("Unhandled exception [{}] on {} {}", reference, request.getMethod(),
                request.getRequestURI(), e);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setType(URI.create(PROBLEM_BASE + "internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setDetail("The request could not be completed. Quote the reference when reporting this.");
        problem.setProperty("reference", reference);

        return ResponseEntity.internalServerError().body(problem);
    }
}
