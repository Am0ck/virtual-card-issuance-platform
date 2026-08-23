package com.andre.virtualcard.card;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreateCardRequest(
        @NotNull(message = "cardholderName is required")
        String cardholderName,

        @NotNull(message = "initialBalance is required")
        BigDecimal initialBalance
) {
}
