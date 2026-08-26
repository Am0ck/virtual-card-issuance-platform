package com.andre.virtualcard.common.ratelimit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    @Nested
    class ConstructionValidation {

        @Test
        void validConfigurationIsAccepted() {
            RateLimitProperties props = new RateLimitProperties(
                    true,
                    new RateLimitProperties.Policy(100, 100, Duration.ofMinutes(1)),
                    new RateLimitProperties.Policy(50, 50, Duration.ofMinutes(1)),
                    new RateLimitProperties.Cache(1000, Duration.ofMinutes(5)));

            assertThat(props.enabled()).isTrue();
            assertThat(props.api().capacity()).isEqualTo(100);
            assertThat(props.health().capacity()).isEqualTo(50);
            assertThat(props.cache().maximumSize()).isEqualTo(1000);
        }

        @Test
        void nullApiPolicyFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties(true, null,
                    new RateLimitProperties.Policy(50, 50, Duration.ofMinutes(1)),
                    new RateLimitProperties.Cache(1000, Duration.ofMinutes(5))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void nullHealthPolicyFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties(true,
                    new RateLimitProperties.Policy(100, 100, Duration.ofMinutes(1)),
                    null,
                    new RateLimitProperties.Cache(1000, Duration.ofMinutes(5))))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void nullCacheFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties(true,
                    new RateLimitProperties.Policy(100, 100, Duration.ofMinutes(1)),
                    new RateLimitProperties.Policy(50, 50, Duration.ofMinutes(1)),
                    null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void zeroCapacityFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Policy(0, 100, Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity");
        }

        @Test
        void negativeCapacityFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Policy(-1, 100, Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("capacity");
        }

        @Test
        void zeroRefillTokensFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Policy(100, 0, Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refill-tokens");
        }

        @Test
        void negativeRefillTokensFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Policy(100, -1, Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refill-tokens");
        }

        @Test
        void nullRefillPeriodFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Policy(100, 100, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void zeroRefillPeriodFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Policy(100, 100, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refill-period");
        }

        @Test
        void negativeRefillPeriodFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Policy(100, 100, Duration.ofMinutes(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("refill-period");
        }

        @Test
        void zeroCacheMaximumSizeFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Cache(0, Duration.ofMinutes(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maximum-size");
        }

        @Test
        void negativeCacheMaximumSizeFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Cache(-1, Duration.ofMinutes(5)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("maximum-size");
        }

        @Test
        void nullCacheExpiryFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Cache(1000, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void zeroCacheExpiryFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Cache(1000, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expire-after-access");
        }

        @Test
        void negativeCacheExpiryFailsFast() {
            assertThatThrownBy(() -> new RateLimitProperties.Cache(1000, Duration.ofMinutes(-1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("expire-after-access");
        }
    }

    @Nested
    class SpringBinding {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(Config.class);

        @Configuration(proxyBeanMethods = false)
        @EnableConfigurationProperties(RateLimitProperties.class)
        static class Config {
        }

        @Test
        void validPropertiesBindAndStartContext() {
            runner.withPropertyValues(
                            "rate-limit.enabled=true",
                            "rate-limit.api.capacity=200",
                            "rate-limit.api.refill-tokens=200",
                            "rate-limit.api.refill-period=PT1M",
                            "rate-limit.health.capacity=100",
                            "rate-limit.health.refill-tokens=100",
                            "rate-limit.health.refill-period=PT1M",
                            "rate-limit.cache.maximum-size=5000",
                            "rate-limit.cache.expire-after-access=PT5M")
                    .run(context -> {
                        assertThat(context).hasSingleBean(RateLimitProperties.class);
                        RateLimitProperties props = context.getBean(RateLimitProperties.class);
                        assertThat(props.enabled()).isTrue();
                        assertThat(props.api().capacity()).isEqualTo(200);
                        assertThat(props.health().capacity()).isEqualTo(100);
                        assertThat(props.cache().maximumSize()).isEqualTo(5000);
                    });
        }

        @Test
        void disabledConfigurationBinds() {
            runner.withPropertyValues(
                            "rate-limit.enabled=false",
                            "rate-limit.api.capacity=10",
                            "rate-limit.api.refill-tokens=10",
                            "rate-limit.api.refill-period=PT1S",
                            "rate-limit.health.capacity=5",
                            "rate-limit.health.refill-tokens=5",
                            "rate-limit.health.refill-period=PT1S",
                            "rate-limit.cache.maximum-size=100",
                            "rate-limit.cache.expire-after-access=PT1M")
                    .run(context -> {
                        assertThat(context).hasSingleBean(RateLimitProperties.class);
                        assertThat(context.getBean(RateLimitProperties.class).enabled()).isFalse();
                    });
        }

        @Test
        void invalidApiCapacityPreventsContextStartup() {
            runner.withPropertyValues(
                            "rate-limit.enabled=true",
                            "rate-limit.api.capacity=0",
                            "rate-limit.api.refill-tokens=10",
                            "rate-limit.api.refill-period=PT1M",
                            "rate-limit.health.capacity=10",
                            "rate-limit.health.refill-tokens=10",
                            "rate-limit.health.refill-period=PT1M",
                            "rate-limit.cache.maximum-size=100",
                            "rate-limit.cache.expire-after-access=PT1M")
                    .run(context ->
                            assertThat(context).getFailure().hasRootCauseInstanceOf(
                                    IllegalArgumentException.class));
        }

        @Test
        void invalidCacheMaximumSizePreventsContextStartup() {
            runner.withPropertyValues(
                            "rate-limit.enabled=true",
                            "rate-limit.api.capacity=10",
                            "rate-limit.api.refill-tokens=10",
                            "rate-limit.api.refill-period=PT1M",
                            "rate-limit.health.capacity=10",
                            "rate-limit.health.refill-tokens=10",
                            "rate-limit.health.refill-period=PT1M",
                            "rate-limit.cache.maximum-size=0",
                            "rate-limit.cache.expire-after-access=PT1M")
                    .run(context ->
                            assertThat(context).getFailure().hasRootCauseInstanceOf(
                                    IllegalArgumentException.class));
        }
    }
}
