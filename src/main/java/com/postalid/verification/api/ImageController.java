package com.postalid.verification.api;

import com.postalid.verification.media.ImageDeliveryService;
import com.postalid.verification.media.WebImage;
import com.postalid.verification.web.RateLimiter;
import com.postalid.verification.web.RequestContextSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Streams a verified cardholder's photograph or signature.
 *
 * <p>There is no way to name a cardholder or a document here. The only input is an opaque
 * reference minted by a successful verification, which is what keeps the endpoint from being
 * enumerable: guessing a working reference means guessing 256 bits.
 *
 * <p>Every unusable reference — unknown, expired, spent, revoked, or pointing at a document type
 * that is not servable — produces the same 404. Distinguishing them would confirm which
 * references had once been real.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Images", description = "Retrieve cardholder photo and signature for a verified record")
public class ImageController {

    private final ImageDeliveryService imageDeliveryService;
    private final RateLimiter rateLimiter;

    public ImageController(ImageDeliveryService imageDeliveryService, RateLimiter rateLimiter) {
        this.imageDeliveryService = imageDeliveryService;
        this.rateLimiter = rateLimiter;
    }

    @Operation(
            summary = "Fetch a cardholder image",
            description = """
                    Streams the photograph or signature behind a reference returned by a VALID
                    verification.

                    References expire a few minutes after they are issued and allow only a small
                    number of fetches. Signatures and scans are held as TIFF, which browsers cannot
                    display, so they are converted to PNG on the way out; the JPEG photograph is
                    served as captured. Read the Content-Type header rather than assuming a format.

                    Only photographs and signatures can ever be referenced. Fingerprints and
                    supporting document scans are stored but not reachable through this API.
                    """,
            security = @SecurityRequirement(name = "oauth2-client-credentials", scopes = "postalid.images"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Image bytes, as image/png or image/jpeg"),
            @ApiResponse(responseCode = "401", description = "Missing, expired or invalid access token",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "403", description = "Token lacks the postalid.images scope",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "404", description = "Reference is unknown, expired or exhausted",
                    content = @io.swagger.v3.oas.annotations.media.Content()),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded",
                    content = @io.swagger.v3.oas.annotations.media.Content())
    })
    @GetMapping("/images/{reference}")
    @PreAuthorize("hasAuthority('SCOPE_postalid.images')")
    public ResponseEntity<byte[]> fetch(
            @Parameter(description = "Opaque reference from a VALID verification response")
            @PathVariable String reference) {

        RateLimiter.Decision decision =
                rateLimiter.checkImageByClient(RequestContextSupport.currentClientId());
        if (!decision.allowed()) {
            throw new RateLimitExceededException(decision.retryAfterSeconds());
        }

        WebImage image = imageDeliveryService.deliver(reference)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Image reference is not valid"));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                // A photograph of an identified person must not be cached by anything in between.
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(image.data());
    }
}
