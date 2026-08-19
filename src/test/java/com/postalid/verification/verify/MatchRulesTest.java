package com.postalid.verification.verify;

import com.postalid.verification.config.PostalIdProperties;
import com.postalid.verification.domain.Cardholder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The truth table for what counts as a match.
 *
 * <p>These cases are the contract: they say exactly which submissions are accepted and which are
 * turned away, including the middle-name tolerances that exist because cards and capture practice
 * disagree about middle names.
 */
class MatchRulesTest {

    private static final LocalDate DOB = LocalDate.of(1988, 3, 17);

    private final FieldNormalizer normalizer = new FieldNormalizer();

    private MatchRules rulesWith(PostalIdProperties properties) {
        return new MatchRules(normalizer, properties);
    }

    private MatchRules defaultRules() {
        return rulesWith(new PostalIdProperties());
    }

    private static Cardholder record() {
        Cardholder c = new Cardholder();
        c.setRecordId("9920260101TESTSTA0001");
        c.setPostalRefNo("T99260320001");
        c.setFirstName("MARIA");
        c.setMiddleName("REYES");
        c.setLastName("SANTOS");
        c.setFirstNameNorm("MARIA");
        c.setMiddleNameNorm("REYES");
        c.setLastNameNorm("SANTOS");
        c.setDateOfBirth(DOB);
        c.setDemoStatus("CDE");
        c.setDeleted(false);
        return c;
    }

    private static VerificationQuery query(String first, String middle, String last, LocalDate dob) {
        return new VerificationQuery("T99260320001", first, middle, last, dob);
    }

    @Nested
    @DisplayName("accepted submissions")
    class Accepted {

        @Test
        void exactMatch() {
            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOS", DOB), record())).isNull();
        }

        @ParameterizedTest(name = "case and padding: \"{0}\" / \"{1}\" / \"{2}\"")
        @CsvSource({
                "maria,   reyes,   santos",
                "'  Maria  ', '  Reyes  ', '  Santos  '",
                "MARIA,   Reyes,   sanTOS"
        })
        void ignoresCaseAndPadding(String first, String middle, String last) {
            assertThat(defaultRules().evaluate(query(first, middle, last, DOB), record())).isNull();
        }

        @ParameterizedTest(name = "middle initial \"{0}\" is accepted")
        @CsvSource({"R", "r", "'R.'", "'  R  '"})
        void acceptsMiddleInitialByDefault(String middle) {
            assertThat(defaultRules().evaluate(query("MARIA", middle, "SANTOS", DOB), record())).isNull();
        }

        @Test
        @DisplayName("no middle name submitted, none on record")
        void bothMiddleNamesAbsent() {
            Cardholder noMiddle = record();
            noMiddle.setMiddleNameNorm(null);

            assertThat(defaultRules().evaluate(query("MARIA", null, "SANTOS", DOB), noMiddle)).isNull();
            assertThat(defaultRules().evaluate(query("MARIA", "  ", "SANTOS", DOB), noMiddle)).isNull();
        }
    }

    @Nested
    @DisplayName("rejected submissions")
    class Rejected {

        @Test
        void wrongSurname() {
            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOZ", DOB), record()))
                    .isEqualTo(FailureReason.SURNAME_MISMATCH);
        }

        @Test
        void wrongFirstName() {
            assertThat(defaultRules().evaluate(query("MARIE", "REYES", "SANTOS", DOB), record()))
                    .isEqualTo(FailureReason.FIRST_NAME_MISMATCH);
        }

        @Test
        void wrongMiddleName() {
            assertThat(defaultRules().evaluate(query("MARIA", "CRUZ", "SANTOS", DOB), record()))
                    .isEqualTo(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        @DisplayName("a middle initial that is not the stored initial")
        void wrongMiddleInitial() {
            assertThat(defaultRules().evaluate(query("MARIA", "C", "SANTOS", DOB), record()))
                    .isEqualTo(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        @DisplayName("omitting a middle name the record has is rejected by default")
        void omittedMiddleNameRejectedByDefault() {
            assertThat(defaultRules().evaluate(query("MARIA", null, "SANTOS", DOB), record()))
                    .isEqualTo(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        @DisplayName("claiming a middle name the record does not have is rejected")
        void middleNameClaimedButNoneOnRecord() {
            Cardholder noMiddle = record();
            noMiddle.setMiddleNameNorm(null);

            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOS", DOB), noMiddle))
                    .isEqualTo(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        void wrongDateOfBirth() {
            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOS", LocalDate.of(1988, 3, 18)), record()))
                    .isEqualTo(FailureReason.DATE_OF_BIRTH_MISMATCH);
        }

        @Test
        @DisplayName("a day and month typed the wrong way round is rejected, not tolerated")
        void transposedDateOfBirth() {
            Cardholder marchFifth = record();
            marchFifth.setDateOfBirth(LocalDate.of(1988, 3, 5));

            LocalDate mayThird = LocalDate.of(1988, 5, 3);

            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOS", mayThird), marchFifth))
                    .isEqualTo(FailureReason.DATE_OF_BIRTH_MISMATCH);
        }
    }

    @Nested
    @DisplayName("configurable tolerances")
    class Tolerances {

        @Test
        void middleInitialCanBeDisallowed() {
            PostalIdProperties properties = new PostalIdProperties();
            properties.getVerify().setAllowMiddleInitial(false);

            assertThat(rulesWith(properties).evaluate(query("MARIA", "R", "SANTOS", DOB), record()))
                    .isEqualTo(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        void middleNameOmissionCanBeAllowed() {
            PostalIdProperties properties = new PostalIdProperties();
            properties.getVerify().setAllowMiddleNameOmission(true);

            assertThat(rulesWith(properties).evaluate(query("MARIA", null, "SANTOS", DOB), record())).isNull();
        }
    }

    @Nested
    @DisplayName("record eligibility, checked before any field comparison")
    class Eligibility {

        @Test
        void issuedRecordIsEligible() {
            assertThat(defaultRules().evaluateEligibility(record())).isNull();
        }

        @Test
        void deletedRecordIsNot() {
            Cardholder deleted = record();
            deleted.setDeleted(true);

            assertThat(defaultRules().evaluateEligibility(deleted)).isEqualTo(FailureReason.RECORD_DELETED);
        }

        @ParameterizedTest(name = "status {0} does not count as issued")
        @CsvSource({"REC", "AQC", "FAF", "STA", "QOK", "FCD"})
        void nonIssuedStatusIsNot(String status) {
            Cardholder pending = record();
            pending.setDemoStatus(status);

            assertThat(defaultRules().evaluateEligibility(pending)).isEqualTo(FailureReason.CARD_NOT_ISSUED);
        }

        @Test
        void missingStatusIsNot() {
            Cardholder unknown = record();
            unknown.setDemoStatus(null);

            assertThat(defaultRules().evaluateEligibility(unknown)).isEqualTo(FailureReason.CARD_NOT_ISSUED);
        }

        @Test
        void theIssuedStatusSetIsConfigurable() {
            PostalIdProperties properties = new PostalIdProperties();
            properties.getVerify().setIssuedStatuses(Set.of("CDE", "FCD"));

            Cardholder fcd = record();
            fcd.setDemoStatus("FCD");

            assertThat(rulesWith(properties).evaluateEligibility(fcd)).isNull();
        }
    }
}
