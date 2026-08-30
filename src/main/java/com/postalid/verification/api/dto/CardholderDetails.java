package com.postalid.verification.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * The cardholder details returned on a successful verification.
 *
 * <p>The selection follows one rule: <strong>return only what is printed on the card</strong>. The
 * verifier is holding that card, so echoing its contents lets them spot a tampered or forged one
 * while disclosing nothing they could not already read. Anything beyond it would be a fresh
 * disclosure of someone's personal data to a third party who never had it.
 *
 * <p>Deliberately excluded, though all present in the enrollment record: parents' names, mobile
 * number, email, contact number, TIN, GSIS, PhilHealth, HDMF, CRN, employer and work address,
 * place of birth, physical description, payment records, and every internal processing field.
 * Place of birth and the government identifiers are the sharpest omissions — both are widely used
 * as knowledge-based authentication secrets elsewhere, so handing them to a verifying party would
 * actively enable impersonation.
 */
@Schema(name = "CardholderDetails",
        description = "Cardholder details as printed on the Postal ID card")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CardholderDetails(

        @Schema(description = "Postal reference number, echoed back", example = "A11230000001")
        String postalRefNo,

        @Schema(description = "Given name", example = "JUAN")
        String firstName,

        @Schema(description = "Middle name", example = "SANTOS")
        String middleName,

        @Schema(description = "Surname", example = "DELA CRUZ")
        String lastName,

        @Schema(description = "Name suffix such as JR or III", example = "JR")
        String suffix,

        @Schema(description = "Date of birth", example = "1990-01-31", type = "string", format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate dateOfBirth,

        @Schema(description = "Gender as recorded", example = "F")
        String gender,

        @Schema(description = "Nationality", example = "Filipino")
        String nationality,

        @Schema(description = "Home address as printed on the card")
        CardholderAddress address,

        @Schema(description = "Post office that issued the card", example = "PPC-TANAUAN")
        String issuingPostOffice,

        @Schema(description = "Date the holder enrolled", example = "2024-02-01",
                type = "string", format = "date")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate enrolledOn) {
}
