package com.andre.virtualcard.card;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CardResponse(
        UUID id,
        String cardholderName,
        BigDecimal balance,
        CardStatus status,
        Instant createdAt
) {

    static CardResponse from(Card card) {
        return new CardResponse(
                card.getId(),
                card.getCardholderName(),
                card.getBalance(),
                card.getStatus(),
                card.getCreatedAt()
        );
    }
}
