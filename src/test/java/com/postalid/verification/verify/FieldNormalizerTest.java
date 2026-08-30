package com.postalid.verification.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class FieldNormalizerTest {

    private final FieldNormalizer normalizer = new FieldNormalizer();

    @ParameterizedTest(name = "\"{0}\" normalizes to \"{1}\"")
    @CsvSource({
            // The source CSV pads every field to a fixed width.
            "'DELA CRUZ                    ', DELA CRUZ",
            "'  Dela Cruz  ',               DELA CRUZ",
            "'de la Cruz',                  DE LA CRUZ",

            // Accents decompose and their marks are dropped, so an operator without the right
            // keyboard layout can still match a stored value.
            "'Muñoz',                       MUNOZ",
            "'José',                        JOSE",
            "'Ångström',                    ANGSTROM",

            // Punctuation that varies freely in how names are written down.
            "'O''Brien',                    OBRIEN",
            "'Smith-Jones',                 SMITHJONES",
            "'Jr.',                         JR",

            // Internal whitespace collapses.
            "'DELA    CRUZ',                DELA CRUZ",
            "'A  B   C',                    A B C"
    })
    void normalizesToComparisonForm(String input, String expected) {
        assertThat(normalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t", "        "})
    @DisplayName("null and padding-only values both collapse to null")
    void treatsBlankAsAbsent(String input) {
        assertThat(normalizer.normalize(input)).isNull();
        assertThat(normalizer.normalizeToEmpty(input)).isEmpty();
    }

    @Test
    void matchesIgnoresPaddingCaseAndAccents() {
        assertThat(normalizer.matches("  josé  ", "JOSE")).isTrue();
        assertThat(normalizer.matches("O'Brien", "OBRIEN")).isTrue();
        assertThat(normalizer.matches(null, "   ")).isTrue();
    }

    @Test
    void matchesRejectsDifferentValues() {
        assertThat(normalizer.matches("DELA CRUZ", "DELA CRUS")).isFalse();
        assertThat(normalizer.matches("SANTOS", "")).isFalse();
        assertThat(normalizer.matches(null, "SANTOS")).isFalse();
    }

    @Test
    @DisplayName("punctuation-only input is treated as absent rather than as a value")
    void punctuationOnlyCollapsesToAbsent() {
        assertThat(normalizer.normalize(".")).isNull();
        assertThat(normalizer.normalize("-")).isNull();
    }

    @Test
    void constantTimeEqualsBehavesLikeEquals() {
        assertThat(FieldNormalizer.constantTimeEquals("ABC", "ABC")).isTrue();
        assertThat(FieldNormalizer.constantTimeEquals("ABC", "ABD")).isFalse();
        assertThat(FieldNormalizer.constantTimeEquals("ABC", "ABCD")).isFalse();
        assertThat(FieldNormalizer.constantTimeEquals(null, null)).isTrue();
        assertThat(FieldNormalizer.constantTimeEquals(null, "ABC")).isFalse();
    }
}
