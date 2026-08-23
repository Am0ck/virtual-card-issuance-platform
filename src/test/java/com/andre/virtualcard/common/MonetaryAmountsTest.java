package com.andre.virtualcard.common;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MonetaryAmountsTest {

    @Nested
    class ScaleCanonicalization {

        @Test
        void equivalentRepresentationsCanonicalizeToTheSameValue() {
            String[] equivalents = {"20", "20.0", "20.00", "20.000", "2E+1"};

            for (String representation : equivalents) {
                BigDecimal canonical = MonetaryAmounts.requirePositive(
                        new BigDecimal(representation), "amount");
                assertEquals(new BigDecimal("20.00"), canonical);
            }
        }

        @Test
        void valuesRequiringRoundingAreRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> MonetaryAmounts.requirePositive(new BigDecimal("20.001"), "amount"));
        }
    }

    @Nested
    class NumericRange {

        private static final String MAX_AMOUNT = "99999999999999999.99";

        @Test
        void acceptsMaximumRepresentableAmount() {
            assertEquals(new BigDecimal(MAX_AMOUNT),
                    MonetaryAmounts.requirePositive(new BigDecimal(MAX_AMOUNT), "amount"));
            assertEquals(new BigDecimal(MAX_AMOUNT),
                    MonetaryAmounts.requireNonNegative(new BigDecimal(MAX_AMOUNT), "initialBalance"));
        }

        @Test
        void rejectsAmountsExceedingPrecision() {
            assertThrows(IllegalArgumentException.class,
                    () -> MonetaryAmounts.requirePositive(
                            new BigDecimal("100000000000000000.00"), "amount"));
            assertThrows(IllegalArgumentException.class,
                    () -> MonetaryAmounts.requireNonNegative(
                            new BigDecimal("100000000000000000"), "initialBalance"));
            assertThrows(IllegalArgumentException.class,
                    () -> MonetaryAmounts.requireWithinNumericRange(
                            new BigDecimal("100000000000000000.00"), "resulting balance"));
        }

        @Test
        void rejectsNegativeAndZeroForPositiveRequirement() {
            assertThrows(IllegalArgumentException.class,
                    () -> MonetaryAmounts.requirePositive(new BigDecimal("-0.01"), "amount"));
            assertThrows(IllegalArgumentException.class,
                    () -> MonetaryAmounts.requirePositive(BigDecimal.ZERO, "amount"));
            assertThrows(NullPointerException.class,
                    () -> MonetaryAmounts.requirePositive(null, "amount"));
        }
    }
}
