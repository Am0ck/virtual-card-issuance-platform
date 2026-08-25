package com.andre.virtualcard.card;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardExpirationPropertiesTest {

    @Nested
    class ConstructionValidation {

        @Test
        void validConfigurationIsAccepted() {
            CardExpirationProperties props =
                    new CardExpirationProperties(Duration.ofDays(365), 60_000L);

            assertThat(props.lifetime()).isEqualTo(Duration.ofDays(365));
            assertThat(props.cleanupIntervalMs()).isEqualTo(60_000L);
        }

        @Test
        void nullLifetimeFailsFast() {
            assertThatThrownBy(() -> new CardExpirationProperties(null, 60_000L))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        void zeroLifetimeFailsFast() {
            assertThatThrownBy(() -> new CardExpirationProperties(Duration.ZERO, 60_000L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void negativeLifetimeFailsFast() {
            assertThatThrownBy(() -> new CardExpirationProperties(Duration.ofDays(-1), 60_000L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void zeroCleanupIntervalFailsFast() {
            assertThatThrownBy(() -> new CardExpirationProperties(Duration.ofDays(365), 0L))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void negativeCleanupIntervalFailsFast() {
            assertThatThrownBy(() -> new CardExpirationProperties(Duration.ofDays(365), -5L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class SpringBinding {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(Config.class);

        @Configuration(proxyBeanMethods = false)
        @EnableConfigurationProperties(CardExpirationProperties.class)
        static class Config {
        }

        @Test
        void validPropertiesBindAndStartContext() {
            runner.withPropertyValues(
                            "card.expiration.lifetime=P30D",
                            "card.expiration.cleanup-interval-ms=1000")
                    .run(context -> {
                        assertThat(context).hasSingleBean(CardExpirationProperties.class);
                        CardExpirationProperties props = context.getBean(CardExpirationProperties.class);
                        assertThat(props.lifetime()).isEqualTo(Duration.ofDays(30));
                        assertThat(props.cleanupIntervalMs()).isEqualTo(1000L);
                    });
        }

        @Test
        void invalidLifetimePreventsContextStartup() {
            runner.withPropertyValues(
                            "card.expiration.lifetime=PT0S",
                            "card.expiration.cleanup-interval-ms=1000")
                    .run(context ->
                            assertThat(context).getFailure().hasRootCauseInstanceOf(
                                    IllegalArgumentException.class));
        }

        @Test
        void invalidCleanupIntervalPreventsContextStartup() {
            runner.withPropertyValues(
                            "card.expiration.lifetime=P30D",
                            "card.expiration.cleanup-interval-ms=0")
                    .run(context ->
                            assertThat(context).getFailure().hasRootCauseInstanceOf(
                                    IllegalArgumentException.class));
        }
    }
}
