package com.postalid.verification.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Pulls caller identity and origin off the current request, for auditing and rate limiting. */
public final class RequestContextSupport {

    private RequestContextSupport() {
    }

    /**
     * The OAuth client id behind the request, or null for an unauthenticated or session caller.
     *
     * <p>Read from the validated JWT rather than any header, so it cannot be spoofed.
     */
    public static String currentClientId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            String azp = jwt.getClaimAsString("azp");
            return azp != null ? azp : jwt.getSubject();
        }
        return null;
    }

    public static String currentPrincipalName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    /**
     * The caller's address.
     *
     * <p>Deliberately reads only the socket address. {@code X-Forwarded-For} is caller-controlled
     * and trusting it here would let anyone reset their own rate-limit bucket by inventing a new
     * value per request. A deployment behind a trusted proxy should enable Spring Boot's
     * {@code server.forward-headers-strategy} instead, so the container resolves it and only
     * proxies it trusts can influence the result.
     */
    public static String remoteIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public static String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}
