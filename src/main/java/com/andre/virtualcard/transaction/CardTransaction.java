package com.andre.virtualcard.transaction;

import com.andre.virtualcard.common.MonetaryAmounts;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "card_transaction")
public class CardTransaction {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "card_id", nullable = false)
    private UUID cardId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private TransactionType type;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "decline_reason", length = 32)
    private DeclineReason declineReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CardTransaction() {
    }

    private CardTransaction(
            UUID id,
            UUID cardId,
            TransactionType type,
            BigDecimal amount,
            TransactionStatus status,
            DeclineReason declineReason,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.cardId = Objects.requireNonNull(cardId, "cardId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.declineReason = declineReason;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public static CardTransaction initialFunding(UUID id, UUID cardId, BigDecimal amount, Instant createdAt) {
        return new CardTransaction(
                id,
                cardId,
                TransactionType.INITIAL_FUNDING,
                MonetaryAmounts.requirePositive(amount, "amount"),
                TransactionStatus.SUCCESSFUL,
                null,
                createdAt
        );
    }

    public static CardTransaction successfulSpend(UUID id, UUID cardId, BigDecimal amount, Instant createdAt) {
        return successful(TransactionType.SPEND, id, cardId, amount, createdAt);
    }

    public static CardTransaction successfulTopUp(UUID id, UUID cardId, BigDecimal amount, Instant createdAt) {
        return successful(TransactionType.TOP_UP, id, cardId, amount, createdAt);
    }

    public static CardTransaction declinedSpend(
            UUID id,
            UUID cardId,
            BigDecimal amount,
            DeclineReason declineReason,
            Instant createdAt
    ) {
        Objects.requireNonNull(declineReason, "declineReason must not be null");
        return new CardTransaction(
                id,
                cardId,
                TransactionType.SPEND,
                MonetaryAmounts.requirePositive(amount, "amount"),
                TransactionStatus.DECLINED,
                declineReason,
                createdAt
        );
    }

    public static CardTransaction declinedTopUp(
            UUID id,
            UUID cardId,
            BigDecimal amount,
            DeclineReason declineReason,
            Instant createdAt
    ) {
        Objects.requireNonNull(declineReason, "declineReason must not be null");
        if (declineReason == DeclineReason.INSUFFICIENT_FUNDS) {
            throw new IllegalArgumentException("TOP_UP cannot be declined with reason INSUFFICIENT_FUNDS");
        }
        return new CardTransaction(
                id,
                cardId,
                TransactionType.TOP_UP,
                MonetaryAmounts.requirePositive(amount, "amount"),
                TransactionStatus.DECLINED,
                declineReason,
                createdAt
        );
    }

    private static CardTransaction successful(
            TransactionType type,
            UUID id,
            UUID cardId,
            BigDecimal amount,
            Instant createdAt
    ) {
        return new CardTransaction(
                id,
                cardId,
                type,
                MonetaryAmounts.requirePositive(amount, "amount"),
                TransactionStatus.SUCCESSFUL,
                null,
                createdAt
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getCardId() {
        return cardId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public DeclineReason getDeclineReason() {
        return declineReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
