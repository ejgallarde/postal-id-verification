package com.postalid.verification.api;

import com.postalid.verification.api.dto.CardValidity;
import com.postalid.verification.api.dto.CardholderAddress;
import com.postalid.verification.api.dto.CardholderDetails;
import com.postalid.verification.api.dto.VerificationFailure;
import com.postalid.verification.domain.Cardholder;
import com.postalid.verification.verify.CardValidityWindow;
import com.postalid.verification.verify.FailureReason;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Turns internal records into the shapes the API returns.
 *
 * <p>This class is the boundary where the decision about what a verifier may learn is actually
 * enforced. {@link #toDetails} is an explicit field-by-field copy rather than a reflective or
 * wholesale mapping, precisely so that adding a column to {@code cardholder} can never silently
 * start publishing it. If a field is not written out here, it is not disclosed.
 */
@Component
public class CardResponseMapper {

    /**
     * The cardholder details a verifier receives: what is printed on the card, and nothing else.
     *
     * <p>Not copied, though all present on the record: parents' names, mobile number, email,
     * contact number, TIN, CRN, GSIS, PhilHealth, HDMF, employer, occupation, work address, place
     * of birth, physical description, payment records, and every internal processing field.
     */
    public CardholderDetails toDetails(Cardholder c) {
        return new CardholderDetails(
                c.getPostalRefNo(),
                c.getFirstName(),
                c.getMiddleName(),
                c.getLastName(),
                c.getSuffixName(),
                c.getDateOfBirth(),
                c.getGender(),
                c.getNationality(),
                toAddress(c),
                c.getIssuingPostOffice(),
                c.getEnrollmentDate());
    }

    private CardholderAddress toAddress(Cardholder c) {
        return new CardholderAddress(
                c.getAddrHouseBldgLot(),
                c.getAddrStreet(),
                c.getAddrSubdivision(),
                c.getAddrBarangay(),
                c.getAddrCity(),
                c.getAddrProvince(),
                c.getAddrPostalCode(),
                c.getAddrCountry());
    }

    public CardValidity toValidity(CardValidityWindow window) {
        if (window == null || !window.known()) {
            return CardValidity.unknown();
        }

        LocalDate expiresOn = window.expiresOn();
        if (expiresOn == null) {
            return new CardValidity(window.issuedOn(), null, CardValidity.UNKNOWN, null, Boolean.TRUE);
        }

        long days = ChronoUnit.DAYS.between(LocalDate.now(), expiresOn);
        String status = window.expired() ? CardValidity.EXPIRED : CardValidity.ACTIVE;

        return new CardValidity(window.issuedOn(), expiresOn, status, days, Boolean.TRUE);
    }

    /**
     * The diagnostics payload. Only ever built for a caller holding
     * {@code postalid.diagnostics}; the calling controller enforces that.
     */
    public VerificationFailure toFailure(List<FailureReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }

        List<String> fields = reasons.stream()
                .filter(FailureReason::isFieldMismatch)
                .map(FailureReason::requestField)
                .toList();

        if (!fields.isEmpty()) {
            return new VerificationFailure(
                    VerificationFailure.FIELD_MISMATCH,
                    fields,
                    fields.size() + " field(s) did not match the stored record");
        }

        FailureReason reason = reasons.get(0);
        return new VerificationFailure(reason.name(), null, describe(reason));
    }

    private String describe(FailureReason reason) {
        return switch (reason) {
            case NO_SUCH_RECORD -> "No record carries that postal reference number";
            case RECORD_DELETED -> "The record exists but has been deleted";
            case CARD_NOT_ISSUED -> "The record exists but no card was ever issued";
            case CARD_EXPIRED -> "The card has passed its expiry date";
            default -> "Verification failed";
        };
    }
}
