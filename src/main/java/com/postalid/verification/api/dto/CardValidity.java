package com.postalid.verification.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * When the card was issued and when it lapses.
 *
 * <p><strong>The expiry date is derived, not authoritative.</strong> The enrollment export carries
 * no expiry column, so it is computed as issue date plus a configured validity period. The
 * {@code derived} flag says so in every response, because a consumer treating a computed date as
 * fact could reject a perfectly good card.
 *
 * <p>An expired card still verifies as VALID by default: the identity checked out, which is a
 * different fact from the card being current. Callers must read {@link #status()} and apply their
 * own policy. Deployments preferring the stricter reading can set
 * {@code postalid.verify.treat-expired-as-invalid}.
 */
@Schema(name = "CardValidity", description = "Issue and expiry dates for the card")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardValidity(

        @Schema(description = "Date the card was produced", example = "2024-02-14",
                type = "string", format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate issuedOn,

        @Schema(description = "Derived expiry date; null when it could not be computed",
                example = "2027-02-14", type = "string", format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate expiresOn,

        @Schema(description = "ACTIVE while current, EXPIRED once past expiry, UNKNOWN when no "
                + "issue date could be established",
                example = "ACTIVE", allowableValues = {"ACTIVE", "EXPIRED", "UNKNOWN"})
        String status,

        @Schema(description = "Days until expiry; negative once expired", example = "1078")
        Long daysUntilExpiry,

        @Schema(description = "Always true: expiry is computed from the issue date, not supplied "
                + "by the source data", example = "true")
        Boolean derived) {

    public static final String ACTIVE = "ACTIVE";
    public static final String EXPIRED = "EXPIRED";
    public static final String UNKNOWN = "UNKNOWN";

    // Not serialized: status already carries this, and two ways to express one fact
    // invites clients to branch on the wrong one.
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isExpired() {
        return EXPIRED.equals(status);
    }

    /** No issue date could be established, so nothing can be said about expiry. */
    public static CardValidity unknown() {
        return new CardValidity(null, null, UNKNOWN, null, Boolean.TRUE);
    }
}
