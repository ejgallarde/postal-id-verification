package com.postalid.verification.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * The verification answer.
 *
 * <p>A VALID result carries the cardholder details printed on the card, the card's validity
 * window, and short-lived references to the photograph and signature.
 *
 * <p>An INVALID result is bare by default: the same body comes back whether the reference number
 * is unknown, the card was never issued, or a name was misspelt. Saying which would let a caller
 * reconstruct a stranger's details one field at a time. Clients granted the
 * {@code postalid.diagnostics} scope additionally receive {@link VerificationFailure}, which is a
 * deliberate, per-client grant rather than the default.
 */
@Schema(name = "VerifyResponse", description = "Outcome of a Postal ID verification")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifyResponse(

        @Schema(description = "VALID when the details match an issued Postal ID, INVALID otherwise",
                example = "VALID", allowableValues = {"VALID", "INVALID"})
        String result,

        @Schema(description = "When the check ran", example = "2026-08-21T04:21:07Z")
        Instant verifiedAt,

        @Schema(description = "Correlation id, also written to the audit trail; quote it in support requests",
                example = "0f4b7c2e-6d1a-4f83-9c55-2a1f9e0b77d1")
        String requestId,

        @Schema(description = "Present only on VALID. Cardholder details as printed on the card.")
        CardholderDetails cardholder,

        @Schema(description = "Present only on VALID. Issue and derived expiry dates. Read `status` "
                + "before treating the card as current.")
        CardValidity validity,

        @Schema(description = "Present only on VALID. Relative URLs for the cardholder's photo and "
                + "signature, each usable for a short window.",
                example = "{\"photo\":\"/api/v1/images/xY3...\",\"signature\":\"/api/v1/images/8Kq...\"}")
        Map<String, String> images,

        @Schema(description = "Present only on VALID. Seconds until the image URLs stop working.",
                example = "300")
        Long imagesExpireIn,

        @Schema(description = "Present only on VALID, and only when the request asked for "
                + "include=images. The photo and signature embedded as base64, keyed like `images`. "
                + "When present, `images` and `imagesExpireIn` are absent: nothing is left to fetch.")
        Map<String, InlineImage> imageData,

        @Schema(description = "Present only on INVALID, and only for clients holding the "
                + "postalid.diagnostics scope.")
        VerificationFailure reason) {

    public static VerifyResponse valid(String requestId,
                                       CardholderDetails cardholder,
                                       CardValidity validity,
                                       Map<String, String> images,
                                       long imagesExpireInSeconds) {
        return new VerifyResponse("VALID", Instant.now(), requestId,
                cardholder, validity, images, imagesExpireInSeconds, null, null);
    }

    /** VALID with the images embedded, for a caller that asked for {@code include=images}. */
    public static VerifyResponse validWithImageData(String requestId,
                                                    CardholderDetails cardholder,
                                                    CardValidity validity,
                                                    Map<String, InlineImage> imageData) {
        return new VerifyResponse("VALID", Instant.now(), requestId,
                cardholder, validity, null, null, imageData, null);
    }

    /** The default INVALID: nothing beyond the verdict. */
    public static VerifyResponse invalid(String requestId) {
        return new VerifyResponse("INVALID", Instant.now(), requestId, null, null, null, null, null, null);
    }

    /** INVALID for a client trusted with diagnostics. */
    public static VerifyResponse invalid(String requestId, VerificationFailure reason) {
        return new VerifyResponse("INVALID", Instant.now(), requestId, null, null, null, null, null, reason);
    }
}
