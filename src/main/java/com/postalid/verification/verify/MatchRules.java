package com.postalid.verification.verify;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.Cardholder;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Decides whether submitted details match a stored enrollment record.
 *
 * <p>Surname, first name and date of birth must match exactly on the normalized form. The middle
 * name is treated more loosely because cards and data-capture practice disagree about it: some
 * cards print only an initial, and some holders never supply one. What is tolerated is
 * configurable, so the strictness can be tightened without touching this class.
 *
 * <p>Every field is evaluated even once something has already failed. Short-circuiting would let
 * a caller time the response to learn which field they got wrong.
 */
@Component
public class MatchRules {

    private final FieldNormalizer normalizer;
    private final PostalIdProperties.Verify config;

    public MatchRules(FieldNormalizer normalizer, PostalIdProperties properties) {
        this.normalizer = normalizer;
        this.config = properties.getVerify();
    }

    /**
     * @return the reason the submitted details do not match, or {@code null} when they do.
     */
    public FailureReason evaluate(VerificationQuery query, Cardholder record) {
        boolean surnameOk = normalizer.matches(query.lastName(), record.getLastNameNorm());
        boolean firstNameOk = normalizer.matches(query.firstName(), record.getFirstNameNorm());
        boolean middleNameOk = middleNameMatches(query.middleName(), record.getMiddleNameNorm());
        boolean dobOk = Objects.equals(query.dateOfBirth(), record.getDateOfBirth());

        // Fixed reporting order; all four were computed regardless.
        if (!surnameOk) {
            return FailureReason.SURNAME_MISMATCH;
        }
        if (!firstNameOk) {
            return FailureReason.FIRST_NAME_MISMATCH;
        }
        if (!middleNameOk) {
            return FailureReason.MIDDLE_NAME_MISMATCH;
        }
        if (!dobOk) {
            return FailureReason.DATE_OF_BIRTH_MISMATCH;
        }
        return null;
    }

    /**
     * Middle name comparison.
     *
     * <ul>
     *   <li>Both absent: matches.</li>
     *   <li>Stored absent, one supplied: does not match — the submitter claims something the
     *       record does not support.</li>
     *   <li>Full name equal after normalization: matches.</li>
     *   <li>A single submitted letter equal to the stored initial: matches when
     *       {@code allow-middle-initial} is on, which is the default because many cards print
     *       only the initial.</li>
     *   <li>Nothing submitted against a stored middle name: matches only when
     *       {@code allow-middle-name-omission} is on, which is off by default.</li>
     * </ul>
     */
    private boolean middleNameMatches(String submitted, String storedNorm) {
        String submittedNorm = normalizer.normalizeToEmpty(submitted);
        String stored = normalizer.normalizeToEmpty(storedNorm);

        if (stored.isEmpty()) {
            return submittedNorm.isEmpty();
        }
        if (submittedNorm.isEmpty()) {
            return config.isAllowMiddleNameOmission();
        }
        if (FieldNormalizer.constantTimeEquals(submittedNorm, stored)) {
            return true;
        }
        return config.isAllowMiddleInitial()
                && submittedNorm.length() == 1
                && submittedNorm.charAt(0) == stored.charAt(0);
    }

    /** Whether the record's state permits it to verify at all, independent of the typed details. */
    public FailureReason evaluateEligibility(Cardholder record) {
        if (record.isDeleted()) {
            return FailureReason.RECORD_DELETED;
        }
        String status = record.getDemoStatus() == null ? "" : record.getDemoStatus().trim().toUpperCase();
        if (!config.getIssuedStatuses().contains(status)) {
            return FailureReason.CARD_NOT_ISSUED;
        }
        return null;
    }
}
