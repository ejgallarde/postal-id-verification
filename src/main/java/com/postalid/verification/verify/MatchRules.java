package com.postalid.verification.verify;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.Cardholder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether submitted details match a stored enrollment record.
 *
 * <p>Surname, first name and date of birth must match exactly on the normalized form. The middle
 * name is treated more loosely because cards and data-capture practice disagree about it: some
 * cards print only an initial, and some holders never supply one. What is tolerated is
 * configurable, so strictness can be tightened without touching this class.
 *
 * <p>Every field is evaluated even once something has already failed, for two reasons.
 * Short-circuiting would let a caller time the response to learn which field they got wrong; and
 * diagnostics are expected to report <em>every</em> mismatch, so a caller correcting a typo is not
 * sent round the loop again for a second one they were never told about.
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
     * @return every field that failed to match, in a stable order; empty when the details match.
     */
    public List<FailureReason> evaluate(VerificationQuery query, Cardholder record) {
        boolean surnameOk = normalizer.matches(query.lastName(), record.getLastNameNorm());
        boolean firstNameOk = normalizer.matches(query.firstName(), record.getFirstNameNorm());
        boolean middleNameOk = middleNameMatches(query.middleName(), record.getMiddleNameNorm());
        boolean dobOk = Objects.equals(query.dateOfBirth(), record.getDateOfBirth());

        List<FailureReason> mismatches = new ArrayList<>(4);
        if (!surnameOk) {
            mismatches.add(FailureReason.SURNAME_MISMATCH);
        }
        if (!firstNameOk) {
            mismatches.add(FailureReason.FIRST_NAME_MISMATCH);
        }
        if (!middleNameOk) {
            mismatches.add(FailureReason.MIDDLE_NAME_MISMATCH);
        }
        if (!dobOk) {
            mismatches.add(FailureReason.DATE_OF_BIRTH_MISMATCH);
        }
        return mismatches;
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
