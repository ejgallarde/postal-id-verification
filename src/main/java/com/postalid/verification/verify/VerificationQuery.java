package com.postalid.verification.verify;

import java.time.LocalDate;

/** The five values a user reads off a Postal ID card, as submitted for verification. */
public record VerificationQuery(
        String postalRefNo,
        String firstName,
        String middleName,
        String lastName,
        LocalDate dateOfBirth) {
}
