package com.postalid.verification.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final String WEB_IMAGE_PATH_PREFIX = "/ui/images/";
    private static final String LOGIN_PATH = "/login";

    /** Longer than any configured operator name; anything longer is not one, and is not keyed. */
    private static final int MAX_USERNAME_LENGTH = 100;

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

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

        if (LOGIN_PATH.equals(path) && "POST".equalsIgnoreCase(request.getMethod())) {
            filterLogin(request, response, chain, ip);
            return;
        }

        RateLimiter.Decision decision = null;
        if (isVerifyRequest(path, request.getMethod())) {
            decision = rateLimiter.checkVerifyByIp(ip);
        } else if (path.startsWith(IMAGE_PATH_PREFIX) || path.startsWith(WEB_IMAGE_PATH_PREFIX)) {
            // No client id at this point, so the address budget is what protects the image
            // endpoints from being walked by an unauthenticated caller.
            decision = rateLimiter.checkImageByIp(ip);
        }

        if (decision != null && !decision.allowed()) {
            writeTooManyRequests(request, response, decision.retryAfterSeconds());
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * The console sign-in, which is a password check and so the place a guesser aims.
     *
     * <p>Mirrors the token endpoint: a per-address budget on every attempt, and a per-username
     * budget on failures that holds across addresses. Both are checked before the password is, so
     * a refused attempt never costs a BCrypt comparison. A refused browser goes back to the sign-in
     * page with a message rather than to a bare 429.
     */
    private void filterLogin(HttpServletRequest request, HttpServletResponse response,
                             FilterChain chain, String ip) throws ServletException, IOException {

        String username = request.getParameter("username");
        if (username != null && (username.isBlank() || username.length() > MAX_USERNAME_LENGTH)) {
            username = null;
        }

        RateLimiter.Decision byIp = rateLimiter.checkLoginByIp(ip);
        RateLimiter.Decision byUser = byIp.allowed() ? rateLimiter.peekLoginFailures(username) : byIp;
        if (!byUser.allowed()) {
            if (acceptsHtml(request)) {
                response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(byUser.retryAfterSeconds()));
                response.sendRedirect(request.getContextPath() + LOGIN_PATH + "?throttled");
            } else {
                writeTooManyRequests(request, response, byUser.retryAfterSeconds());
            }
            return;
        }

        chain.doFilter(request, response);

        // Form login answers a bad password with a redirect to /login?error.
        String location = response.getHeader(HttpHeaders.LOCATION);
        if (location != null && location.contains(LOGIN_PATH + "?error")) {
            rateLimiter.recordLoginFailure(username);
            if (!rateLimiter.peekLoginFailures(username).allowed()) {
                log.warn("Operator account '{}' has exhausted its failed sign-in budget; "
                        + "sign-in is refused until the budget refills", username);
            }
        }
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
