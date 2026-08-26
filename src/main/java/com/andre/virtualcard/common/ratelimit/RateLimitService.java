package com.andre.virtualcard.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class RateLimitService {

    private record BucketKey(String clientId, RateLimitPolicy policy) {}

    private final RateLimitProperties properties;
    private final Cache<BucketKey, Bucket> bucketCache;

    public RateLimitService(RateLimitProperties properties) {
        this.properties = properties;
        this.bucketCache = Caffeine.newBuilder()
                .maximumSize(properties.cache().maximumSize())
                .expireAfterAccess(properties.cache().expireAfterAccess().toNanos(), TimeUnit.NANOSECONDS)
                .build();
    }

    public RateLimitDecision tryConsume(String clientId, RateLimitPolicy policy) {
        RateLimitProperties.Policy config = switch (policy) {
            case API -> properties.api();
            case HEALTH -> properties.health();
        };

        BucketKey key = new BucketKey(clientId, policy);
        Bucket bucket = bucketCache.get(key, k -> createBucket(config));

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            return RateLimitDecision.allowed();
        }

        long nanosUntilRefilled = probe.getNanosToWaitForRefill();
        long seconds = Math.max(1, (long) Math.ceil(nanosUntilRefilled / 1_000_000_000.0));
        return RateLimitDecision.rejected(seconds);
    }

    long estimatedSize() {
        bucketCache.cleanUp();
        return bucketCache.estimatedSize();
    }

    private Bucket createBucket(RateLimitProperties.Policy config) {
        return Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(config.capacity())
                        .refillGreedy(config.refillTokens(), config.refillPeriod()))
                .build();
    }
}
