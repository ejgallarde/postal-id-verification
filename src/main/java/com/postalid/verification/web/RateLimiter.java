package com.postalid.verification.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.postalid.verification.config.PostalIdProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
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

    public RateLimiter(PostalIdProperties properties) {
        this.config = properties.getRateLimit();
        this.buckets = Caffeine.newBuilder()
                .expireAfterAccess(10, TimeUnit.MINUTES)
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

    public Decision checkImageByClient(String clientId) {
        return check("image:client:" + nullSafe(clientId), config.getImagePerClientPerMinute());
    }

    private Decision check(String key, int permitsPerMinute) {
        if (!config.isEnabled()) {
            return Decision.ALLOWED;
        }

        Bucket bucket = buckets.get(key, ignored -> newBucket(permitsPerMinute));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Decision(true, 0, probe.getRemainingTokens());
        }

        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        return new Decision(false, retryAfter, 0);
    }

    private static Bucket newBucket(int permitsPerMinute) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(permitsPerMinute)
                // Greedy refill drips tokens back continuously rather than releasing a full
                // minute's worth at once, which stops callers synchronising into bursts.
                .refillGreedy(permitsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private static String nullSafe(String value) {
        return value == null ? "anonymous" : value;
    }
}
