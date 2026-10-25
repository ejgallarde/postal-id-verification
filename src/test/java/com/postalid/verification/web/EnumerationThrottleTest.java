package com.postalid.verification.web;

import com.postalid.verification.config.PostalIdProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The control that stops patient enumeration.
 *
 * <p>This existed as configuration and documentation for some time without any code behind it, so
 * these tests exist as much to keep it wired as to check the arithmetic: a security control nobody
 * exercises is one that can be deleted by accident and never missed.
 */
class EnumerationThrottleTest {

    private static EnumerationThrottle throttleAfter(int threshold) {
        PostalIdProperties properties = new PostalIdProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setInvalidBurstThreshold(threshold);
        properties.getRateLimit().setInvalidLookback(Duration.ofMinutes(10));
        properties.getRateLimit().setInvalidPenalty(Duration.ofMinutes(5));
        return new EnumerationThrottle(properties);
    }

    @Test
    @DisplayName("a caller under the threshold is left alone")
    void allowsCallersBelowTheThreshold() {
        EnumerationThrottle throttle = throttleAfter(5);

        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("client-a");
            assertThat(throttle.retryAfterSeconds("client-a")).isZero();
        }
    }

    @Test
    @DisplayName("crossing the threshold starts a penalty")
    void penalisesSustainedFailure() {
        EnumerationThrottle throttle = throttleAfter(5);

        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("client-a");
        }

        assertThat(throttle.retryAfterSeconds("client-a"))
                .as("caller should be held off for the configured penalty")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("a success on a new card earns back one failure, so an occasional typo never accumulates")
    void successOnDistinctCardEarnsCredit() {
        EnumerationThrottle throttle = throttleAfter(5);

        // A legitimate counter: a typo, then the corrected entry, card after card.
        for (int card = 0; card < 50; card++) {
            throttle.recordFailure("client-a");
            throttle.recordSuccess("client-a", "record-" + card);
        }
        assertThat(throttle.retryAfterSeconds("client-a")).isZero();
    }

    @Test
    @DisplayName("replaying one known-good card does not launder a stream of guesses")
    void replayingTheSameCardEarnsNothing() {
        EnumerationThrottle throttle = throttleAfter(5);

        // The old reset-on-success rule let this run forever: four guesses, one known-good card,
        // repeat. Only the first success for the card counts, so the count still climbs.
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 4; i++) {
                throttle.recordFailure("client-a");
            }
            throttle.recordSuccess("client-a", "known-good-record");
        }
        assertThat(throttle.retryAfterSeconds("client-a"))
                .as("interleaved replays of one card should not hold off the penalty")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("a success only earns back one failure, it does not clear the count")
    void successDoesNotResetTheCount() {
        EnumerationThrottle throttle = throttleAfter(5);

        for (int i = 0; i < 4; i++) {
            throttle.recordFailure("client-a");
        }
        throttle.recordSuccess("client-a", "record-1");

        // 4 - 1 = 3 net; two more reach the threshold of 5.
        throttle.recordFailure("client-a");
        assertThat(throttle.retryAfterSeconds("client-a")).isZero();
        throttle.recordFailure("client-a");
        assertThat(throttle.retryAfterSeconds("client-a")).isGreaterThan(0);
    }

    @Test
    @DisplayName("one caller tripping the throttle does not affect another")
    void penaltiesArePerCaller() {
        EnumerationThrottle throttle = throttleAfter(3);

        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("client-a");
        }

        assertThat(throttle.retryAfterSeconds("client-a")).isGreaterThan(0);
        assertThat(throttle.retryAfterSeconds("client-b")).isZero();
    }

    @Test
    @DisplayName("unauthenticated callers share a bucket rather than escaping the control")
    void anonymousCallersAreBucketed() {
        EnumerationThrottle throttle = throttleAfter(3);

        for (int i = 0; i < 3; i++) {
            throttle.recordFailure(null);
        }

        assertThat(throttle.retryAfterSeconds(null)).isGreaterThan(0);
    }

    @Test
    @DisplayName("disabling rate limiting disables this too, so tests and dev are not fighting it")
    void respectsTheDisabledFlag() {
        PostalIdProperties properties = new PostalIdProperties();
        properties.getRateLimit().setEnabled(false);
        properties.getRateLimit().setInvalidBurstThreshold(2);
        EnumerationThrottle throttle = new EnumerationThrottle(properties);

        for (int i = 0; i < 50; i++) {
            throttle.recordFailure("client-a");
        }

        assertThat(throttle.retryAfterSeconds("client-a")).isZero();
    }
}
