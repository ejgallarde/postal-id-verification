package com.postalid.verification.api;

import com.postalid.verification.api.dto.VerifyRequest;
import com.postalid.verification.api.dto.VerifyResponse;
import com.postalid.verification.audit.VerificationContext;
import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.DocumentType;
import com.postalid.verification.verify.VerificationFacade;
import com.postalid.verification.verify.VerificationQuery;
import com.postalid.verification.web.EnumerationThrottle;
import com.postalid.verification.web.RateLimiter;
import com.postalid.verification.web.RequestContextSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The verification endpoint.
 *
 * <p>A non-matching submission returns {@code 200 INVALID}, not {@code 404}. The request was
 * understood and answered; the identity simply did not check out. Reserving 4xx for actual
 * protocol faults keeps "we could not process this" distinguishable from "this is not a valid
 * card", which matters to any client trying to retry sensibly.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Verification", description = "Validate a Postal ID against the enrollment database")
public class VerificationController {

    /** Grants a client the field-level failure detail on an INVALID result. */
    private static final String DIAGNOSTICS_AUTHORITY = "SCOPE_postalid.diagnostics";

    private final VerificationFacade verificationFacade;
    private final RateLimiter rateLimiter;
    private final EnumerationThrottle enumerationThrottle;
    private final CardResponseMapper mapper;
    private final PostalIdProperties properties;

    public VerificationController(VerificationFacade verificationFacade,
                                  RateLimiter rateLimiter,
                                  EnumerationThrottle enumerationThrottle,
                                  CardResponseMapper mapper,
                                  PostalIdProperties properties) {
        this.verificationFacade = verificationFacade;
        this.rateLimiter = rateLimiter;
        this.enumerationThrottle = enumerationThrottle;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Operation(
            summary = "Verify a Postal ID",
            description = """
                    Checks the submitted details against the enrollment record identified by the
                    postal reference number, and answers VALID or INVALID.

                    VALID requires all of: a record with that reference number exists, it is not
                    deleted, it has reached an issued status, and the surname, first name, middle
                    name and date of birth all match.

                    A VALID response carries the cardholder details as printed on the card, the
                    card's validity window, and short-lived relative URLs for the photograph and
                    signature. Fetching the images needs the postalid.images scope.

                    Expiry is derived, not supplied by the source data: it is the card's production
                    date plus a configured validity period, and is flagged as derived. An expired
                    card still returns VALID with validity.status = EXPIRED, because the identity
                    did check out - read validity.status and apply your own policy.

                    Every unsuccessful case produces the same INVALID body. The endpoint will not
                    reveal whether the reference number exists or which field failed, because that
                    would make it usable for harvesting other people's details. Clients granted the
                    postalid.diagnostics scope additionally receive a reason object naming every
                    mismatched field.
                    """,
            security = @SecurityRequirement(name = "oauth2-client-credentials", scopes = "postalid.verify"))
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Verification completed. Inspect `result` for the answer, and on "
                            + "VALID also `validity.status` before treating the card as current."),
            @ApiResponse(responseCode = "400",
                    description = "The request body failed validation.", content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "401",
                    description = "Missing, expired or invalid access token.", content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "403",
                    description = "Token lacks the postalid.verify scope.", content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "429",
                    description = "Rate limit exceeded; see the Retry-After header.", content = @io.swagger.v3.oas.annotations.media.Content())
    })
    @PostMapping("/verify")
    @PreAuthorize("hasAuthority('SCOPE_postalid.verify')")
    public ResponseEntity<VerifyResponse> verify(@Valid @RequestBody VerifyRequest request,
                                                 HttpServletRequest httpRequest) {

        String clientId = RequestContextSupport.currentClientId();

        // The filter already applied the per-address budget. Now that the token is validated and
        // the client is known, apply the per-client budget too.
        RateLimiter.Decision decision = rateLimiter.checkVerifyByClient(clientId);
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }

        // A caller failing repeatedly is walking reference numbers, not checking cards people
        // hold. The per-minute budget alone would let that continue indefinitely at a patient rate.
        long penalty = enumerationThrottle.retryAfterSeconds(clientId);
        if (penalty > 0) {
            throw new RateLimitExceededException(penalty);
        }

        String requestId = UUID.randomUUID().toString();
        VerificationContext context = new VerificationContext(
                requestId,
                clientId,
                RequestContextSupport.currentPrincipalName(),
                VerificationContext.Channel.API,
                RequestContextSupport.remoteIp(httpRequest),
                RequestContextSupport.userAgent(httpRequest));

        VerificationQuery query = new VerificationQuery(
                request.postalRefNo(),
                request.firstName(),
                request.middleName(),
                request.lastName(),
                request.dateOfBirth());

        VerificationFacade.Result result = verificationFacade.verify(query, context);

        if (result.valid()) {
            enumerationThrottle.recordSuccess(clientId);
        } else {
            enumerationThrottle.recordFailure(clientId);
        }

        VerifyResponse body = result.valid()
                ? buildValidResponse(requestId, result)
                : buildInvalidResponse(requestId, result);

        return ResponseEntity.ok()
                // A verification answer is about a specific person and must not be cached or
                // stored by anything between here and the caller.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Request-Id", requestId)
                // Budget headers, so a client can pace itself rather than learning the ceiling
                // only when it is refused. Names follow the IETF RateLimit header field draft.
                .header("RateLimit-Limit", String.valueOf(properties.getRateLimit().getVerifyPerClientPerMinute()))
                .header("RateLimit-Remaining", String.valueOf(decision.remaining()))
                .header("RateLimit-Policy", properties.getRateLimit().getVerifyPerClientPerMinute() + ";w=60")
                .body(body);
    }

    private VerifyResponse buildValidResponse(String requestId, VerificationFacade.Result result) {
        return VerifyResponse.valid(
                requestId,
                result.outcome().matchedCardholder().map(mapper::toDetails).orElse(null),
                mapper.toValidity(result.outcome().validity()),
                toImageUrls(result.imageReferences()),
                properties.getMedia().getTokenTtl().toSeconds());
    }

    /**
     * An INVALID response says nothing about why, unless the caller was specifically trusted with
     * that. Field-level detail is what turns this endpoint into a tool for confirming guesses about
     * strangers, so it is granted per client rather than published by default.
     */
    private VerifyResponse buildInvalidResponse(String requestId, VerificationFacade.Result result) {
        if (!hasDiagnosticsScope()) {
            return VerifyResponse.invalid(requestId);
        }
        return VerifyResponse.invalid(requestId, mapper.toFailure(result.outcome().failureReasons()));
    }

    private boolean hasDiagnosticsScope() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> DIAGNOSTICS_AUTHORITY.equals(granted.getAuthority()));
    }

    /** Maps document codes to the friendlier keys the API contract exposes. */
    private Map<String, String> toImageUrls(Map<String, String> references) {
        Map<String, String> urls = new LinkedHashMap<>();
        references.forEach((code, reference) -> urls.put(friendlyKey(code), "/api/v1/images/" + reference));
        return urls;
    }

    private String friendlyKey(String docCode) {
        if (DocumentType.PHOTO.equals(docCode)) {
            return "photo";
        }
        if (DocumentType.SIGNATURE.equals(docCode)) {
            return "signature";
        }
        return docCode.toLowerCase(java.util.Locale.ROOT);
    }
}
