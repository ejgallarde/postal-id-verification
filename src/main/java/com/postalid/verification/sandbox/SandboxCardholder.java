package com.postalid.verification.sandbox;

import java.time.LocalDate;

/**
 * One made-up person in the sandbox catalogue, as read from {@code sandbox/cardholders.csv}.
 *
 * <p>Carries only what varies between test scenarios. Everything else a real enrollment packet
 * holds — parents' names, contact numbers, government identifiers — is filled with fixed
 * placeholders by {@link SyntheticPacketWriter}, since the API never returns those fields anyway.
 *
 * @param issuedOn date the card was produced; null for a record that never reached an issued
 *                 status, which is what makes it fail as "not issued"
 * @param scenario what this record exists to exercise, for the tester-facing catalogue
 */
public record SandboxCardholder(
        int sequence,
        String postalRefNo,
        String firstName,
        String middleName,
        String lastName,
        String suffix,
        LocalDate dateOfBirth,
        String gender,
        String status,
        boolean deleted,
        LocalDate enrolledOn,
        LocalDate issuedOn,
        boolean hasSignature,
        String houseBldgLot,
        String barangay,
        String city,
        String province,
        String postalCode,
        String scenario) {

    /**
     * The internal packet key: the same shape as a real one (two digits, a date, a seven-letter
     * station, a four-digit sequence), with a station code no real capture site uses.
     */
    public String recordId() {
        return "99" + "20260101" + "SANDBOX" + String.format("%04d", sequence);
    }
}
