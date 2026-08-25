package com.andre.virtualcard.card;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Objects;

/**
 * Global card lifetime configuration. Validated explicitly in the constructor so
 * invalid configuration fails application startup.
 */
@ConfigurationProperties(prefix = "card.expiration")
public record CardExpirationProperties(Duration lifetime, long cleanupIntervalMs) {

    public CardExpirationProperties {
        Objects.requireNonNull(lifetime, "card.expiration.lifetime must not be null");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException(
                    "card.expiration.lifetime must be positive, got " + lifetime);
        }
        if (cleanupIntervalMs <= 0) {
            throw new IllegalArgumentException(
                    "card.expiration.cleanup-interval-ms must be > 0, got " + cleanupIntervalMs);
        }
    }
}
