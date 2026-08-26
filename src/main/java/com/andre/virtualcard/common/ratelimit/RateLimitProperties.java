package com.andre.virtualcard.common.ratelimit;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        boolean enabled,
        Policy api,
        Policy health,
        Cache cache
) {
    public RateLimitProperties {
        Objects.requireNonNull(api, "rate-limit.api must not be null");
        Objects.requireNonNull(health, "rate-limit.health must not be null");
        Objects.requireNonNull(cache, "rate-limit.cache must not be null");
    }

    public record Policy(long capacity, long refillTokens, Duration refillPeriod) {
        public Policy {
            Objects.requireNonNull(refillPeriod, "refill-period must not be null");
            if (capacity <= 0) {
                throw new IllegalArgumentException(
                        "rate-limit capacity must be > 0, got " + capacity);
            }
            if (refillTokens <= 0) {
                throw new IllegalArgumentException(
                        "rate-limit refill-tokens must be > 0, got " + refillTokens);
            }
            if (refillPeriod.isZero() || refillPeriod.isNegative()) {
                throw new IllegalArgumentException(
                        "rate-limit refill-period must be positive, got " + refillPeriod);
            }
        }
    }

    public record Cache(long maximumSize, Duration expireAfterAccess) {
        public Cache {
            Objects.requireNonNull(expireAfterAccess, "cache expire-after-access must not be null");
            if (maximumSize <= 0) {
                throw new IllegalArgumentException(
                        "rate-limit cache maximum-size must be > 0, got " + maximumSize);
            }
            if (expireAfterAccess.isZero() || expireAfterAccess.isNegative()) {
                throw new IllegalArgumentException(
                        "rate-limit cache expire-after-access must be positive, got " + expireAfterAccess);
            }
        }
    }
}
