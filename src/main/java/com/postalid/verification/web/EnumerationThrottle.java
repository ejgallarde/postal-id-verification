package com.postalid.verification.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.postalid.verification.config.PostalIdProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Blunts patient enumeration that stays under the per-minute ceiling.
 *
 * <p>The plain rate limit stops a flood. It does nothing about a client that verifies steadily,
 * within budget, and is wrong almost every time — which is exactly what walking a range of
 * reference numbers looks like. A legitimate caller checks cards people actually hold, so its
 * failure rate is low; a caller failing dozens of times in a row is doing something else.
 *
 * <p>Counting is deliberately in memory rather than by querying the audit table on every request:
 * a database round trip per verification to decide whether to allow a verification is a poor trade,
 * and the audit trail remains the authoritative record either way.
 *
 * <p>Like {@link RateLimiter}, this is per-instance state. Behind several instances a client gets
 * roughly that many times the allowance before tripping.
 */
@Component
public class EnumerationThrottle {

    private static final Logger log = LoggerFactory.getLogger(EnumerationThrottle.class);

    private final PostalIdProperties.RateLimit config;

    /** Consecutive INVALID results per caller, expiring on the configured lookback. */
    private final Cache<String, Integer> consecutiveFailures;

    /** Callers currently serving a penalty, expiring when it lapses. */
    private final Cache<String, Instant> penalised;

    public EnumerationThrottle(PostalIdProperties properties) {
        this.config = properties.getRateLimit();

        Duration lookback = config.getInvalidLookback();
        this.consecutiveFailures = Caffeine.newBuilder()
                .expireAfterWrite(lookback)
                .maximumSize(100_000)
                .build();
        this.penalised = Caffeine.newBuilder()
                .expireAfterWrite(config.getInvalidPenalty().plusMinutes(1))
                .maximumSize(100_000)
                .build();
    }

    /**
     * @return seconds the caller must wait, or 0 when they may proceed
     */
    public long retryAfterSeconds(String caller) {
        if (!config.isEnabled()) {
            return 0;
        }

        Instant until = penalised.getIfPresent(key(caller));
        if (until == null) {
            return 0;
        }
        long remaining = Duration.between(Instant.now(), until).toSeconds();
        if (remaining <= 0) {
            penalised.invalidate(key(caller));
            return 0;
        }
        return remaining;
    }

    /**
     * A verification came back INVALID. Counts it, and starts a penalty once a caller crosses the
     * threshold.
     */
    public void recordFailure(String caller) {
        if (!config.isEnabled()) {
            return;
        }

        String key = key(caller);
        int failures = consecutiveFailures.asMap().merge(key, 1, Integer::sum);

        if (failures >= config.getInvalidBurstThreshold()) {
            Instant until = Instant.now().plus(config.getInvalidPenalty());
            penalised.put(key, until);
            consecutiveFailures.invalidate(key);

            // Worth a warning: this is the signature of someone walking reference numbers, and an
            // operator reviewing the audit trail should be able to find it from the log too.
            log.warn("Throttling caller '{}' after {} consecutive INVALID results; blocked for {}",
                    key, failures, config.getInvalidPenalty());
        }
    }

    /** A verification succeeded, so the caller is behaving like a legitimate one again. */
    public void recordSuccess(String caller) {
        if (config.isEnabled()) {
            consecutiveFailures.invalidate(key(caller));
        }
    }

    private static String key(String caller) {
        return caller == null ? "anonymous" : caller;
    }
}
