package com.andre.virtualcard.common.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {

    private RateLimitProperties properties;
    private RateLimitService service;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties(
                true,
                new RateLimitProperties.Policy(3, 3, Duration.ofHours(1)),
                new RateLimitProperties.Policy(2, 2, Duration.ofHours(1)),
                new RateLimitProperties.Cache(100, Duration.ofMinutes(10)));
        service = new RateLimitService(properties);
    }

    @Test
    void firstNRequestsAreAllowed() {
        for (int i = 0; i < 3; i++) {
            RateLimitDecision decision = service.tryConsume("client-1", RateLimitPolicy.API);
            assertThat(decision.isAllowed()).isTrue();
        }
    }

    @Test
    void requestNPlusOneIsRejected() {
        for (int i = 0; i < 3; i++) {
            service.tryConsume("client-1", RateLimitPolicy.API);
        }
        RateLimitDecision decision = service.tryConsume("client-1", RateLimitPolicy.API);
        assertThat(decision.isAllowed()).isFalse();
        assertThat(decision.retryAfterSeconds()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void secondClientHasIndependentQuota() {
        for (int i = 0; i < 3; i++) {
            service.tryConsume("client-1", RateLimitPolicy.API);
        }
        RateLimitDecision decision = service.tryConsume("client-2", RateLimitPolicy.API);
        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void sameClientUnderDifferentPolicyHasIndependentQuota() {
        for (int i = 0; i < 3; i++) {
            service.tryConsume("client-1", RateLimitPolicy.API);
        }
        RateLimitDecision decision = service.tryConsume("client-1", RateLimitPolicy.HEALTH);
        assertThat(decision.isAllowed()).isTrue();
    }

    @Test
    void healthPolicyUsesOwnCapacity() {
        for (int i = 0; i < 2; i++) {
            service.tryConsume("client-1", RateLimitPolicy.HEALTH);
        }
        RateLimitDecision decision = service.tryConsume("client-1", RateLimitPolicy.HEALTH);
        assertThat(decision.isAllowed()).isFalse();
    }

    @Test
    void registryRemainsBoundedAfterExceedingMaximumSize() {
        RateLimitProperties smallCache = new RateLimitProperties(
                true,
                new RateLimitProperties.Policy(100, 100, Duration.ofMinutes(1)),
                new RateLimitProperties.Policy(100, 100, Duration.ofMinutes(1)),
                new RateLimitProperties.Cache(5, Duration.ofMinutes(10)));
        RateLimitService boundedService = new RateLimitService(smallCache);

        for (int i = 0; i < 20; i++) {
            boundedService.tryConsume("client-" + i, RateLimitPolicy.API);
        }

        assertThat(boundedService.estimatedSize()).isLessThanOrEqualTo(5);
    }

    @Test
    void rejectedDecisionHasReasonableRetryAfter() {
        for (int i = 0; i < 3; i++) {
            service.tryConsume("client-1", RateLimitPolicy.API);
        }
        RateLimitDecision decision = service.tryConsume("client-1", RateLimitPolicy.API);
        assertThat(decision.retryAfterSeconds()).isBetween(1L, 3601L);
    }
}
