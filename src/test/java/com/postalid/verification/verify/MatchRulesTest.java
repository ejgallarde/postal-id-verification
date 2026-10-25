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
            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOS", DOB), record())).isEmpty();
        }

        @ParameterizedTest(name = "case and padding: \"{0}\" / \"{1}\" / \"{2}\"")
        @CsvSource({
                "maria,   reyes,   santos",
                "'  Maria  ', '  Reyes  ', '  Santos  '",
                "MARIA,   Reyes,   sanTOS"
        })
        void ignoresCaseAndPadding(String first, String middle, String last) {
            assertThat(defaultRules().evaluate(query(first, middle, last, DOB), record())).isEmpty();
        }

        @ParameterizedTest(name = "middle initial \"{0}\" is accepted")
        @CsvSource({"R", "r", "'R.'", "'  R  '"})
        void acceptsMiddleInitialByDefault(String middle) {
            assertThat(defaultRules().evaluate(query("MARIA", middle, "SANTOS", DOB), record())).isEmpty();
        }

        @ParameterizedTest(name = "surname \"{0}\" matches stored \"{1}\"")
        @DisplayName("surnames compare with spaces ignored")
        @CsvSource({
                "DELACRUZ,       DELA CRUZ",
                "DELA CRUZ,      DELACRUZ",
                "'DE LA CRUZ',   DELA CRUZ",
                "LIBUIT ARROYO,  LIBUITARROYO",
                "Libuit-Arroyo,  LIBUITARROYO",
                "DELOS SANTOS,   DE LOS SANTOS"
        })
        void surnameIgnoresSpaces(String submitted, String storedNorm) {
            Cardholder compound = record();
            compound.setLastNameNorm(storedNorm);

            assertThat(defaultRules().evaluate(query("MARIA", "REYES", submitted, DOB), compound)).isEmpty();
        }

        @Test
        @DisplayName("no middle name submitted, none on record")
        void bothMiddleNamesAbsent() {
            Cardholder noMiddle = record();
            noMiddle.setMiddleNameNorm(null);

            assertThat(defaultRules().evaluate(query("MARIA", null, "SANTOS", DOB), noMiddle)).isEmpty();
            assertThat(defaultRules().evaluate(query("MARIA", "  ", "SANTOS", DOB), noMiddle)).isEmpty();
        }
    }

    @Nested
    @DisplayName("rejected submissions")
    class Rejected {

        @Test
        void wrongSurname() {
            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOZ", DOB), record())).containsExactly(FailureReason.SURNAME_MISMATCH);
        }

        @Test
        @DisplayName("ignoring spaces does not loosen the letters: DELA CRUZ is not DELO CRUZ")
        void spacesIgnoredButLettersStillCount() {
            Cardholder compound = record();
            compound.setLastNameNorm("DELA CRUZ");

            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "DELO CRUZ", DOB), compound))
                    .containsExactly(FailureReason.SURNAME_MISMATCH);
        }

        @Test
        @DisplayName("first names keep their spaces: EARLJOHN is not EARL JOHN")
        void firstNameSpacesStillMatter() {
            Cardholder twoFirstNames = record();
            twoFirstNames.setFirstNameNorm("EARL JOHN");

            assertThat(defaultRules().evaluate(query("EARLJOHN", "REYES", "SANTOS", DOB), twoFirstNames))
                    .containsExactly(FailureReason.FIRST_NAME_MISMATCH);
        }

        @Test
        void wrongFirstName() {
            assertThat(defaultRules().evaluate(query("MARIE", "REYES", "SANTOS", DOB), record())).containsExactly(FailureReason.FIRST_NAME_MISMATCH);
        }

        @Test
        void wrongMiddleName() {
            assertThat(defaultRules().evaluate(query("MARIA", "CRUZ", "SANTOS", DOB), record())).containsExactly(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        @DisplayName("a middle initial that is not the stored initial")
        void wrongMiddleInitial() {
            assertThat(defaultRules().evaluate(query("MARIA", "C", "SANTOS", DOB), record())).containsExactly(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        @DisplayName("omitting a middle name the record has is rejected by default")
        void omittedMiddleNameRejectedByDefault() {
            assertThat(defaultRules().evaluate(query("MARIA", null, "SANTOS", DOB), record())).containsExactly(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        @DisplayName("claiming a middle name the record does not have is rejected")
        void middleNameClaimedButNoneOnRecord() {
            Cardholder noMiddle = record();
            noMiddle.setMiddleNameNorm(null);

            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOS", DOB), noMiddle)).containsExactly(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        void wrongDateOfBirth() {
            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOS", LocalDate.of(1988, 3, 18)), record())).containsExactly(FailureReason.DATE_OF_BIRTH_MISMATCH);
        }

        @Test
        @DisplayName("a day and month typed the wrong way round is rejected, not tolerated")
        void transposedDateOfBirth() {
            Cardholder marchFifth = record();
            marchFifth.setDateOfBirth(LocalDate.of(1988, 3, 5));

            LocalDate mayThird = LocalDate.of(1988, 5, 3);

            assertThat(defaultRules().evaluate(query("MARIA", "REYES", "SANTOS", mayThird), marchFifth)).containsExactly(FailureReason.DATE_OF_BIRTH_MISMATCH);
        }
    }

    @Nested
    @DisplayName("multiple mismatches")
    class MultipleMismatches {

        @Test
        @DisplayName("every failing field is reported, not just the first")
        void reportsAllMismatches() {
            assertThat(defaultRules().evaluate(
                    query("WRONG", "REYES", "ALSOWRONG", LocalDate.of(1999, 1, 1)), record()))
                    .containsExactly(
                            FailureReason.SURNAME_MISMATCH,
                            FailureReason.FIRST_NAME_MISMATCH,
                            FailureReason.DATE_OF_BIRTH_MISMATCH);
        }

        @Test
        @DisplayName("mismatch reasons map onto the caller's own request field names")
        void reasonsCarryRequestFieldNames() {
            assertThat(FailureReason.SURNAME_MISMATCH.requestField()).isEqualTo("lastName");
            assertThat(FailureReason.FIRST_NAME_MISMATCH.requestField()).isEqualTo("firstName");
            assertThat(FailureReason.MIDDLE_NAME_MISMATCH.requestField()).isEqualTo("middleName");
            assertThat(FailureReason.DATE_OF_BIRTH_MISMATCH.requestField()).isEqualTo("dateOfBirth");

            // Reasons about the record as a whole name no field.
            assertThat(FailureReason.NO_SUCH_RECORD.isFieldMismatch()).isFalse();
            assertThat(FailureReason.CARD_NOT_ISSUED.isFieldMismatch()).isFalse();
            assertThat(FailureReason.CARD_EXPIRED.isFieldMismatch()).isFalse();
        }
    }

    @Nested
    @DisplayName("configurable tolerances")
    class Tolerances {

        @Test
        void middleInitialCanBeDisallowed() {
            PostalIdProperties properties = new PostalIdProperties();
            properties.getVerify().setAllowMiddleInitial(false);

            assertThat(rulesWith(properties).evaluate(query("MARIA", "R", "SANTOS", DOB), record())).containsExactly(FailureReason.MIDDLE_NAME_MISMATCH);
        }

        @Test
        void middleNameOmissionCanBeAllowed() {
            PostalIdProperties properties = new PostalIdProperties();
            properties.getVerify().setAllowMiddleNameOmission(true);

            assertThat(rulesWith(properties).evaluate(query("MARIA", null, "SANTOS", DOB), record())).isEmpty();
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
