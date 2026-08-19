package com.postalid.verification.web;

import com.postalid.verification.config.PostalIdProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    private static RateLimiter limiterAllowing(int verifyPerClient, int verifyPerIp) {
        PostalIdProperties properties = new PostalIdProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setVerifyPerClientPerMinute(verifyPerClient);
        properties.getRateLimit().setVerifyPerIpPerMinute(verifyPerIp);
        return new RateLimiter(properties);
    }

    @Test
    @DisplayName("requests are allowed up to the budget and refused after it")
    void enforcesTheBudget() {
        RateLimiter limiter = limiterAllowing(3, 100);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.checkVerifyByClient("client-a").allowed())
                    .as("request %d should be within budget", i + 1).isTrue();
        }

        RateLimiter.Decision refused = limiter.checkVerifyByClient("client-a");
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.retryAfterSeconds()).isPositive();
    }

    @Test
    @DisplayName("one caller exhausting its budget does not affect another")
    void budgetsArePerCaller() {
        RateLimiter limiter = limiterAllowing(2, 100);

        limiter.checkVerifyByClient("client-a");
        limiter.checkVerifyByClient("client-a");
        assertThat(limiter.checkVerifyByClient("client-a").allowed()).isFalse();

        assertThat(limiter.checkVerifyByClient("client-b").allowed()).isTrue();
    }

    @Test
    @DisplayName("the client budget and the address budget are counted separately")
    void clientAndAddressBudgetsAreIndependent() {
        RateLimiter limiter = limiterAllowing(1, 5);

        assertThat(limiter.checkVerifyByClient("client-a").allowed()).isTrue();
        assertThat(limiter.checkVerifyByClient("client-a").allowed()).isFalse();

        // Same identifier, different budget: the address bucket is untouched.
        assertThat(limiter.checkVerifyByIp("client-a").allowed()).isTrue();
    }

    @Test
    @DisplayName("unauthenticated callers share one bucket rather than bypassing the limit")
    void nullCallerIsBucketed() {
        RateLimiter limiter = limiterAllowing(2, 100);

        assertThat(limiter.checkVerifyByClient(null).allowed()).isTrue();
        assertThat(limiter.checkVerifyByClient(null).allowed()).isTrue();
        assertThat(limiter.checkVerifyByClient(null).allowed()).isFalse();
    }

    @Test
    void disablingTheLimiterAllowsEverything() {
        PostalIdProperties properties = new PostalIdProperties();
        properties.getRateLimit().setEnabled(false);
        properties.getRateLimit().setVerifyPerClientPerMinute(1);
        RateLimiter limiter = new RateLimiter(properties);

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.checkVerifyByClient("client-a").allowed()).isTrue();
        }
    }
}
