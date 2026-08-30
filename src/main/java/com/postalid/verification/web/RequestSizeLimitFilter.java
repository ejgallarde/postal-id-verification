package com.postalid.verification.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * Rejects request bodies larger than a verification could possibly need.
 *
 * <p>Tomcat's own {@code max-http-form-post-size} governs form encodings only; a JSON body has no
 * container-level ceiling, so without this a caller can post megabytes and have the server buffer
 * and parse all of it before validation rejects five short fields. That is cheap for the caller
 * and expensive for the service, which is the shape of a denial of service.
 *
 * <p>Checked against {@code Content-Length} before the body is read, so an oversized request costs
 * nothing beyond the headers. A chunked request without a declared length is passed through and
 * bounded by the container instead.
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ObjectMapper objectMapper;

    public RequestSizeLimitFilter(
            @Value("${postalid.limits.max-request-bytes:16384}") long maxBytes,
            ObjectMapper objectMapper) {
        this.maxBytes = maxBytes;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        long declared = request.getContentLengthLong();
        if (declared > maxBytes) {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.PAYLOAD_TOO_LARGE);
            problem.setType(URI.create("https://postalid.example/problems/payload-too-large"));
            problem.setTitle("Payload Too Large");
            problem.setDetail("Request body exceeds " + maxBytes + " bytes.");

            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), problem);
            return;
        }

        chain.doFilter(request, response);
    }

    /** Only the API takes request bodies worth capping; the console posts a small form. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }
}
