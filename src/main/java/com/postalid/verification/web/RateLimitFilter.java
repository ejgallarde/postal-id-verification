package com.postalid.verification.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;

/**
 * Applies the request budget before a request reaches a controller.
 *
 * <p>Placed ahead of authentication so that an unauthenticated flood is turned away cheaply,
 * without spending a BCrypt verification or a database round trip on it. The consequence is that
 * pre-authentication requests are limited by address only — the client id is not known yet — and
 * the per-client budget is checked again inside the verify path, where identity is established.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String VERIFY_PATH = "/api/v1/verify";
    private static final String IMAGE_PATH_PREFIX = "/api/v1/images/";
    private static final String WEB_VERIFY_PATH = "/verify";

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String ip = RequestContextSupport.remoteIp(request);

        RateLimiter.Decision decision = null;
        if (isVerifyRequest(path, request.getMethod())) {
            decision = rateLimiter.checkVerifyByIp(ip);
        } else if (path.startsWith(IMAGE_PATH_PREFIX)) {
            // No client id at this point, so the address budget is what protects the image
            // endpoint from being walked by an unauthenticated caller.
            decision = rateLimiter.checkVerifyByIp(ip);
        }

        if (decision != null && !decision.allowed()) {
            writeTooManyRequests(request, response, decision.retryAfterSeconds());
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isVerifyRequest(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return VERIFY_PATH.equals(path) || WEB_VERIFY_PATH.equals(path);
    }

    private void writeTooManyRequests(HttpServletRequest request, HttpServletResponse response, long retryAfter)
            throws IOException {

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));

        if (acceptsHtml(request)) {
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.getWriter().write("Too many requests. Try again in " + retryAfter + " seconds.");
            return;
        }

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
        problem.setType(URI.create("https://postalid.example/problems/rate-limited"));
        problem.setTitle("Too Many Requests");
        problem.setDetail("Request rate exceeded. Retry after " + retryAfter + " seconds.");
        problem.setProperty("retryAfterSeconds", retryAfter);

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), problem);
    }

    private boolean acceptsHtml(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains(MediaType.TEXT_HTML_VALUE);
    }
}
