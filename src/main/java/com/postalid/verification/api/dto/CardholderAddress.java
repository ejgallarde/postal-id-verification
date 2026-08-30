package com.postalid.verification.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The home address as printed on the card.
 *
 * <p>Work address, which the enrollment record also holds, is deliberately absent: it is not on
 * the card, so returning it would disclose something the verifier could not already see.
 */
@Schema(name = "CardholderAddress", description = "Home address as printed on the card")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardholderAddress(

        @Schema(description = "House, building or lot", example = "BLK 7 LOT 2")
        String houseBldgLot,

        @Schema(description = "Street", example = "MABINI ST")
        String street,

        @Schema(description = "Subdivision", example = "GREENVIEW SUBDIVISION")
        String subdivision,

        @Schema(description = "Barangay", example = "BARANGAY SAN ROQUE")
        String barangay,

        @Schema(description = "City or municipality", example = "City of Calamba")
        String city,

        @Schema(description = "Province code", example = "BTG")
        String province,

        @Schema(description = "Postal code", example = "4027")
        String postalCode,

        @Schema(description = "Country code", example = "PHL")
        String country) {
}
