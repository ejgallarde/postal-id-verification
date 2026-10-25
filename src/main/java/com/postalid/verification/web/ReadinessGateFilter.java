package com.postalid.verification.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Refuses verification until the instance has declared itself ready.
 *
 * <p>Tomcat starts listening before Spring Boot runs its startup runners, and with the
 * {@code ingest} profile one of those runners is loading the very records a verification would
 * check. Anything arriving in that window would get a confident INVALID for a card that is
 * perfectly genuine, just not loaded yet.
 *
 * <p>Readiness flips to {@code ACCEPTING_TRAFFIC} only after every runner has finished, so a load
 * balancer driven by {@code /actuator/health/readiness} already holds traffic back. This covers
 * the caller who reaches the port directly, as a third-party tester on a sandbox will: a 503 with
 * Retry-After tells them to come back, rather than telling them something false.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReadinessGateFilter extends OncePerRequestFilter {

    private static final long RETRY_AFTER_SECONDS = 15;

    private final ApplicationAvailability availability;

    public ReadinessGateFilter(ApplicationAvailability availability) {
        this.availability = availability;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !(path.equals("/api/v1/verify") || path.startsWith("/api/v1/images/")
                || path.equals("/verify") || path.startsWith("/ui/images/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS));
            return;
        }
        chain.doFilter(request, response);
    }
}
