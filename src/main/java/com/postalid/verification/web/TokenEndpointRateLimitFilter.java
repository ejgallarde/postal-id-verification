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
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Rate limits the OAuth endpoints that authenticate a client secret.
 *
 * <p>Every request to these endpoints costs a BCrypt comparison, which is slow on purpose. That
 * makes them the cheapest way to exhaust the server's CPU — a request carrying a real client id
 * and a wrong secret costs the caller nothing and the server a quarter of a second — and the only
 * route by which a client secret could be guessed. Two budgets apply, both checked before the
 * secret is:
 *
 * <ul>
 *   <li><b>Per source address</b>, on every request. Bounds what one address can make the server
 *       spend, whether or not its credentials are valid.</li>
 *   <li><b>Failed authentications per client id</b>, across all addresses. Bounds guessing of one
 *       client's secret by a caller who spreads attempts over many addresses. Successful requests
 *       never draw on it, so a client with the right secret is never slowed by its own traffic.
 *       The trade-off is the usual one for lockouts: someone who knows a client id can spend its
 *       failure budget and lock it out for a while. Client ids are not secret, but in a sandbox
 *       with known partners that is a far smaller risk than an unbounded guessing channel.</li>
 * </ul>
 *
 * <p>The refusal uses the OAuth error-response shape (RFC 6749 §5.2) rather than a problem
 * document, because the callers here are OAuth client libraries and that is what they parse.
 */
@Component
public class TokenEndpointRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenEndpointRateLimitFilter.class);

    /** Long enough for any registered client id; anything longer is not one, and is not keyed. */
    private static final int MAX_CLIENT_ID_LENGTH = 100;

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final Set<String> protectedPaths;

    public TokenEndpointRateLimitFilter(RateLimiter rateLimiter,
                                        ObjectMapper objectMapper,
                                        AuthorizationServerSettings settings) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        // Every endpoint that authenticates a client secret, not just the token endpoint:
        // introspection and revocation check the same secret the same slow way.
        this.protectedPaths = Set.of(
                settings.getTokenEndpoint(),
                settings.getTokenIntrospectionEndpoint(),
                settings.getTokenRevocationEndpoint());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !protectedPaths.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        RateLimiter.Decision byIp = rateLimiter.checkTokenByIp(RequestContextSupport.remoteIp(request));
        if (!byIp.allowed()) {
            refuse(response, byIp.retryAfterSeconds());
            return;
        }

        String clientId = claimedClientId(request);
        RateLimiter.Decision failures = rateLimiter.peekTokenFailures(clientId);
        if (!failures.allowed()) {
            refuse(response, failures.retryAfterSeconds());
            return;
        }

        chain.doFilter(request, response);

        // 401 is how the authorization server answers invalid_client: unknown id or wrong secret.
        if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            rateLimiter.recordTokenFailure(clientId);
            if (!rateLimiter.peekTokenFailures(clientId).allowed()) {
                log.warn("Client id '{}' has exhausted its failed-authentication budget; "
                        + "token requests for it are refused until the budget refills", clientId);
            }
        }
    }

    /**
     * The client id the request claims, before anything has verified it. Used only as a bucket
     * key, never trusted: an attacker naming someone else's client id spends that id's failure
     * budget, which is precisely the guessing this exists to bound.
     */
    static String claimedClientId(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String clientId = null;

        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                        StandardCharsets.UTF_8);
                int colon = decoded.indexOf(':');
                if (colon > 0) {
                    // RFC 6749 §2.3.1: the id is form-urlencoded before being base64-encoded.
                    clientId = URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException malformed) {
                clientId = null;
            }
        } else {
            clientId = request.getParameter("client_id");
        }

        if (clientId == null || clientId.isBlank() || clientId.length() > MAX_CLIENT_ID_LENGTH) {
            return null;
        }
        return clientId;
    }

    private void refuse(HttpServletResponse response, long retryAfter) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "too_many_requests");
        body.put("error_description", "Token request rate exceeded. Retry after " + retryAfter + " seconds.");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
