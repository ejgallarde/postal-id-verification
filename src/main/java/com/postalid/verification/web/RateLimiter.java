package com.postalid.verification.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.postalid.verification.config.PostalIdProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Per-caller request budgets.
 *
 * <p>Two independent limits apply to verification: one per client credential and one per source
 * address. Either alone is easy to sidestep — a client limit does nothing against many anonymous
 * callers, and an address limit does nothing against one authorized client behind a proxy pool —
 * so both must pass.
 *
 * <p>Buckets are held in a Caffeine cache with an idle expiry, which bounds memory without
 * needing a sweep: a caller that stops calling loses its bucket, and a caller that comes back
 * simply gets a fresh full one, which is the same thing a time-based refill would have given it.
 *
 * <p>This is in-process state. Behind more than one instance the effective limit multiplies by
 * the instance count; a shared backend would be the fix, and the configured numbers should be
 * divided accordingly until then.
 */
@Component
public class RateLimiter {

    private final PostalIdProperties.RateLimit config;
    private final Cache<String, Bucket> buckets;

    /**
     * Buckets whose window is an hour. Held apart because the idle expiry above would otherwise
     * hand a caller who paused for ten minutes a full hour's allowance back.
     */
    private final Cache<String, Bucket> hourlyBuckets;

    public RateLimiter(PostalIdProperties properties) {
        this.config = properties.getRateLimit();
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
                .maximumSize(100_000)
                .build();
        this.hourlyBuckets = Caffeine.newBuilder()
                .expireAfterAccess(1, TimeUnit.HOURS)
                .maximumSize(100_000)
                .build();
    }

    /**
     * What a limit check decided, how long to wait if it said no, and how much budget is left.
     *
     * <p>{@code remaining} is published to callers as a RateLimit header so they can pace
     * themselves, rather than discovering the ceiling only by being refused.
     */
    public record Decision(boolean allowed, long retryAfterSeconds, long remaining) {
        static final Decision ALLOWED = new Decision(true, 0, Long.MAX_VALUE);
    }

    public Decision checkVerifyByClient(String clientId) {
        return check("verify:client:" + nullSafe(clientId), config.getVerifyPerClientPerMinute());
    }

    public Decision checkVerifyByIp(String ip) {
        return check("verify:ip:" + nullSafe(ip), config.getVerifyPerIpPerMinute());
    }

    public Decision checkVerifyByOperator(String operator) {
        return check("verify:operator:" + nullSafe(operator), config.getVerifyPerOperatorPerMinute());
    }

    public Decision checkImageByClient(String clientId) {
        return checkImageByClient(clientId, 1);
    }

    /** Charges several image fetches at once, for a verification that embeds its images. */
    public Decision checkImageByClient(String clientId, int images) {
        return check("image:client:" + nullSafe(clientId), config.getImagePerClientPerMinute(), images);
    }

    /**
     * Kept apart from {@link #checkVerifyByIp}: a VALID result is normally followed by two image
     * fetches, and if those drew on the verification budget an address could only complete about
     * a third of the verifications it was promised.
     */
    public Decision checkImageByIp(String ip) {
        return check("image:ip:" + nullSafe(ip), config.getImagePerIpPerMinute());
    }

    /**
     * Token requests from one address. Checked before the client secret is, because verifying a
     * secret is a deliberately slow BCrypt hash: without this, a stream of requests carrying a
     * real client id and a wrong secret would turn the token endpoint into a CPU exhaustion tool.
     */
    public Decision checkTokenByIp(String ip) {
        return check("token:ip:" + nullSafe(ip), config.getTokenPerIpPerMinute());
    }

    /**
     * Whether a client id still has failed-authentication budget left, without spending any.
     * Successful token requests never draw on this budget; only {@link #recordTokenFailure} does.
     */
    public Decision peekTokenFailures(String clientId) {
        return peekFailures("token-fail:client:", clientId, config.getTokenFailuresPerClientPerHour());
    }

    /** A token request for this client id failed authentication. */
    public void recordTokenFailure(String clientId) {
        recordFailure("token-fail:client:", clientId, config.getTokenFailuresPerClientPerHour());
    }

    /** Console sign-in attempts from one address, checked before the password's BCrypt hash. */
    public Decision checkLoginByIp(String ip) {
        return check("login:ip:" + nullSafe(ip), config.getLoginPerIpPerMinute());
    }

    /** Whether an operator account still has failed sign-in budget left, without spending any. */
    public Decision peekLoginFailures(String username) {
        return peekFailures("login-fail:user:", username, config.getLoginFailuresPerUserPerHour());
    }

    /** A sign-in for this username failed. */
    public void recordLoginFailure(String username) {
        recordFailure("login-fail:user:", username, config.getLoginFailuresPerUserPerHour());
    }

    private Decision peekFailures(String prefix, String subject, int perHour) {
        if (!config.isEnabled() || subject == null) {
            return Decision.ALLOWED;
        }
        Bucket bucket = hourlyBuckets.get(prefix + subject, ignored -> newBucket(perHour, Duration.ofHours(1)));
        EstimationProbe probe = bucket.estimateAbilityToConsume(1);
        if (probe.canBeConsumed()) {
            return new Decision(true, 0, bucket.getAvailableTokens());
        }
        return new Decision(false, toRetryAfter(probe.getNanosToWaitForRefill()), 0);
    }

    private void recordFailure(String prefix, String subject, int perHour) {
        if (!config.isEnabled() || subject == null) {
            return;
        }
        hourlyBuckets.get(prefix + subject, ignored -> newBucket(perHour, Duration.ofHours(1))).tryConsume(1);
    }

    private Decision check(String key, int permitsPerMinute) {
        return check(key, permitsPerMinute, 1);
    }

    private Decision check(String key, int permitsPerMinute, int permits) {
        if (!config.isEnabled()) {
            return Decision.ALLOWED;
        }

        Bucket bucket = buckets.get(key, ignored -> newBucket(permitsPerMinute, Duration.ofMinutes(1)));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(permits);
        if (probe.isConsumed()) {
            return new Decision(true, 0, probe.getRemainingTokens());
        }
        return new Decision(false, toRetryAfter(probe.getNanosToWaitForRefill()), 0);
    }

    private static long toRetryAfter(long nanos) {
        return Math.max(1, TimeUnit.NANOSECONDS.toSeconds(nanos));
    }

    private static Bucket newBucket(int capacity, Duration window) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                // Greedy refill drips tokens back continuously rather than releasing a full
                // window's worth at once, which stops callers synchronising into bursts.
                .refillGreedy(capacity, window)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private static String nullSafe(String value) {
        return value == null ? "anonymous" : value;
    }
}
