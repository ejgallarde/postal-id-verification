package com.postalid.verification.verify;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single definition of "the same value" used across the whole application.
 *
 * <p>Both the ingester (writing the {@code *_norm} columns) and the verifier (normalizing user
 * input) call this. Keeping one implementation is what stops stored and submitted values from
 * being normalized differently, which would show up as unexplainable INVALID results.
 *
 * <p>The pipeline is: strip the source CSV's fixed-width padding, uppercase in a fixed locale,
 * decompose accents and drop the combining marks, remove the punctuation that varies freely in
 * hand-entered names, then collapse runs of whitespace.
 */
@Component
public class FieldNormalizer {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern DROPPED_PUNCTUATION = Pattern.compile("[.\\-'’`,]");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    /**
     * @return the comparison form, or {@code null} if the input is null or holds nothing but
     *         padding. Null and blank collapse to the same thing on purpose: the source CSV
     *         writes "absent" as a run of spaces.
     */
    public String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }

        value = value.toUpperCase(Locale.ROOT);
        value = Normalizer.normalize(value, Normalizer.Form.NFD);
        value = COMBINING_MARKS.matcher(value).replaceAll("");
        value = DROPPED_PUNCTUATION.matcher(value).replaceAll("");
        value = WHITESPACE_RUN.matcher(value).replaceAll(" ").trim();

        return value.isEmpty() ? null : value;
    }

    /** Normalizes, mapping "nothing there" to an empty string instead of null. */
    public String normalizeToEmpty(String raw) {
        String normalized = normalize(raw);
        return normalized == null ? "" : normalized;
    }

    /**
     * Equality on the comparison form, treating null and blank as equal.
     *
     * <p>Uses a length-independent comparison so that a caller cannot learn how much of a value
     * they got right by timing the response.
     */
    public boolean matches(String a, String b) {
        return constantTimeEquals(normalizeToEmpty(a), normalizeToEmpty(b));
    }

    /**
     * Equality on the comparison form with every space removed as well.
     *
     * <p>For surnames, where the same name is written with and without its spaces: DELA CRUZ and
     * DELACRUZ, DE LOS SANTOS and DELOS SANTOS, and a hyphenated LIBUIT-ARROYO typed as
     * LIBUIT ARROYO. Hyphens are already removed by {@link #normalize}, so without this a space
     * typed where the hyphen belongs would never match.
     */
    public boolean matchesIgnoringSpaces(String a, String b) {
        return constantTimeEquals(withoutSpaces(normalizeToEmpty(a)), withoutSpaces(normalizeToEmpty(b)));
    }

    private static String withoutSpaces(String normalized) {
        return normalized.replace(" ", "");
    }

    /**
     * Compares two strings without returning early on the first differing character.
     *
     * <p>Length still leaks, which is unavoidable and uninteresting here; what matters is that
     * the per-character comparison does not shorten for a near-miss.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        byte[] left = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] right = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(left, right);
    }
}
