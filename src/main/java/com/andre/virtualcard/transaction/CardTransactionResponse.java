package com.andre.virtualcard.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CardTransactionResponse(
        UUID id,
        UUID cardId,
        String type,
        BigDecimal amount,
        String status,
        Instant createdAt
) {

    public static CardTransactionResponse from(CardTransaction transaction) {
        return new CardTransactionResponse(
                transaction.getId(),
                transaction.getCardId(),
                transaction.getType().name(),
                transaction.getAmount(),
                transaction.getStatus().name(),
                transaction.getCreatedAt()
        );
    }
}
