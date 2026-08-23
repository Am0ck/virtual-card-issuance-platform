package com.andre.virtualcard.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public final class MonetaryAmounts {

    private static final int SCALE = 2;
    private static final int MAX_PRECISION = 19;

    private MonetaryAmounts() {
    }

    public static BigDecimal requirePositive(BigDecimal amount, String fieldName) {
        Objects.requireNonNull(amount, fieldName + " must not be null");
        BigDecimal canonical = canonicalize(amount, fieldName);
        if (canonical.signum() <= 0) {
            throw new IllegalArgumentException(fieldName + " must be greater than zero");
        }
        return canonical;
    }

    public static BigDecimal requireNonNegative(BigDecimal amount, String fieldName) {
        Objects.requireNonNull(amount, fieldName + " must not be null");
        BigDecimal canonical = canonicalize(amount, fieldName);
        if (canonical.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return canonical;
    }

    public static BigDecimal requireWithinNumericRange(BigDecimal amount, String fieldName) {
        Objects.requireNonNull(amount, fieldName + " must not be null");
        return canonicalize(amount, fieldName);
    }

    private static BigDecimal canonicalize(BigDecimal amount, String fieldName) {
        BigDecimal canonical;
        try {
            canonical = amount.stripTrailingZeros().setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    fieldName + " cannot be represented exactly with two decimal places: " + amount,
                    exception
            );
        }
        if (canonical.precision() > MAX_PRECISION) {
            throw new IllegalArgumentException(
                    fieldName + " exceeds the supported maximum of 17 integer digits and "
                            + SCALE + " decimal places: " + amount
            );
        }
        return canonical;
    }
}
