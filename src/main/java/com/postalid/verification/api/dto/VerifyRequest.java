package com.postalid.verification.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * The details read off a Postal ID card.
 *
 * <p>Input is validated against an allow-list rather than sanitized. A name containing something
 * the pattern does not permit is rejected outright; nothing is silently stripped and then matched,
 * because that would mean verifying something the user did not actually type.
 */
@Schema(name = "VerifyRequest", description = "Details transcribed from a Postal ID card")
public record VerifyRequest(

        @Schema(description = "Postal reference number printed on the card", example = "A11230000001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Postal reference number is required")
        @Pattern(regexp = "^[A-Za-z0-9]{8,20}$",
                message = "Postal reference number must be 8 to 20 letters or digits")
        String postalRefNo,

        @Schema(description = "Given name as printed on the card", example = "JUAN",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "First name is required")
        @Size(max = 120, message = "First name is too long")
        @Pattern(regexp = NAME_PATTERN, message = "First name contains unsupported characters")
        String firstName,

        @Schema(description = "Middle name or initial; omit if the card shows none", example = "SANTOS")
        @Size(max = 120, message = "Middle name is too long")
        @Pattern(regexp = OPTIONAL_NAME_PATTERN, message = "Middle name contains unsupported characters")
        String middleName,

        @Schema(description = "Surname as printed on the card", example = "DELA CRUZ",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Surname is required")
        @Size(max = 120, message = "Surname is too long")
        @Pattern(regexp = NAME_PATTERN, message = "Surname contains unsupported characters")
        String lastName,

        @Schema(description = "Date of birth in ISO-8601 form", example = "1990-01-31",
                requiredMode = Schema.RequiredMode.REQUIRED, type = "string", format = "date")
        @NotNull(message = "Date of birth is required")
        @Past(message = "Date of birth must be in the past")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        LocalDate dateOfBirth) {

    /**
     * Letters from any script, plus the spaces, hyphens, apostrophes and full stops that appear
     * legitimately in names. Anything else — digits, angle brackets, control characters — is out.
     */
    public static final String NAME_PATTERN = "^[\\p{L}\\p{M}][\\p{L}\\p{M} .'\\-]*$";

    /** As above, but an empty value is allowed, since a middle name is optional. */
    public static final String OPTIONAL_NAME_PATTERN = "^$|" + NAME_PATTERN;
}
