package com.andre.virtualcard.transaction;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull(message = "amount is required")
        BigDecimal amount
) {
}
