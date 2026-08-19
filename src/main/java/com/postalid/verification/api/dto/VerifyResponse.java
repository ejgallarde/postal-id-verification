package com.postalid.verification.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * The verification answer.
 *
 * <p>An INVALID response is deliberately bare: the same body comes back whether the reference
 * number is unknown, the card was never issued, or the name was misspelt. Saying which would let
 * a caller reconstruct a stranger's details one field at a time. The reason is recorded in the
 * audit trail for operators, not returned to the caller.
 */
@Schema(name = "VerifyResponse", description = "Outcome of a Postal ID verification")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerifyResponse(

        @Schema(description = "VALID when the details match an issued Postal ID, INVALID otherwise",
                example = "VALID", allowableValues = {"VALID", "INVALID"})
        String result,

        @Schema(description = "When the check ran", example = "2026-08-18T04:21:07Z")
        Instant verifiedAt,

        @Schema(description = "Correlation id, also written to the audit trail; quote it in support requests",
                example = "0f4b7c2e-6d1a-4f83-9c55-2a1f9e0b77d1")
        String requestId,

        @Schema(description = "Present only on VALID. Relative URLs for the cardholder's photo and "
                + "signature, each usable for a short window.",
                example = "{\"photo\":\"/api/v1/images/xY3...\",\"signature\":\"/api/v1/images/8Kq...\"}")
        Map<String, String> images,

        @Schema(description = "Present only on VALID. Seconds until the image URLs stop working.",
                example = "300")
        Long imagesExpireIn) {

    public static VerifyResponse valid(String requestId, Map<String, String> images, long expiresInSeconds) {
        return new VerifyResponse("VALID", Instant.now(), requestId, images, expiresInSeconds);
    }

    public static VerifyResponse invalid(String requestId) {
        return new VerifyResponse("INVALID", Instant.now(), requestId, null, null);
    }
}
