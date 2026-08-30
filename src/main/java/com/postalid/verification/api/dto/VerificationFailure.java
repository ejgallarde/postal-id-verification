package com.postalid.verification.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Why a verification came back INVALID.
 *
 * <p>Present only for callers holding the {@code postalid.diagnostics} scope. Without it, an
 * INVALID response carries no reason at all, and every failing case looks identical.
 *
 * <p>That gate exists because this object is precisely what makes the endpoint useful for
 * harvesting: told which field was wrong, a caller can confirm a guess one field at a time, and
 * {@code NO_SUCH_RECORD} reveals whether any given reference number exists. Granting the scope is
 * a decision to trust a specific client with that capability, not a default.
 */
@Schema(name = "VerificationFailure",
        description = "Reason for an INVALID result. Requires the postalid.diagnostics scope.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VerificationFailure(

        @Schema(description = "Machine-readable failure code", example = "FIELD_MISMATCH",
                allowableValues = {"NO_SUCH_RECORD", "RECORD_DELETED", "CARD_NOT_ISSUED",
                                   "CARD_EXPIRED", "FIELD_MISMATCH"})
        String code,

        @Schema(description = "Request fields that did not match the stored record. Present only "
                + "for FIELD_MISMATCH, and listing every field that failed, not just the first.",
                example = "[\"lastName\", \"dateOfBirth\"]")
        List<String> mismatchedFields,

        @Schema(description = "Human-readable summary for logs and support",
                example = "2 field(s) did not match the stored record")
        String message) {

    public static final String NO_SUCH_RECORD = "NO_SUCH_RECORD";
    public static final String RECORD_DELETED = "RECORD_DELETED";
    public static final String CARD_NOT_ISSUED = "CARD_NOT_ISSUED";
    public static final String CARD_EXPIRED = "CARD_EXPIRED";
    public static final String FIELD_MISMATCH = "FIELD_MISMATCH";
}
